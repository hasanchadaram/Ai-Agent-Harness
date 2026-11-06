"""Replay a WAV through the PC v2 callback and mobile streaming worker, without typing."""
import argparse
import json
import math
import time
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

import pc_dictate_v2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', type=Path)
    parser.add_argument('--asr-url', default='http://127.0.0.1:8000')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--fast', action='store_true', help='Queue all audio immediately to test backlog')
    parser.add_argument('--streaming-strategy', choices=['overlap', 'growing'], default='overlap')
    parser.add_argument('--baseline-finish', action='store_true', help='Compare against the original growing-window Stop path')
    parser.add_argument('--cleanup', action='store_true', help='Also test the mobile cleanup request')
    args = parser.parse_args()
    wav, rate = sf.read(args.audio, dtype='float32', always_2d=True)
    audio = wav.mean(axis=1)
    gcd = math.gcd(rate, 16000)
    audio = resample_poly(audio, 16000 // gcd, rate // gcd)
    audio = (np.clip(audio, -1, 32767 / 32768) * 32768).astype(np.int16)
    client = pc_dictate_v2.PcDictationV2(asr_url=args.asr_url, play_sound=False,
                                      streaming_strategy=args.streaming_strategy, growing_fast_finish=not args.baseline_finish)
    inserted = []
    with patch.object(pc_dictate_v2.win, 'output_text', side_effect=lambda text, **kw: inserted.append(text) or True), \
         patch.object(pc_dictate_v2.win.user32, 'GetForegroundWindow', return_value=1):
        client.start_recording('CLEANUP' if args.cleanup else 'RAW')
        began = time.monotonic()
        for start in range(0, len(audio), 1600):
            chunk = audio[start:start + 1600]
            client.audio_callback(chunk[:, None], len(chunk), None, None)
            if not args.fast:
                time.sleep(max(0, min(start + 1600, len(audio)) / 16000 - (time.monotonic() - began)))
        stable_before_stop = client.progress.get('stable', '')
        stopped = time.monotonic()
        client.stop_recording()
        stop_delay = time.monotonic() - stopped
        report = {'audio': str(args.audio), 'duration_sec': len(audio) / 16000,
                  'strategy': args.streaming_strategy, 'insertions': len(inserted),
                  'optimized_finish': not args.baseline_finish,
                  'text': inserted[0] if inserted else '', 'stable_before_stop': stable_before_stop,
                  'stop_delay_sec': stop_delay, 'progress': client.progress,
                  'error': str(client.engine_error or client.ingest_error or '')}
        print(json.dumps(report, indent=2, ensure_ascii=False))
        if args.report:
            args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
        if client.engine_error or client.ingest_error or not inserted:
            raise SystemExit(1)
        client.close()


if __name__ == '__main__':
    main()
