"""PC port of Android's StreamingAudio and StreamingTranscriber.

Keep the constants and state transitions in sync with the Kotlin files under
android-app/.../dictation. Capture, VAD and HTTP inference are independent.
"""
import asyncio
import base64
import bisect
import io
import threading
import unicodedata
from dataclasses import dataclass

import httpx
import numpy as np
import soundfile as sf

from speech_detection import vad_session

SAMPLE_RATE = 16_000
MAX_SAMPLES = SAMPLE_RATE * 300
FRAME_SAMPLES = 512
SILENCE_SAMPLES = 320 * 16
MINIMUM_SAMPLES = 32_000
WINDOW_SAMPLES = 128_000
OVERLAP_SAMPLES = 48_000
PRE_ROLL_SAMPLES = 4_800
INTERVAL_SEC = 1.0
CONTEXT_QUIET_SAMPLES = 12_000  # Keep both sides of a long gap: at most 1.5 s.
REVISABLE_WORDS = 3
BACKGROUND_TIMEOUT_SEC = 4.0
RETRY_DELAY_SEC = 1.5
RETRY_STATUSES = {408, 429, 500, 502, 503, 504}
TRANSCRIBE_MODEL = "Qwen/Qwen3-ASR-1.7B"


class SileroDetector:
    """Identical state, frame, context and CPU session to Android Silero VAD."""
    frame_size = FRAME_SAMPLES

    def __init__(self):
        self.session = vad_session()
        self.state = np.zeros((2, 1, 128), np.float32)
        self.context = np.zeros(64, np.float32)

    def probability(self, frame):
        values, self.state = self.session.run(None, {
            "input": np.concatenate((self.context, frame))[None, :],
            "state": self.state, "sr": np.array(SAMPLE_RATE, dtype=np.int64),
        })
        self.context = frame[-64:].copy()
        return float(values.item())


@dataclass(frozen=True)
class Position:
    samples: int
    pause: int
    last_speech: int
    finished: bool
    pauses: int
    probability: float
    pending_samples: int


