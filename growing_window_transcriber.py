"""Opt-in growing-window agreement with timestamp-driven audio advancement.

The existing overlap worker is unchanged. Audio coordinates here refer to its
RecognitionAudio view; progress and full recovery use original PCM coordinates.
"""
import asyncio
import base64
import io
import math
from dataclasses import dataclass

import numpy as np
import soundfile as sf

from mobile_streaming import (SAMPLE_RATE, TRANSCRIBE_MODEL, StreamingTranscriber,
                              completion_url, request_model)


@dataclass(frozen=True)
class TimedWord:
    text: str
    start: float
    end: float


@dataclass(frozen=True)
class AlignedTranscript:
    text: str
    words: tuple = ()


def word_key(text):
    return ''.join(c for c in text.lower() if c.isalnum())


def validated_words(result, duration):
    """Missing, partial or inconsistent alignment cannot authorize an audio cut."""
    if not isinstance(result.text, str) or any(not isinstance(w.text, str) for w in result.words):
        return None
    if word_key(result.text) != ''.join(word_key(w.text) for w in result.words):
        return None
    previous_start = previous_end = 0.
    for word in result.words:
        if (not word_key(word.text) or not math.isfinite(word.start) or not math.isfinite(word.end)
                or word.start < previous_start or word.end < previous_end
                or word.end < word.start or word.end > duration + .05):
            return None
        previous_start, previous_end = word.start, word.end
    if result.words and not any(w.end > w.start for w in result.words):
        return None
    return list(result.words)


async def transcribe_aligned(client, asr_url, pcm, timestamps=True):
    bio = io.BytesIO()
    sf.write(bio, np.frombuffer(pcm, dtype='<i2'), SAMPLE_RATE, format='WAV', subtype='PCM_16')
    payload = {'model': TRANSCRIBE_MODEL, 'messages': [{'role': 'user', 'content': [
        {'type': 'input_audio', 'input_audio': {'data': base64.b64encode(bio.getvalue()).decode('ascii'),
                                               'format': 'wav'}}]}],
        'temperature': 0, 'max_tokens': 4096, 'stream': False, 'language': 'English',
        'return_time_stamps': timestamps}
    response = await request_model(client, completion_url(asr_url), payload, structured=True)
    text = response['choices'][0]['message']['content'].split('<asr_text>', 1)[-1].split('<|im_end|>', 1)[0].strip()
    try:
        words = tuple(TimedWord(w['word'], float(w['start']), float(w['end']))
                      for w in response.get('timestamps', [])) if timestamps else ()
    except (KeyError, TypeError, ValueError):
        words = ()  # Text remains usable for an authoritative full decode.
    return AlignedTranscript(text, words)


@dataclass(frozen=True)
class GrowingConfig:
    initial: float = 4.
    growth: float = 2.
    soft_limit: float = 12.
    hard_limit: float = 20.
    context: float = 1.5
    tail: float = .8
    hold_words: int = 2
    minimum_advance: float = 2.

    def __post_init__(self):
        values = (self.initial, self.growth, self.soft_limit, self.hard_limit,
                  self.context, self.tail, self.minimum_advance)
        if (not all(math.isfinite(v) and v > 0 for v in values)
                or not self.initial <= self.soft_limit <= self.hard_limit
                or self.hold_words < 1 or self.context >= self.minimum_advance):
            raise ValueError('Invalid growing-window configuration')


