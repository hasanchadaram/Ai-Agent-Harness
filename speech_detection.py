"""CPU Silero VAD using the same pinned ONNX model as the Android client."""
from functools import lru_cache
from pathlib import Path
from types import SimpleNamespace
import math

import numpy as np
from scipy.signal import resample_poly


DEFAULT_MODEL = (Path(__file__).parent / "android-app/app/src/main/assets/vad/silero_vad_16k_op15.onnx")


@lru_cache(maxsize=1)
def vad_session():
    import onnxruntime as ort
    if not DEFAULT_MODEL.is_file():
        raise RuntimeError(f"Missing Silero model: {DEFAULT_MODEL}")
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    return ort.InferenceSession(str(DEFAULT_MODEL), options, providers=["CPUExecutionProvider"])


def speech_bounds(wav, sample_rate):
    """Return conservative bounds in original samples, or None for no speech.

    State and lookback are local to this call; simultaneous clients cannot share
    detector history. Quiet speech is amplified for detection only.
    """
    if not len(wav) or not np.any(wav):
        return None
    divisor = math.gcd(sample_rate, 16000)
    audio = resample_poly(wav, 16000 // divisor, sample_rate // divisor).astype(np.float32)
    peak = float(np.max(np.abs(audio)))
    audio = audio * min(20., .5 / max(peak, 1e-9))
    session = vad_session()
    state = np.zeros((2, 1, 128), dtype=np.float32)
    context = np.zeros(64, dtype=np.float32)
    detected = []
    for offset in range(0, len(audio), 512):
        frame = audio[offset:offset + 512]
        frame = np.pad(frame, (0, 512 - len(frame)))
        probability, state = session.run(None, {
            "input": np.r_[context, frame][None, :], "state": state,
            "sr": np.array(16000, dtype=np.int64),
        })
        context = frame[-64:]
        if float(probability.item()) >= .35:
            detected.append(offset)
    if not detected:
        return None
    # Keep 250 ms on either side; VAD onsets and word boundaries are estimates.
    first = max(0, round((detected[0] / 16000 - .25) * sample_rate))
    last = min(len(wav), round(((detected[-1] + 512) / 16000 + .25) * sample_rate))
    return first, last


def transcribe_speech(wav, sample_rate, generate, language=None, return_time_stamps=True):
    """Run a model callback on speech, restoring times to the input clock."""
    bounds = speech_bounds(wav, sample_rate)
    if bounds is None:
        return [SimpleNamespace(text="", language="", time_stamps=[])]
    first, last = bounds
    model_audio = wav[first:last]
    model_audio = np.pad(model_audio, (0, max(0, int(.3 * sample_rate) - len(model_audio))))
    results = generate(audio=[(model_audio, sample_rate)], language=language,
                       return_time_stamps=return_time_stamps)
    return [SimpleNamespace(text=result.text, language=getattr(result, 'language', ''),
                            time_stamps=[SimpleNamespace(text=w.text,
                                start_time=w.start_time + first / sample_rate,
                                end_time=w.end_time + first / sample_rate)
                                for w in (result.time_stamps or [])]) for result in results]