class StreamingAudio:
    def __init__(self, detector=None, silence_samples=SILENCE_SAMPLES, max_samples=MAX_SAMPLES):
        self.detector = detector or SileroDetector()
        self.frame_size = self.detector.frame_size
        self.silence_samples = silence_samples
        self.max_samples = max_samples
        self.lock = threading.RLock()
        self.data = bytearray()
        self.samples = self.pause = self.last_speech = self.quiet_samples = 0
        self.frame = np.zeros(self.frame_size, np.float32)
        self.frame_samples = 0
        self.pauses = []
        self.speech_ends = []
        self.probability = 0.0
        self.speaking = self.pause_reported = self.finished = False
        self.loop = self.updates = None

    def attach(self):
        with self.lock:
            self.loop = asyncio.get_running_loop()
            self.updates = asyncio.Queue(maxsize=1)
            self._notify()

    def _notify(self):
        if self.loop is not None:
            def signal():
                if not self.updates.full():
                    self.updates.put_nowait(None)
            try:
                self.loop.call_soon_threadsafe(signal)
            except RuntimeError:
                pass  # Event loop already closed after cancellation or failure.

    def append(self, pcm):
        if len(pcm) % 2:
            raise ValueError("Incomplete PCM sample")
        with self.lock:
            if self.finished:
                raise ValueError("Capture already finished")
            allowed = min(len(pcm) // 2, self.max_samples - self.samples)
            pcm = pcm[:allowed * 2]
            self.data.extend(pcm)
            samples = np.frombuffer(pcm, dtype='<i2')
            index = 0
            while index < len(samples):
                count = min(self.frame_size - self.frame_samples, len(samples) - index)
                self.frame[self.frame_samples:self.frame_samples + count] = samples[index:index + count].astype(np.float32) / 32768.0
                self.frame_samples += count
                self.samples += count
                index += count
                if self.frame_samples == self.frame_size:
                    self.probability = self.detector.probability(self.frame)
                    self.speaking = self.probability >= float(np.float32(.30 if self.speaking else .50))
                    if self.speaking:
                        self.last_speech = self.samples
                        self.speech_ends.append(self.samples)
                        self.quiet_samples = 0
                        self.pause_reported = False
                    else:
                        self.quiet_samples += self.frame_size
                        if (not self.pause_reported and self.last_speech > self.pause
                                and self.quiet_samples >= self.silence_samples):
                            self.pause = self.samples
                            self.pauses.append(self.pause)
                            self.pause_reported = True
                    self.frame_samples = 0
            self._notify()
            return allowed

    def position(self):
        with self.lock:
            return Position(self.samples, self.pause, self.last_speech, self.finished,
                            len(self.pauses), self.probability, self.frame_samples)

    def pause_between(self, start, end):
        with self.lock:
            index = bisect.bisect_right(self.pauses, end) - 1
            return self.pauses[index] if index >= 0 and self.pauses[index] > start else None

    def has_speech(self, start, end):
        with self.lock:
            index = bisect.bisect_left(self.speech_ends, start + 1)
            return index < len(self.speech_ends) and self.speech_ends[index] - self.frame_size < end

    def snapshot(self, start, end):
        with self.lock:
            if not 0 <= start <= end <= self.samples:
                raise ValueError("Invalid audio range")
            return bytes(self.data[start * 2:end * 2])

    def finish(self):
        with self.lock:
            self.finished = True
            self._notify()

    async def receive(self):
        await self.updates.get()


class LocalAgreement2:
    def __init__(self):
        self.reset()

    def reset(self):
        self.previous = []
        self.stable = self.tentative = ""

    def update(self, text):
        current = text.split()
        count = 0
        while count < min(len(self.previous), max(0, len(current) - REVISABLE_WORDS)) and self.previous[count] == current[count]:
            count += 1
        self.stable = " ".join(current[:count])
        self.tentative = " ".join(current[count:])
        self.previous = current


class RecognitionAudio:
    """Speech-preserving view; original PCM remains available for recovery.

    Normal pauses are untouched. For gaps over 1.5 seconds, retain 750 ms
    on each side so a long thinking pause cannot evict the acoustic overlap.
    Coordinates inside the worker refer to this view, never word estimates.
    """
    def __init__(self, source):
        self.source = source
        self.spans = []
        self.samples = 0
        self.raw = source.position()

    def position(self):
        with self.source.lock:
            raw = self.source.position()
            ends = list(self.source.speech_ends)
        regions = [(end - self.source.frame_size, end) for end in ends]
        if raw.finished and raw.pending_samples:
            regions.append((raw.samples - raw.pending_samples, raw.samples))
        spans, cursor, last = [], 0, 0
        for first, end in regions:
            if first - last > CONTEXT_QUIET_SAMPLES * 2:
                spans.append((cursor, last + CONTEXT_QUIET_SAMPLES))
                cursor = first - CONTEXT_QUIET_SAMPLES
            last = end
        end = min(raw.samples, last + CONTEXT_QUIET_SAMPLES)
        if cursor < end:
            spans.append((cursor, end))
        self.spans, self.raw = spans, raw
        self.samples = sum(end - first for first, end in spans)
        last_speech = sum(max(0, min(end, raw.last_speech) - first) for first, end in spans)
        return Position(self.samples, 0, last_speech, raw.finished,
                        raw.pauses, raw.probability, raw.pending_samples)

    def ranges(self, start, end):
        if not 0 <= start <= end <= self.samples:
            raise ValueError("Invalid recognition audio range")
        offset = 0
        for first, last in self.spans:
            size = last - first
            left, right = max(start, offset), min(end, offset + size)
            if left < right:
                yield first + left - offset, first + right - offset
            offset += size

    def snapshot(self, start, end):
        return b''.join(self.source.snapshot(first, last) for first, last in self.ranges(start, end))

    def has_speech(self, start, end):
        return any(self.source.has_speech(first, last) for first, last in self.ranges(start, end))

    def raw_offset(self, sample):
        if sample == self.samples and self.raw.finished:
            return self.raw.samples
        offset = 0
        for first, last in self.spans:
            if sample <= offset + last - first:
                return first + sample - offset
            offset += last - first
        return self.raw.samples


def overlap_join(previous, current):
    old, new = previous.split(), current.split()
    def key(word):
        # Kotlin filters UTF-16 Char values with isLetterOrDigit().
        data = word.lower().encode('utf-16-le', errors='surrogatepass')
        units = (chr(int.from_bytes(data[i:i + 2], 'little')) for i in range(0, len(data), 2))
        return ''.join(ch for ch in units if unicodedata.category(ch).startswith('L')
                       or unicodedata.category(ch) == 'Nd')
    left, right = list(map(key, old)), list(map(key, new))
    matches = []
    for drop_end in range(min(2, len(old)) + 1):
        end = len(old) - drop_end
        for new_start in range(min(2, len(new)) + 1):
            for old_start in range(end):
                count = end - old_start
                if count < 2 or new_start + count > len(new):
                    continue
                anchor = left[old_start:end]
                if any(not item for item in anchor) or sum(map(len, anchor)) < 8 or len(set(anchor)) < 2:
                    continue
                if anchor == right[new_start:new_start + count]:
                    matches.append((old_start, new_start, count))
    if not matches:
        return None
    length = max(match[2] for match in matches)
    best = [match for match in matches if match[2] == length]
    if len({match[:2] for match in best}) != 1:
        return None
    old_start, new_start, _ = best[0]
    # A fresh ASR window can capitalize its opening word. If the overlapping
    # earlier hypothesis already placed that same word in a continuing sentence,
    # retain its case. Do not change names/acronyms already capitalized there,
    # sentence starts, quoted openings, or casing elsewhere in Qwen's output.
    if old_start > 0 and new_start == 0:
        before = old[old_start - 1]
        earlier, opening = old[old_start], new[0]
        if (not before.endswith(tuple('.!?…:;"\'“”‘’([{'))
                and earlier.isalpha() and earlier.islower()
                and 'A' <= opening[0] <= 'Z' and opening[1:].islower()
                and key(earlier) == key(opening)):
            new[0] = opening[:1].lower() + opening[1:]
    return ' '.join(old[:old_start]), ' '.join(new[new_start:])


class ModelError(ValueError):
    pass


class RequestFailure(OSError):
    pass


async def request_model(client, url, payload, *, structured=False):
    for attempt in range(3):
        try:
            response = await asyncio.wait_for(client.post(url, json=payload), timeout=150)
            if response.status_code in RETRY_STATUSES:
                raise httpx.HTTPStatusError(f"Model request failed (HTTP {response.status_code})",
                                            request=response.request, response=response)
            response.raise_for_status()
            result = response.json()
            try:
                choice = result['choices'][0]
            except (KeyError, IndexError, TypeError) as error:
                raise RequestFailure("The server returned no completion.") from error
            if choice.get('finish_reason') == 'length':
                raise ModelError("The server's output limit was reached. Try a shorter recording.")
            content = choice['message']['content']
            if not isinstance(content, str):
                raise ModelError("Unexpected server response: missing text content.")
            return result if structured else content
        except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError, asyncio.TimeoutError,
                httpx.HTTPStatusError) as error:
            if isinstance(error, httpx.HTTPStatusError) and error.response.status_code not in RETRY_STATUSES:
                raise RequestFailure(f"Model request failed (HTTP {error.response.status_code}).") from error
            if attempt == 2:
                raise RequestFailure("The PC model request failed after three attempts.") from error
            await asyncio.sleep(.25 * (attempt + 1))