class GrowingWindowTranscriber(StreamingTranscriber):
    def __init__(self, audio, recognize_fn, *, config=GrowingConfig(), on_progress=None,
                 interval=.05, timeout=8., retry_delay=1.5, optimize_finish=True):
        self.recognize, self.config = recognize_fn, config
        self.optimize_finish = optimize_finish
        async def full_text(pcm):
            result = await recognize_fn(pcm, False)
            if asyncio.current_task().cancelling():
                raise asyncio.CancelledError()
            return result.text
        def publish(state):
            if on_progress:
                on_progress({**state, 'strategy': 'growing'})
        super().__init__(audio, full_text, on_progress=publish, interval=interval,
                         timeout=timeout, retry_delay=retry_delay)

    async def run(self):
        self.audio.attach()
        cfg = self.config
        samples = lambda seconds: round(seconds * SAMPLE_RATE)
        initial, growth, soft, hard = map(samples, (cfg.initial, cfg.growth, cfg.soft_limit, cfg.hard_limit))
        previous, last_end = None, -1
        cached_start = cached_end = -1
        cached_pcm = cached_result = None
        while True:
            if self.recovery_required:
                self.publish()
                if self.audio.position().finished:
                    return await self._recover_recording()
                await self.audio.receive()
                continue
            position = self.context.position()
            if position.finished and self.start >= position.samples:
                break
            target = self.start + initial if last_end < 0 else last_end + growth
            if self.optimize_finish and position.finished and position.samples - self.start <= hard:
                target = position.samples  # No need for speculative growing steps after Stop.
            end = min(position.samples, self.start + hard, target)
            final = position.finished and end == position.samples
            if end <= self.start or (not position.finished and (
                    end - self.start < initial or (last_end >= 0 and end - last_end < growth))):
                self.publish()
                await self.audio.receive()
                continue
            if not self.context.has_speech(self.start, end):
                if final:
                    self.start = self.resolved = end
                    break
                last_end = end
                if end - self.start >= hard:
                    self.recovery_required = True
                else:
                    await self.audio.receive()
                continue
            pcm = self.context.snapshot(self.start, end)
            reuse = (self.optimize_finish and final and cached_start == self.start and cached_end == end
                     and cached_result is not None and cached_result.text.strip() and cached_pcm == pcm)
            if reuse:
                self.reused += 1
            else:
                self.requests += 1
                self.max_request_samples = max(self.max_request_samples, end - self.start)
            self.publish()
            try:
                if reuse:
                    result = cached_result
                else:
                    # No earlier commit means the final response is already
                    # authoritative. Word times cannot affect its text.
                    timestamps = not (self.optimize_finish and final and self.start == 0 and not self.sections)
                    call = self.recognize(pcm, timestamps)
                    result = await call if position.finished else await asyncio.wait_for(call, self.timeout)
                if asyncio.current_task().cancelling():
                    raise asyncio.CancelledError()
                if not reuse:
                    self.completed += 1
                    cached_start, cached_end, cached_pcm, cached_result = self.start, end, pcm, result
                if self.optimize_finish and final and self.start == 0 and not self.sections and result.text.strip():
                    self.sections = [result.text.strip()]
                    self.start = self.resolved = end
                    self.agreement.reset()
                    break
                words = validated_words(result, (end - self.start) / SAMPLE_RATE)
                if words is None or (not words and self.context.has_speech(self.start, end)):
                    previous = None
                    self.agreement.reset()
                    # With no earlier commit, a final response already covers
                    # everything. Alignment is only required to cut/join audio.
                    if final and self.start == 0 and not self.sections and result.text.strip():
                        self.sections = [result.text.strip()]
                        self.start = self.resolved = end
                        break
                    if final or end - self.start >= hard:
                        self.recovery_required = True
                    last_end = end
                    self.publish()
                    continue
                # Re-observed left context is discarded by acoustic position,
                # never by searching for an identical suffix in another window.
                words = [w for w in words if self.start + (w.start + w.end) * SAMPLE_RATE / 2 > self.resolved]
                if final:
                    text = ' '.join(w.text for w in words).strip()
                    if text:
                        self.sections.append(text)
                    self.start = self.resolved = end
                    self.agreement.reset()
                    break
                count = 0
                if previous is not None:
                    while count < min(len(previous), max(0, len(words) - cfg.hold_words)):
                        if word_key(previous[count].text) != word_key(words[count].text):
                            break
                        if end - self.start - samples(words[count].end) < samples(cfg.tail):
                            break
                        count += 1
                # Aligners sometimes collapse a short word to a point. Keep
                # the whole ambiguous boundary pending; never cut through it.
                while count and (words[count - 1].end <= words[count - 1].start or
                        (count < len(words) and (words[count].start + words[count].end) / 2 <= words[count - 1].end)):
                    count -= 1
                self.agreement.stable = ' '.join(w.text for w in words[:count])
                self.agreement.tentative = ' '.join(w.text for w in words[count:])
                if count and end - self.start >= soft:
                    frontier = self.start + samples(words[count - 1].end)
                    if frontier - self.resolved >= samples(cfg.minimum_advance):
                        self.sections.append(self.agreement.stable)
                        self.resolved = frontier
                        self.start = max(self.start, frontier - samples(cfg.context))
                        self.rolls += 1
                        previous, last_end = None, -1
                        self.agreement.reset()
                        self.publish()
                        continue
                previous, last_end = words, end
                if end - self.start >= hard:
                    self.recovery_required = True
                    self.agreement.reset()
                self.publish()
            except Exception:
                self.failures += 1
                previous, last_end = None, -1
                self.agreement.reset()
                self.publish()
                if position.finished:
                    raise
                await self.pace(self.retry_delay)
            if not self.audio.position().finished:
                await self.pace(self.interval)
        text = ' '.join(self.sections).strip()
        if not text:
            raise ValueError('No speech was recognized. Please try again.')
        self.publish()
        return text
