"""Compare actual Kotlin/Python growing-window requests and text, including timestamp cuts."""
import asyncio
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_growing_windows import GrowingTests, EXAMPLE_CONFIG, example_answers
from growing_window_transcriber import GrowingWindowTranscriber


async def main():
    root = Path(__file__).resolve().parents[1]
    report = root / 'android-app/app/build/test-results/testDebugUnitTest/TEST-com.companion.ainotifications.dictation.GrowingWindowTranscriberTest.xml'
    output = ET.parse(report).getroot().findtext('system-out') or ''
    reference = json.loads(re.search(r'GROWING_PARITY=(\{.*\})', output).group(1))
    audio, _ = GrowingTests().audio(6)
    answers, ranges = iter(example_answers()), []
    async def recognize(data, timestamps):
        ranges.append([int.from_bytes(data[:2], 'little', signed=True), len(data) // 2])
        return next(answers)
    actual = {'text': await GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG,
                                                   optimize_finish=False).run(), 'ranges': ranges}
    if actual != reference: raise AssertionError(f'{actual!r} != {reference!r}')
    print('PASS: Growing-window Kotlin/Python text and all timestamp-based request ranges match:', ranges)


if __name__ == '__main__': asyncio.run(main())
