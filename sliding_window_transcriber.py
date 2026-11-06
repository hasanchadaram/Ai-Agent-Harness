"""
Sliding Window Background Transcription using Qwen3-ASR + Qwen3-ForcedAligner.

This module maintains an audio buffer, rejects non-speech with Silero VAD,
and reconciles estimated word timestamps across overlapping acoustic windows.
The tentative tail is re-decoded with more context before it is committed.
"""

from dataclasses import dataclass
from typing import List, Optional, Generator
import numpy as np
import soundfile as sf
import torch

from qwen_asr import Qwen3ASRModel
from asr_streaming import AlignmentError, attach_punctuation, partition_window, deduplicate_timed_words
from speech_detection import transcribe_speech


@dataclass(frozen=True)
class WordTimestamp:
    text: str
    start_time: float  # Absolute time in seconds from stream start
    end_time: float    # Absolute time in seconds from stream start


def attach_punctuation_timestamps(full_text, items):
    words = [{"word": w.text, "start": w.start_time, "end": w.end_time} for w in items]
    attach_punctuation(full_text, words)
    return [WordTimestamp(w["word"], w["start"], w["end"]) for w in words]


@dataclass
class WindowUpdate:
    window_index: int
    window_start: float
    window_end: float
    committed_words: List[WordTimestamp]
    preview_words: List[WordTimestamp]
    total_text: str
    is_final: bool
    recovery_required: bool = False
    recovered: bool = False