def mobile_client():
    return httpx.AsyncClient(timeout=httpx.Timeout(120, connect=8, write=30),
                             follow_redirects=False, trust_env=False)


def completion_url(base):
    base = base.rstrip('/')
    return base + ('/chat/completions' if base.endswith('/v1') else '/v1/chat/completions')


async def transcribe(client, asr_url, pcm):
    bio = io.BytesIO()
    samples = np.frombuffer(pcm, dtype='<i2')
    sf.write(bio, samples, SAMPLE_RATE, format='WAV', subtype='PCM_16')
    payload = {'model': TRANSCRIBE_MODEL, 'messages': [{'role': 'user', 'content': [
        {'type': 'input_audio', 'input_audio': {'data': base64.b64encode(bio.getvalue()).decode('ascii'),
                                               'format': 'wav'}}]}],
        'temperature': 0, 'max_tokens': 4096, 'stream': False, 'language': 'English'}
    text = await request_model(client, completion_url(asr_url), payload)
    return text.split('<asr_text>', 1)[-1].split('<|im_end|>', 1)[0].strip()


async def polish(client, cleanup_url, model, prompt, text):
    payload = {'model': model, 'messages': [{'role': 'system', 'content': prompt},
                                           {'role': 'user', 'content': text}],
               'temperature': 0, 'max_tokens': 4096, 'stream': False,
               'reasoning_effort': 'none', 'chat_template_kwargs': {'enable_thinking': False}}
    result = (await request_model(client, completion_url(cleanup_url), payload)).strip()
    if not result:
        raise ModelError("Cleanup returned no text. Your original transcript is available.")
    return result


