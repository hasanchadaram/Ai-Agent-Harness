"""Windows push-to-talk dictation using the Android streaming pipeline.

Ctrl+Win: raw dictation. Add Shift for mobile-style cleanup.
"""
import argparse
import asyncio
import io
import queue
import threading
import time
import wave
import winsound

import numpy as np
import sounddevice as sd

import pc_dictate as win
from mobile_streaming import (MAX_SAMPLES, OVERLAP_SAMPLES, SAMPLE_RATE, WINDOW_SAMPLES,
                              StreamingAudio, StreamingTranscriber,
                              mobile_client, polish, transcribe)


def _make_cue(frequencies):
    """Create a short, softly enveloped two-note chime as an in-memory WAV."""
    rate = 24000
    duration = 0.095
    step = 0.065
    t = np.arange(int(rate * duration)) / rate
    envelope = np.minimum(t / 0.012, 1) * np.minimum((duration - t) / 0.06, 1)
    samples = np.zeros(int(rate * (duration + step)))
    for index, frequency in enumerate(frequencies):
        note = (np.sin(2 * np.pi * frequency * t) +
                0.15 * np.sin(4 * np.pi * frequency * t)) * envelope
        offset = int(index * step * rate)
        samples[offset:offset + len(note)] += note
    samples *= 0.18 / np.max(np.abs(samples))
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(rate)
        output.writeframes((samples * 32767).astype('<i2').tobytes())
    return buffer.getvalue()


class _CuePlayer:
    def __init__(self):
        self.cues = {'start': _make_cue((660, 880)), 'stop': _make_cue((880, 660))}
        self.queue = queue.Queue()
        threading.Thread(target=self._run, daemon=True).start()

    def play(self, cue):
        self.queue.put_nowait(self.cues[cue])

    def close(self):
        self.queue.put_nowait(None)

    def _run(self):
        while True:
            cue = self.queue.get()
            if cue is None:
                return
            try:
                winsound.PlaySound(cue, winsound.SND_MEMORY | winsound.SND_NODEFAULT)
            except (OSError, RuntimeError) as error:
                print(f'  [Sound] Unable to play cue: {error}')


