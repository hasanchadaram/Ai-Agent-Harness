"""Compare the Kotlin test's emitted request ranges and output with the Python port."""
import asyncio
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from mobile_streaming import StreamingAudio, StreamingTranscriber


class Speech:
    frame_size = 512
    def __init__(self, pauses=False): self.pauses = pauses
    def probability(self, frame): return 0.0 if self.pauses and frame[0] == 0 else 1.0


async def main():
    report = (Path(__file__).resolve().parents[1] / 'android-app/app/build/test-results/testDebugUnitTest/'
              'TEST-com.companion.ainotifications.dictation.MobilePcParityTest.xml')
    output = ET.parse(report).getroot().findtext('system-out') or ''
    for pauses, name, gap in [(False, 'MOBILE_PARITY', 0), (True, 'MOBILE_PARITY_PAUSES', 6400),
                              (True, 'MOBILE_PARITY_LONG_PAUSES', 320000)]:
        reference = json.loads(re.search(name + r'=(\{.*\})', output).group(1))
        audio = StreamingAudio(Speech(pauses))
        for number in range(1, 31 if pauses else 81):
            audio.append(np.full(4000, number, dtype='<i2').tobytes())
            if pauses and number in (10, 20):
                audio.append(np.zeros(gap, dtype='<i2').tobytes())
        audio.finish()
        ranges = []
        async def decode(data):
            values = np.frombuffer(data, dtype='<i2')
            raw = np.r_[values[0], values[1:][values[1:] != values[:-1]]].tolist()
            ids = [item for index, item in enumerate(raw) if item != 0 and
                   (not index or item != raw[index - 1])]
            ranges.append([int(values[0]), len(values)])
            return ' '.join(f'word{number}' for number in ids)
        actual = {'text': await StreamingTranscriber(audio, decode, interval=.001).run(), 'ranges': ranges}
        if actual != reference:
            raise AssertionError(f"Python/mobile mismatch: {actual!r} != {reference!r}")
        print(f"PASS: {name} matched Kotlin text and request ranges: {ranges}")


if __name__ == '__main__':
    asyncio.run(main())
