"""Request-level local Qwen timing, with and without alignment; no mic or insertion."""
import argparse
import asyncio
import base64
import io
import json
import sys
import time
from pathlib import Path

import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mobile_streaming import mobile_client, request_model, completion_url, TRANSCRIBE_MODEL


async def main(args):
    audio, rate = sf.read(args.audio, dtype='int16')
    if rate != 16000 or audio.ndim != 1:
        raise ValueError('Use a mono 16 kHz source')
    rows = []
    async with mobile_client() as client:
        for repeat in range(args.repeats):
            for seconds in args.seconds:
                wav = io.BytesIO()
                selected = audio[:round(seconds * rate)]
                sf.write(wav, selected, rate, format='WAV', subtype='PCM_16')
                for timestamps in ([False, True] if repeat % 2 == 0 else [True, False]):
                    body = {'model': TRANSCRIBE_MODEL, 'language': 'English', 'temperature': 0,
                        'max_tokens': 4096, 'stream': False, 'return_time_stamps': timestamps,
                        'messages': [{'role': 'user', 'content': [{'type': 'input_audio',
                            'input_audio': {'data': base64.b64encode(wav.getvalue()).decode(), 'format': 'wav'}}]}]}
                    began = time.perf_counter()
                    response = await request_model(client, completion_url(args.asr_url), body, structured=True)
                    row = {'repeat': repeat, 'seconds': len(selected) / rate, 'timestamps': timestamps,
                        'client_ms': (time.perf_counter() - began) * 1000,
                        'server_ms': response.get('latency_sec', 0) * 1000,
                        'text': response['choices'][0]['message']['content']}
                    rows.append(row)
                    args.report.write_text(json.dumps(rows, indent=2), encoding='utf-8')
                    print(f'{row["seconds"]:.2f}s alignment={timestamps}: client={row["client_ms"]:.0f}ms '
                          f'server={row["server_ms"]:.0f}ms', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', type=Path)
    parser.add_argument('--seconds', type=float, nargs='+', default=[4, 8, 12, 20, 50.3])
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--asr-url', default='http://127.0.0.1:8000')
    parser.add_argument('--report', type=Path, required=True)
    asyncio.run(main(parser.parse_args()))
