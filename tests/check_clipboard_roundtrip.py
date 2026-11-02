"""Check complete Unicode clipboard text and restoration, without typing.

Temporarily writes synthetic text to the local clipboard, then restores the
original restorable formats in memory. Does not open a microphone, insert into
an editor, or save clipboard content. A newer user copy is never overwritten
by restoration.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pc_dictate as pc


def main():
    original = pc.snapshot_clipboard()
    if original is None:
        raise SystemExit('Clipboard cannot be preserved safely; no test data was written.')
    probe = ('Because right now, it is giving me some Hindi words in the transcript output sometimes.\n'
             'Why is it not working properly?\nKeep ₹50, iPhone and NASA.')
    sequence = None
    restored = False
    try:
        if not pc.set_clipboard_text(probe):
            raise RuntimeError('Unable to write synthetic clipboard probe.')
        sequence = pc.user32.GetClipboardSequenceNumber()
        if pc.get_clipboard_text() != probe:
            raise AssertionError('Clipboard probe lost or changed characters.')
        print('PASS: complete Unicode sentences and negation are intact in the real clipboard.')
    finally:
        if sequence is not None:
            unchanged = pc.user32.GetClipboardSequenceNumber() == sequence
            restored = pc.restore_clipboard(original, sequence)
            if not restored:
                raise RuntimeError('Original clipboard restoration failed.')
            if unchanged and pc.snapshot_clipboard() != original:
                raise AssertionError('Restorable clipboard content changed after restoration.')
            print('PASS: original clipboard content restored, or a newer user copy preserved.')
    return restored


if __name__ == '__main__':
    main()
