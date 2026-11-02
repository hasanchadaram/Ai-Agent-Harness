"""Shared, model-independent validation and window reconciliation.

Forced alignment estimates word locations; it does not certify recognition accuracy.
Keep acoustic context on both sides of a boundary and never deduplicate by spelling.
"""
import math
import re
import unicodedata


class AlignmentError(ValueError):
    pass


def clean_word(text):
    return "".join(c for c in unicodedata.normalize("NFKC", text).casefold()
                   if unicodedata.category(c)[0] in "LNM")


def deduplicate_timed_words(previous, incoming):
    """Match re-observed context by text AND overlapping acoustic location.

    A later occurrence of the same word has its own interval and is retained.
    This handles quantization drift without a global spelling-only suffix rule.
    """
    result = []
    for word in incoming:
        duplicate = False
        for old in previous[-12:]:
            if clean_word(old["word"]) != clean_word(word["word"]):
                continue
            overlap = min(old["end"], word["end"]) - max(old["start"], word["start"])
            shorter = min(old["end"] - old["start"], word["end"] - word["start"])
            center_delta = abs((old["start"] + old["end"] - word["start"] - word["end"]) / 2)
            if overlap > 0 and (overlap >= shorter * .5 or center_delta < .08):
                duplicate = True
                break
        if not duplicate:
            result.append(word)
    return result


def attach_punctuation(full_text, items):
    """Restore original tokens, including Unicode, split contractions and symbols.

    Use complete character coverage rather than a four-token lookahead. Never
    silently drop text that the aligner failed to cover. Mutates the list for
    compatibility with the chat/audio endpoints.
    """
    if not full_text.strip():
        if any(clean_word(item["word"]) for item in items):
            raise AlignmentError("Alignment contains words for an empty transcript")
        items[:] = []
        return items
    tokens = list(re.finditer(r"\S+", full_text))
    text_chars = "".join(clean_word(token.group()) for token in tokens)
    aligned_chars = "".join(clean_word(item["word"]) for item in items)
    if not text_chars or text_chars != aligned_chars:
        raise AlignmentError("Aligned words do not cover the complete transcript")

    # Each original whitespace token is timed by all alignment items it overlaps.
    # Merged alignment items become a phrase; split items become one original word.
    spans = []
    offset = 0
    for item in items:
        length = len(clean_word(item["word"]))
        if length:
            spans.append((offset, offset + length, item))
        offset += length
    restored = []
    offset = 0
    for token in tokens:
        length = len(clean_word(token.group()))
        if not length:
            if restored:
                restored[-1]["word"] += " " + token.group()
            continue
        covering = [span for span in spans if span[0] < offset + length and span[1] > offset]
        group = {"word": token.group(), "start": covering[0][2]["start"],
                 "end": covering[-1][2]["end"]}
        # If one alignment item covers multiple original tokens, keep them in
        # one group so their shared timestamps cannot discard the latter token.
        if restored and covering[0][0] < offset:
            restored[-1]["word"] += " " + group["word"]
            restored[-1]["end"] = group["end"]
        else:
            restored.append(group)
        offset += length
    items[:] = restored
    return items


def partition_window(full_text, items, window_start, duration, overlap,
                     last_committed=0.0, is_final=False, context_sec=.5):
    """Validate local timestamps and return absolute committed/preview words."""
    values = (window_start, duration, overlap, last_committed)
    if not all(math.isfinite(v) and v >= 0 for v in values) or duration <= 0:
        raise AlignmentError("Invalid window times")
    window_end = window_start + duration
    if last_committed > window_end + .02:
        raise AlignmentError("Commit time is beyond this window")
    previous_start = previous_end = 0.0
    local = []
    for item in items:
        start, end = float(item["start"]), float(item["end"])
        if (not math.isfinite(start) or not math.isfinite(end)
                or start < 0 or end < start or end > duration + .5
                or start < previous_start or end < previous_end):
            raise AlignmentError("Invalid or non-monotonic word timestamps")
        previous_start, previous_end = start, end
        local.append({"word": item["word"], "start": min(start, duration), "end": min(end, duration)})
    attach_punctuation(full_text, local)
    cutoff = window_end if is_final else window_end - overlap
    committed, preview = [], []
    frontier = last_committed
    for word in local:
        start, end = window_start + word["start"], window_start + word["end"]
        # Compare the word center with the fixed prior frontier, not a moving
        # frontier: aligners can legitimately give adjacent tokens equal times.
        if last_committed > 0 and (start + end) / 2 <= last_committed:
            continue
        absolute = {"word": word["word"], "start": round(start, 6), "end": round(end, 6)}
        if is_final or end <= cutoff:
            committed.append(absolute)
            frontier = max(frontier, end)
        else:
            preview.append(absolute)

    if is_final:
        next_start = window_end
    elif committed:
        next_pending = preview[0]["start"] - context_sec if preview else cutoff
        next_start = max(window_start, frontier - context_sec, min(cutoff, next_pending))
    elif preview:
        next_start = max(window_start, min(cutoff, preview[0]["start"] - context_sec))
    else:
        next_start = max(window_start, cutoff)
    if not is_final and next_start <= window_start + 1e-6:
        raise AlignmentError("Cannot advance safely; retry with more context or recover the audio")
    return {"window_start_sec": window_start, "window_end_sec": window_end,
            "committed_words": committed, "preview_words": preview,
            "last_committed_time_sec": frontier, "next_start_sec": next_start,
            "full_text": full_text.strip()}