class StreamingTranscriber:
    def __init__(self, audio, transcribe_fn, *, interval=INTERVAL_SEC, timeout=BACKGROUND_TIMEOUT_SEC,
                 minimum=MINIMUM_SAMPLES, limit=WINDOW_SAMPLES, retry_delay=RETRY_DELAY_SEC, on_progress=None):
        self.audio, self.transcribe = audio, transcribe_fn
        self.context = RecognitionAudio(audio)
        self.interval, self.timeout, self.minimum, self.limit, self.retry_delay = interval, timeout, minimum, limit, retry_delay
        self.overlap = min(OVERLAP_SAMPLES, limit // 2)
        self.on_progress = on_progress or (lambda _: None)
        self.agreement = LocalAgreement2()
        self.sections = []
        self.start = self.resolved = 0
        self.bridge = None
        self.pending_prefix = ''
        self.requests = self.completed = self.failures = self.reused = self.rolls = 0
        self.max_request_samples = 0
        self.recovery_required = False
        self.recovery_requests = self.recovery_samples = 0
        self.recovered = False

    def publish(self):
        self.on_progress({'requests': self.requests, 'completed': self.completed, 'failures': self.failures,
                          'reused': self.reused, 'rolls': self.rolls, 'sections': len(self.sections),
                          'finalized_samples': self.context.raw_offset(self.resolved),
                          'start': self.context.raw_offset(self.start),
                          'max_request_samples': self.max_request_samples,
                          'recovery_required': self.recovery_required,
                          'recovery_requests': self.recovery_requests,
                          'recovery_samples': self.recovery_samples, 'recovered': self.recovered,
                          'stable': ' '.join(s for s in [*self.sections, self.pending_prefix, self.agreement.stable] if s.strip()),
                          'tentative': self.agreement.tentative})

    async def pace(self, seconds):
        async def wait_until_finished():
            while not self.audio.position().finished:
                await self.audio.receive()
        try:
            await asyncio.wait_for(wait_until_finished(), timeout=seconds)
        except asyncio.TimeoutError:
            pass

    async def run(self):
        self.audio.attach()
        last_end, last_text = -1, ''
        while True:
            if self.recovery_required:
                self.publish()
                if self.audio.position().finished:
                    return await self._recover_recording()
                await self.audio.receive()
                continue
            position = self.context.position()
            limit = min(position.samples, self.start + self.limit)
            end = limit
            final = position.finished and end == position.samples
            checkpoint = not final and end - self.start == self.limit
            if position.finished and self.start == position.samples:
                break
            if (self.bridge is None and end > self.start and not self.context.has_speech(self.start, end)
                    and (end < position.samples or position.pending_samples == 0)):
                self.start = end if position.finished else max(self.start, end - PRE_ROLL_SAMPLES)
                self.resolved = self.start
                last_end, last_text = -1, ''
                self.agreement.reset()
                self.pending_prefix = ''
                self.publish()
                if not position.finished:
                    await self.audio.receive()
                continue
            if end <= self.start or (not final and not checkpoint and (end - self.start < self.minimum or end == last_end)):
                self.publish()
                await self.audio.receive()
                continue
            if final and self.bridge is None and end == last_end and last_text.strip():
                self.sections.append(last_text.strip())
                self.reused += 1
                self.start = self.resolved = end
                last_end, last_text = -1, ''
                self.agreement.reset()
                self.publish()
                continue
            self.requests += 1
            self.max_request_samples = max(self.max_request_samples, end - self.start)
            self.publish()
            try:
                pcm = self.context.snapshot(self.start, end)
                call = self.transcribe(pcm)
                text = await call if position.finished else await asyncio.wait_for(call, timeout=self.timeout)
                if not ((not final and not checkpoint and self.bridge is None) or text.strip()
                        or not self.context.has_speech(self.start, end)):
                    raise ValueError("No speech was recognized in an audio section. Please try again.")
                self.completed += 1
                if self.bridge is not None:
                    previous, old_start, widened = self.bridge
                    joined = overlap_join(previous, text)
                    if joined is None:
                        if widened:
                            self.recovery_required = True
                            self.pending_prefix = ''
                            self.agreement.reset()
                            self.publish()
                            continue
                        self.start = old_start + (self.start - old_start) // 2
                        self.bridge = (previous, old_start, True)
                        self.pending_prefix = ''
                        self.agreement.reset()
                        last_end, last_text = -1, ''
                        self.publish()
                        continue
                    self.pending_prefix, text = joined
                    self.resolved = self.start
                    if final or checkpoint:
                        if self.pending_prefix.strip():
                            self.sections.append(self.pending_prefix)
                        self.pending_prefix = ''
                        self.bridge = None
                if final:
                    if text.strip():
                        self.sections.append(text.strip())
                    self.start = self.resolved = end
                    last_end, last_text = -1, ''
                    self.agreement.reset()
                    self.pending_prefix = ''
                elif checkpoint:
                    self.bridge = (text, self.start, False)
                    self.start = end - self.overlap
                    self.rolls += 1
                    self.agreement.reset()
                    last_end, last_text = -1, ''
                    self.pending_prefix = ''
                else:
                    self.agreement.update(text)
                    last_end, last_text = end, text
                self.publish()
            except Exception as error:
                self.failures += 1
                self.agreement.reset()
                last_end, last_text = -1, ''
                self.publish()
                if position.finished:
                    raise
                await self.pace(max(self.interval, self.retry_delay))
            if not self.audio.position().finished:
                await self.pace(self.interval)
        text = ' '.join(self.sections).strip()
        if not text:
            raise ValueError("No speech was recognized. Please try again.")
        self.publish()
        return text

    async def _recover_recording(self):
        """One authoritative result from original PCM, never append to old text."""
        position = self.audio.position()
        if not position.finished:
            raise RuntimeError('Full-recording recovery requires capture to finish')
        self.requests += 1
        self.recovery_requests += 1
        self.recovery_samples = position.samples
        self.publish()
        try:
            text = (await self.transcribe(self.audio.snapshot(0, position.samples))).strip()
            if not text:
                raise ValueError('Full-recording ASR recovery returned no text; original audio retained.')
        except Exception:
            self.failures += 1
            self.publish()
            raise
        self.completed += 1
        self.sections = [text]
        self.pending_prefix = ''
        self.bridge = None
        self.agreement.reset()
        self.start = self.resolved = self.context.position().samples
        self.recovery_required = False
        self.recovered = True
        self.publish()
        return text