class PcDictationV2(win.PcDictationClient):
    def __init__(self, default_cleanup=False, **kwargs):
        kwargs.setdefault('window_size_sec', WINDOW_SAMPLES / SAMPLE_RATE)
        kwargs.setdefault('overlap_sec', OVERLAP_SAMPLES / SAMPLE_RATE)
        kwargs.setdefault('play_sound', True)
        super().__init__(**kwargs)
        self._cue_player = _CuePlayer() if self.play_sound else None
        self.default_cleanup = default_cleanup
        self.limit_reached = threading.Event()
        self.audio = None
        self.engine = None
        self.ingest_thread = None
        self.engine_thread = None
        self.result = None
        self.engine_error = None
        self.ingest_error = None
        self.progress = {}
        self.engine_loop = self.engine_task = None
        self.cancel_requested = threading.Event()
        self.recording_started = None
        self.asr_request_number = 0

    def beep_start(self):
        if self._cue_player:
            self._cue_player.play('start')

    def beep_stop(self):
        if self._cue_player:
            self._cue_player.play('stop')

    def audio_callback(self, indata, frames, time_info, status):
        with self._capture_lock:
            if self.is_recording and not self.limit_reached.is_set():
                if status:
                    self.capture_errors.append(str(status))
                self.audio_queue.put(indata.copy().reshape(-1))

    def _ingest(self):
        try:
            while True:
                chunk = self.audio_queue.get()
                if chunk is None:
                    break
                if self.limit_reached.is_set():
                    continue
                if chunk.dtype != np.int16:
                    chunk = (np.clip(chunk, -1, 32767 / 32768) * 32768).astype(np.int16)
                accepted = self.audio.append(chunk.astype('<i2', copy=False).tobytes())
                if accepted < len(chunk) or self.audio.position().samples >= MAX_SAMPLES:
                    self.limit_reached.set()
                    break  # Mobile stops capture and finalizes when it reaches five minutes.
        except Exception as error:
            self.ingest_error = error
        finally:
            self.audio.finish()

    def _publish(self, progress):
        previous = self.progress
        self.progress = progress
        if progress['failures'] != previous.get('failures', 0):
            print("  [ASR] Request failed; audio retained for retry.")
        if progress['completed'] != previous.get('completed', 0):
            print(f"  [ASR] {progress['completed']} responses | {progress['sections']} sections | "
                  f"{progress['start'] / SAMPLE_RATE:.1f}s processed")

    def _run_engine(self):
        async def run():
            self.engine_loop = asyncio.get_running_loop()
            self.engine_task = asyncio.current_task()
            if self.cancel_requested.is_set():
                raise asyncio.CancelledError()
            async with mobile_client() as client:
                async def timed_transcribe(pcm):
                    self.asr_request_number += 1
                    number = self.asr_request_number
                    started = time.monotonic()
                    outcome = 'failed or cancelled'
                    try:
                        result = await transcribe(client, self.asr_url, pcm)
                        outcome = 'completed'
                        return result
                    finally:
                        print(f"  [Timing] ASR request {number} {outcome}: "
                              f"{(time.monotonic() - started) * 1000:.0f}ms "
                              f"({len(pcm) / (2 * SAMPLE_RATE):.2f}s audio)")
                self.engine = StreamingTranscriber(self.audio,
                    timed_transcribe, on_progress=self._publish)
                return await self.engine.run()
        try:
            self.result = asyncio.run(run())
        except asyncio.CancelledError:
            self.engine_error = RuntimeError("Dictation cancelled")
        except Exception as error:
            self.engine_error = error

    def start_recording(self, mode):
        with self._lifecycle_lock:
            with self._capture_lock:
                if self.is_recording:
                    return
                if self._needs_recovery and len(self.audio_buffer):
                    if self.save_recovery("Previous recording still needs recovery") is None:
                        raise RuntimeError("Cannot discard an unsaved recording")
                self.audio_buffer = np.zeros(0, dtype=np.float32)
                self.committed_words.clear()
                self.preview_words.clear()
                self.capture_errors.clear()
                self.audio_queue = queue.Queue()
                self.limit_reached.clear()
                self.result = self.engine_error = self.ingest_error = None
                self.engine = None
                self.engine_loop = self.engine_task = None
                self.cancel_requested.clear()
                self.progress = {}
                self.asr_request_number = 0
                self.recording_mode = mode
                self.target_window = win.user32.GetForegroundWindow()
                self.audio = StreamingAudio()
                self._needs_recovery = True
                self.is_recording = True
                self.recording_started = time.monotonic()
            self.ingest_thread = threading.Thread(target=self._ingest, daemon=True)
            self.engine_thread = threading.Thread(target=self._run_engine, daemon=True)
            self.ingest_thread.start()
            self.engine_thread.start()
            self.beep_start()
            print(f"\n  >>> RECORDING STARTED: [{mode}] (mobile flow, five-minute limit)")

    def _save_session(self, reason):
        if self.audio is not None:
            # Keep all accepted PCM available for the same recovery path as v1.
            self.audio_buffer = np.frombuffer(bytes(self.audio.data), dtype='<i2').astype(np.float32) / 32768.0
        if self.engine is not None:
            self.committed_words = list(self.engine.sections)
            self.preview_words = [self.engine.pending_prefix, self.engine.agreement.stable, self.engine.agreement.tentative]
        return self.save_recovery(reason)

    def stop_recording(self):
        with self._lifecycle_lock:
            with self._capture_lock:
                if not self.is_recording:
                    return
                self.is_recording = False
                self.audio_queue.put(None)
            self.beep_stop()
            started = time.monotonic()
            held_seconds = started - self.recording_started
            print("\n  [Recording stopped] Finalizing mobile-style transcription...")
            step_started = time.monotonic()
            self.ingest_thread.join()
            print(f"  [Timing] Audio flush: {(time.monotonic() - step_started) * 1000:.0f}ms; "
                  f"{self.audio.position().samples / SAMPLE_RATE:.2f}s captured "
                  f"(hotkey held {held_seconds:.2f}s)")
            step_started = time.monotonic()
            self.engine_thread.join()
            print(f"  [Timing] ASR finalization wait: {(time.monotonic() - step_started) * 1000:.0f}ms "
                  "(background requests run during recording)")
            if self.capture_errors or self.ingest_error or self.engine_error:
                error = self.ingest_error or self.engine_error or '; '.join(self.capture_errors)
                print(f"  [Dictation error] {error}")
                self._save_session(str(error))
                return
            raw = self.result or ''
            if not raw:
                self._save_session("No speech was recognized")
                return
            print(f"  RAW ASR: {raw!r}")
            final = raw
            if self.recording_mode == 'CLEANUP':
                async def clean():
                    async with mobile_client() as client:
                        return await polish(client, self.cleanup_url, self.cleanup_model, win.CLEANUP_SYSTEM_PROMPT, raw)
                step_started = time.monotonic()
                try:
                    try:
                        final = asyncio.run(clean())
                    finally:
                        print(f"  [Timing] Cleanup: {(time.monotonic() - step_started) * 1000:.0f}ms")
                except Exception as error:
                    self.committed_words = [raw]
                    print(f"  [Cleanup error] {error}; original retained for recovery.")
                    self._save_session(f"Cleanup failed: {error}; original transcript: {raw}")
                    return
            self.committed_words = [final]
            if win.user32.GetForegroundWindow() != self.target_window:
                self._save_session("Focused window changed before insertion; transcript: " + final)
                return
            step_started = time.monotonic()
            try:
                try:
                    inserted = win.output_text(final, method=self.paste_method)
                finally:
                    print(f"  [Timing] Text insertion: {(time.monotonic() - step_started) * 1000:.0f}ms")
                if inserted is False:
                    self._save_session("Text insertion failed; transcript: " + final)
                    return
            except Exception as error:
                self._save_session(f"Text insertion failed: {error}; transcript: {final}")
                return
            self._needs_recovery = False
            print(f"  DONE! Stop to output: {(time.monotonic() - started) * 1000:.0f}ms\n")

    def close(self):
        with self._lifecycle_lock:
            if self._cue_player:
                self._cue_player.close()
                self._cue_player = None
            with self._capture_lock:
                interrupted = self.is_recording
                self.is_recording = False
                if interrupted:
                    self.audio_queue.put(None)
            if self.audio is not None and self._needs_recovery:
                self.ingest_thread.join()
                self.cancel_requested.set()
                if self.engine_loop is not None and self.engine_task is not None and self.engine_thread.is_alive():
                    try:
                        self.engine_loop.call_soon_threadsafe(self.engine_task.cancel)
                    except RuntimeError:
                        pass
                self.engine_thread.join()
                self._save_session("Dictation was interrupted before finalization")
            elif self._needs_recovery and len(self.audio_buffer):
                self.save_recovery("Dictation was interrupted before finalization")

    def run(self):
        print("=" * 65)
        print("  PC DICTATION V2 - MOBILE STREAMING FLOW")
        print(f"  ASR: {self.asr_url} | Cleanup: {self.cleanup_url}")
        print(f"  Hold Ctrl + Windows to dictate ({'cleanup' if self.default_cleanup else 'raw'}).")
        print("  Add Shift for cleanup; use --cleanup to make it the default.")
        print("  Release to output. Press Ctrl+C here to exit.")
        print("  Exit any other PC dictation instance to avoid duplicate hotkeys.")
        print("=" * 65)
        with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype='int16',
                            blocksize=1600, callback=self.audio_callback):
            was_active = False
            limit_warned = False
            while True:
                time.sleep(.015)
                ctrl = win.is_pressed(win.VK_CONTROL)
                windows = win.is_pressed(win.VK_LWIN) or win.is_pressed(win.VK_RWIN)
                shift = win.is_pressed(win.VK_SHIFT)
                if ctrl and windows:
                    if not was_active:
                        win.mask_start_menu()
                        self.start_recording('CLEANUP' if self.default_cleanup or shift else 'RAW')
                        was_active = True
                        limit_warned = False
                    elif shift and self.recording_mode == 'RAW':
                        self.recording_mode = 'CLEANUP'
                        print("  [Mode upgraded to cleanup]")
                    if self.limit_reached.is_set() and not limit_warned:
                        print("  [Five-minute limit reached] Release the hotkey to finalize.")
                        limit_warned = True
                elif was_active:
                    self.stop_recording()
                    was_active = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asr-url', default='http://127.0.0.1:8000')
    parser.add_argument('--cleanup-url', default='http://127.0.0.1:8080')
    parser.add_argument('--paste-method', choices=['clipboard', 'sendinput'], default='clipboard')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--raw', dest='cleanup', action='store_false', help='Default to raw transcription (default)')
    mode.add_argument('--cleanup', action='store_true', help='Enable cleanup even without Shift')
    parser.set_defaults(cleanup=False)
    sound = parser.add_mutually_exclusive_group()
    sound.add_argument('--sound', action='store_true', help='Enable start/stop chimes (default)')
    sound.add_argument('--no-sound', dest='sound', action='store_false', help='Disable start/stop chimes')
    parser.set_defaults(sound=True)
    args = parser.parse_args()
    win.run_single_instance(PcDictationV2, asr_url=args.asr_url, cleanup_url=args.cleanup_url,
                           paste_method=args.paste_method, play_sound=args.sound,
                           default_cleanup=args.cleanup)


if __name__ == '__main__':
    main()
