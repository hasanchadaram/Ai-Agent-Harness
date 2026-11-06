"""Compare opt-in growing-window profiles against the existing flow; no mic or insertion.

Input paths are explicit. Reports can contain their transcripts; keep them local.
Backlog replay measures processing cost, not real-time dictation latency.
"""
import argparse
import asyncio
import json
import math
import sys
import time
from dataclasses import asdict
from pathlib import Path

import soundfile as sf
import numpy as np
from scipy.signal import resample_poly

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from growing_window_transcriber import GrowingConfig, GrowingWindowTranscriber, transcribe_aligned
from mobile_streaming import StreamingAudio, StreamingTranscriber, mobile_client, transcribe


async def main(args):
    profiles = {
        'responsive': GrowingConfig(initial=2, growth=1, soft_limit=6, context=.5),
        'balanced': GrowingConfig(initial=3, growth=1.5, soft_limit=8, context=.5),
        'efficient': GrowingConfig(initial=4, growth=2, soft_limit=10, context=.5),
        'contextual': GrowingConfig(initial=4, growth=2, soft_limit=10, context=1.5),
        'long_context': GrowingConfig(initial=4, growth=2, soft_limit=12, context=1.5),
    }
    reports = []
    async with mobile_client() as client:
        for path in args.audio:
            wav, rate = sf.read(path, dtype='float32', always_2d=True)
            divisor = math.gcd(rate, 16000)
            wav = resample_poly(wav.mean(axis=1), 16000 // divisor, rate // divisor)
            pcm = (np.clip(wav, -1, 32767 / 32768) * 32768).astype('<i2')
            rate = 16000
            for name in args.profiles:
                audio = StreamingAudio()
                audio.append(pcm.astype('<i2').tobytes()); audio.finish()
                calls, progress = [], {}
                async def recognize(data, timestamps):
                    began = time.monotonic()
                    result = await transcribe_aligned(client, args.asr_url, data, timestamps)
                    calls.append({'seconds': len(data) / 32000, 'elapsed': time.monotonic() - began,
                                  'timestamps': timestamps, 'word_count': len(result.words),
                                  'zero_times': sum(w.start == w.end for w in result.words)})
                    return result
                async def plain(data):
                    began = time.monotonic()
                    result = await transcribe(client, args.asr_url, data)
                    calls.append({'seconds': len(data) / 32000, 'elapsed': time.monotonic() - began,
                                  'timestamps': False})
                    return result
                def update(state): progress.update(state)
                worker = (StreamingTranscriber(audio, plain, on_progress=update) if name == 'overlap' else
                          GrowingWindowTranscriber(audio, recognize, config=profiles[name], on_progress=update,
                                                   optimize_finish=False))
                began = time.monotonic()
                error, text = '', ''
                try: text = await worker.run()
                except Exception as exc: error = str(exc)
                report = {'audio': str(path), 'profile': name, 'duration': len(pcm) / rate,
                          'config': asdict(profiles[name]) if name in profiles else {},
                          'elapsed': time.monotonic() - began, 'calls': calls, 'progress': progress,
                          'text': text, 'error': error}
                reports.append(report)
                args.report.write_text(json.dumps(reports, indent=2), encoding='utf-8')
                print(f'{path.name} {name}: {len(calls)} calls, {report["elapsed"]:.2f}s processing, '
                      f'{sum(c["seconds"] for c in calls):.2f}s submitted, '
                      f'{progress.get("rolls")} rolls, recovery={progress.get("recovered")}, error={error}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', type=Path, nargs='+')
    parser.add_argument('--asr-url', default='http://127.0.0.1:8000')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--profiles', nargs='+', choices=['overlap', 'responsive', 'balanced', 'efficient', 'contextual', 'long_context'],
                        default=['overlap', 'responsive', 'balanced', 'efficient'])
    asyncio.run(main(parser.parse_args()))
