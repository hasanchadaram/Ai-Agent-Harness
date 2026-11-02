"""Compare pause-sized clips with continuous Qwen context on synthetic WAVs.

Uses real Silero and Qwen, with English fixed for both strategies to isolate
segmentation. The default queues audio to test backlog; it never opens a mic,
inserts text, or calls the cleanup model. Generate inputs with the companion
create_qwen_context_fixture.ps1 script.
"""
import argparse
import asyncio
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import soundfile as sf
from mobile_streaming import StreamingAudio, StreamingTranscriber, mobile_client, transcribe

EXPECTED = 'Because right now it is giving me some Hindi words in the transcript output sometimes.'


async def replay(path, url):
    pcm, rate = sf.read(path, dtype='int16')
    if rate != 16000 or pcm.ndim != 1:
        raise ValueError('Use 16 kHz mono synthetic speech.')
    audio = StreamingAudio()
    audio.append(pcm.astype('<i2').tobytes())
    audio.finish()
    async with mobile_client() as client:
        clips, start = [], 0
        for end in [*audio.pauses, audio.samples]:
            if audio.has_speech(start, end):
                text = await transcribe(client, url, audio.snapshot(start, end))
                clips.append({'start_sec': start / rate, 'end_sec': end / rate, 'text': text})
            start = end
        states, requests = [], []
        async def decode(data):
            text = await transcribe(client, url, data)
            requests.append({'audio_sec': len(data) / (2 * rate), 'text': text})
            return text
        started = time.monotonic()
        text = await StreamingTranscriber(audio, decode, on_progress=states.append).run()
        seconds = time.monotonic() - started
    words = lambda value: re.findall(r'\w+', value.casefold())
    return {'audio': str(path), 'duration_sec': len(pcm) / rate,
            'detected_pauses': len(audio.pauses), 'pause_clips': clips,
            'pause_clips_text': ' '.join(clip['text'] for clip in clips),
            'context_text': text, 'context_requests': requests,
            'exact_words': words(text) == words(EXPECTED),
            'sentence_endings': len(re.findall(r'[.!?]', text)),
            'context_processing_sec': seconds, 'progress': states[-1]}


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', nargs='+', type=Path)
    parser.add_argument('--asr-url', default='http://127.0.0.1:8000')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    results = []
    for path in args.audio:
        result = await replay(path, args.asr_url)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.report:
        args.report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    if any(not item['exact_words'] or item['sentence_endings'] > 1 or
           item['progress']['failures'] for item in results):
        raise SystemExit('The continuous-context replay did not match the expected sentence.')


if __name__ == '__main__':
    asyncio.run(main())