class SlidingWindowTranscriber:
    """
    Real-time sliding window transcriber using Qwen3-ASR + Qwen3-ForcedAligner.
    """

    def __init__(
        self,
        asr_model_name: str = "Qwen/Qwen3-ASR-1.7B",
        aligner_model_name: str = "Qwen/Qwen3-ForcedAligner-0.6B",
        sample_rate: int = 16000,
        window_size_sec: float = 6.0,
        min_step_sec: float = 2.5,
        overlap_sec: float = 2.0,
        language: Optional[str] = "English",
        device: str = "cuda:0",
        dtype: torch.dtype = torch.bfloat16,
    ):
        if sample_rate <= 0 or not 0 <= overlap_sec < window_size_sec - .5:
            raise ValueError("Window must exceed overlap plus 0.5 seconds of context")
        self.sample_rate = sample_rate
        self.window_size_sec = window_size_sec
        self.min_step_sec = min_step_sec
        self.overlap_sec = overlap_sec
        self.language = language
        self.device = device

        print(f"[ASR] Loading {asr_model_name} + {aligner_model_name} on {device}...")
        self.model = Qwen3ASRModel.from_pretrained(
            asr_model_name,
            dtype=dtype,
            device_map=device,
            forced_aligner=aligner_model_name,
            forced_aligner_kwargs=dict(
                dtype=dtype,
                device_map=device,
            ),
            max_inference_batch_size=16,
            max_new_tokens=4096,
        )
        print("[ASR] Models ready.")

        self.reset()

    def reset(self):
        """Resets the streaming state for a new audio session."""
        self.audio_buffer = np.zeros(0, dtype=np.float32)
        self.window_start_sample = 0
        self.committed_words: List[WordTimestamp] = []
        self.preview_words: List[WordTimestamp] = []
        self.window_count = 0
        self.last_committed_time = 0.0
        self.recovery_required = False
        self.recovered = False
        self.final_text = None

    def add_audio_bytes(self, pcm_bytes: bytes):
        """Appends 16-bit mono 16kHz PCM audio bytes."""
        samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
        self.add_audio_samples(samples)

    def add_audio_samples(self, samples: np.ndarray):
        """Appends float32 normalized [-1.0, 1.0] audio samples."""
        samples = np.asarray(samples, dtype=np.float32)
        if samples.ndim != 1 or not np.isfinite(samples).all():
            raise ValueError("Audio samples must be finite mono PCM")
        self.audio_buffer = np.concatenate([self.audio_buffer, samples])

    @property
    def total_audio_sec(self) -> float:
        return len(self.audio_buffer) / self.sample_rate

    @property
    def buffered_unprocessed_sec(self) -> float:
        return (len(self.audio_buffer) - self.window_start_sample) / self.sample_rate

    def should_process(self) -> bool:
        """Returns True if enough audio has accumulated for a sliding window."""
        return not self.recovery_required and self.buffered_unprocessed_sec >= self.window_size_sec

    def step(self, is_final: bool = False) -> Optional[WindowUpdate]:
        """
        Processes the current sliding window.
        Returns WindowUpdate if a window was processed, otherwise None.
        """
        if self.recovery_required:
            return None  # Capture can continue; finish() owns the final retry.
        unprocessed_sec = self.buffered_unprocessed_sec
        if not is_final and unprocessed_sec < self.window_size_sec:
            return None

        if self.window_start_sample >= len(self.audio_buffer):
            return None
        # A final call with a backlog processes a regular bounded window first.
        # Call finish() to drain until the actual final tail has been submitted.
        is_final = is_final and unprocessed_sec <= self.window_size_sec

        # Slice the audio window
        start_samp = self.window_start_sample
        window_len_samp = min(
            int(self.window_size_sec * self.sample_rate),
            len(self.audio_buffer) - start_samp
        )
        end_samp = start_samp + window_len_samp
        window_audio = self.audio_buffer[start_samp:end_samp]

        w_start_sec = start_samp / self.sample_rate
        w_end_sec = end_samp / self.sample_rate

        # Run transcription + forced alignment
        results = transcribe_speech(window_audio, self.sample_rate, self.model.transcribe,
                                    [self.language] if self.language else None)

        res = results[0]
        words = [{"word": w.text, "start": w.start_time, "end": w.end_time}
                 for w in (res.time_stamps or [])]
        try:
            update = partition_window(res.text, words, w_start_sec, w_end_sec - w_start_sec,
                                      self.overlap_sec, self.last_committed_time, is_final)
        except AlignmentError:
            self.recovery_required = True
            return WindowUpdate(self.window_count, w_start_sec, w_end_sec,
                list(self.committed_words), list(self.preview_words),
                ' '.join(w.text for w in self.committed_words + self.preview_words),
                False, recovery_required=True)
        previous = [{"word": w.text, "start": w.start_time, "end": w.end_time} for w in self.committed_words[-12:]]
        newly_committed = deduplicate_timed_words(previous, update["committed_words"])
        self.committed_words.extend(WordTimestamp(w["word"], w["start"], w["end"])
                                    for w in newly_committed)
        self.preview_words = [WordTimestamp(w["word"], w["start"], w["end"])
                              for w in update["preview_words"]]
        self.last_committed_time = update["last_committed_time_sec"]
        self.window_start_sample = round(update["next_start_sec"] * self.sample_rate)

        self.window_count += 1

        all_text = " ".join([w.text for w in self.committed_words] + [w.text for w in self.preview_words])

        return WindowUpdate(
            window_index=self.window_count,
            window_start=w_start_sec,
            window_end=w_end_sec,
            committed_words=list(self.committed_words),
            preview_words=list(self.preview_words),
            total_text=all_text.strip(),
            is_final=is_final,
        )

    def process_file_stream(
        self,
        audio_path: str,
        chunk_sec: float = 0.5,
    ) -> Generator[WindowUpdate, None, None]:
        """
        Simulates streaming an audio file in small real-time chunks (e.g. 0.5s per chunk).
        Yields WindowUpdate events whenever a window is processed.
        """
        wav, sr = sf.read(audio_path, dtype="float32")
        if wav.ndim == 2:
            wav = wav.mean(axis=1)
        if chunk_sec <= 0:
            raise ValueError("chunk_sec must be positive")
        if sr != self.sample_rate:
            import librosa
            wav = librosa.resample(wav, orig_sr=sr, target_sr=self.sample_rate)

        chunk_samples = max(1, int(chunk_sec * self.sample_rate))
        total_samples = len(wav)

        self.reset()

        for idx in range(0, total_samples, chunk_samples):
            chunk = wav[idx:idx + chunk_samples]
            self.add_audio_samples(chunk)

            while self.should_process():
                update = self.step(is_final=False)
                if update:
                    yield update

        yield from self.finish()

    def finish(self):
        """Drain the tail, or replace partial text with a full decode after alignment failure.

        Recovery returns text without word timestamps. Callers should use the
        authoritative total_text instead of appending earlier updates.
        """
        while not self.recovery_required and self.window_start_sample < len(self.audio_buffer):
            update = self.step(is_final=True)
            if update is None:
                break
            yield update
        if self.recovery_required:
            # The final text is authoritative. Word timestamps are unavailable
            # on this path: never fabricate offsets or append the old prefix.
            results = transcribe_speech(self.audio_buffer, self.sample_rate, self.model.transcribe,
                [self.language] if self.language else None, return_time_stamps=False)
            text = results[0].text.strip()
            if not text:
                raise ValueError('Full-recording ASR recovery returned no text; original audio retained.')
            self.committed_words = []
            self.preview_words = []
            self.final_text = text
            self.window_start_sample = len(self.audio_buffer)
            self.window_count += 1
            self.recovery_required = False
            self.recovered = True
            yield WindowUpdate(self.window_count, 0.0, self.total_audio_sec,
                [], [], text, True, recovered=True)

