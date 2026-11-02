"""Replay WAVs through the real PC client without microphone, hotkeys, or pasting.

Use --client-module to compare a saved baseline with the current implementation.
"""
import argparse
import base64
import contextlib
import importlib.util
import io
import json
import math
import re
import time
import threading
from pathlib import Path
from unittest.mock import patch

import numpy as np
import requests
import soundfile as sf
from scipy.signal import resample_poly

import pc_dictate


def edit_distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, a in enumerate(reference, 1):
        current = [i]
        for j, b in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (a != b)))
        previous = current
    return previous[-1]


def tokens(text):
    return re.findall(r"\w+", text.casefold())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path)
    parser.add_argument("--asr-url", default="http://127.0.0.1:8000")
    parser.add_argument("--client-module", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--chunk-sec", type=float, default=.1)
    parser.add_argument("--window-sec", type=float, default=5)
    parser.add_argument("--overlap-sec", type=float, default=1.5)
    parser.add_argument("--realtime", action="store_true")
    parser.add_argument("--worker", action="store_true", help="Feed the real callback and background worker")
    parser.add_argument("--request-delay", type=float, default=0, help="Inject latency to test backlog handling")
    parser.add_argument("--reference", help="Known transcript; otherwise compare with whole-file ASR")
    args = parser.parse_args()
    if args.chunk_sec <= 0:
        parser.error("chunk-sec must be positive")
    if args.request_delay < 0:
        parser.error("request-delay must be nonnegative")
    if args.worker and args.client_module:
        parser.error("worker replay requires the current client")
    module = pc_dictate
    if args.client_module:
        spec = importlib.util.spec_from_file_location("baseline_client", args.client_module)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    audio, rate = sf.read(args.audio, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    divisor = math.gcd(rate, 16000)
    audio = resample_poly(audio, 16000 // divisor, rate // divisor).astype(np.float32)
    reference = args.reference
    if reference is None:
        with args.audio.open("rb") as f:
            response = requests.post(f"{args.asr_url}/v1/audio/transcriptions",
                                     files={"file": f}, data={"model": "Qwen/Qwen3-ASR-1.7B"}, timeout=60)
        response.raise_for_status()
        reference = response.json()["text"]
    client = module.PcDictationClient(asr_url=args.asr_url, play_sound=False,
                                     window_size_sec=args.window_sec, overlap_sec=args.overlap_sec)
    emitted = []
    durations = []
    request_lengths = []
    real_post = requests.post

    def post(url, **kwargs):
        if url.endswith("/v1/streaming/window"):
            samples, sr = sf.read(io.BytesIO(base64.b64decode(kwargs["json"]["audio_base64"])))
            request_lengths.append(len(samples) / sr)
        started = time.monotonic()
        if args.request_delay:
            time.sleep(args.request_delay)
        result = real_post(url, **kwargs)
        durations.append(time.monotonic() - started)
        return result

    recoveries = []
    original_recovery = getattr(client, 'save_recovery', None)
    def save_recovery(reason):
        recoveries.append(reason)
        return original_recovery(reason)

    with contextlib.ExitStack() as stack:
        # This is a headless audio replay; real desktop focus is unrelated to
        # the fixture and may change while the user works elsewhere.
        stack.enter_context(patch.object(module.user32, 'GetForegroundWindow', return_value=1))
        if original_recovery:
            stack.enter_context(patch.object(client, 'save_recovery', side_effect=save_recovery))
        run_replay(module, client, audio, args, emitted, post)
        stable_before_stop, stop_delay = client._replay_metrics
    text = emitted[-1] if emitted else ""
    ref_words = tokens(reference)
    distance = edit_distance(ref_words, tokens(text))
    report = {"audio": str(args.audio), "duration_sec": len(audio) / 16000,
              "reference": reference, "reference_kind": 'provided' if args.reference is not None else 'whole_file_asr',
              "text": text, "word_errors": distance, "wer": distance / max(1, len(ref_words)),
              "requests": len(durations), "max_request_sec": max(request_lengths, default=0),
              "request_latency_sec": durations, "stable_words_before_stop": stable_before_stop,
              "stop_delay_sec": stop_delay, "recoveries": recoveries,
              "worker": args.worker, "window_sec": args.window_sec, "overlap_sec": args.overlap_sec}
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if args.report:
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    if recoveries:
        raise SystemExit(1)


def run_replay(module, client, audio, args, emitted, post):
    started = time.monotonic()
    with patch.object(module, "output_text", side_effect=lambda text, **kw: emitted.append(text)), \
         patch.object(module.requests, "post", side_effect=post):
        client.start_recording("RAW")
        worker = None
        if args.worker:
            worker = threading.Thread(target=client.audio_worker, daemon=True)
            worker.start()
        chunk = max(1, int(args.chunk_sec * 16000))
        for offset in range(0, len(audio), chunk):
            piece = audio[offset:offset + chunk]
            if args.worker:
                client.audio_callback(piece[:, None], len(piece), None, None)
            else:
                client.audio_buffer = np.concatenate([client.audio_buffer, piece])
                client.process_background_window()
            if args.realtime:
                time.sleep(max(0, (offset + chunk) / 16000 - (time.monotonic() - started)))
        stable_before_stop = len(client.committed_words)
        stopped = time.monotonic()
        client.stop_recording()
        stop_delay = time.monotonic() - stopped
        if worker:
            client.close()
            worker.join(2)
    client._replay_metrics = stable_before_stop, stop_delay


if __name__ == "__main__":
    main()
