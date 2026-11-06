import asyncio
import json
import unittest

import httpx
import numpy as np

from growing_window_transcriber import (AlignedTranscript, GrowingConfig, GrowingWindowTranscriber,
                                        TimedWord, transcribe_aligned, validated_words)
from mobile_streaming import StreamingAudio


class Speech:
    frame_size = 512
    def probability(self, frame): return 1.


def result(*words):
    timed = tuple(TimedWord(*word) for word in words)
    return AlignedTranscript(' '.join(w.text for w in timed), timed)


def example_answers():
    return [result(('I', .1, .4), ('am', .5, 1.2), ('try', 2., 2.9)),
            result(('I', .1, .4), ('am', .5, 1.2), ('trying', 2., 3.), ('to', 3.2, 3.5), ('get', 3.7, 4.)),
            result(('am', 0., .5), ('trying', 1.3, 2.3), ('to', 2.5, 2.9)),
            result(('am', 0., .5), ('trying', 1.3, 2.3), ('to', 2.5, 2.9), ('get', 3., 3.3), ('there', 4., 4.7)),
            result(('to', 0., .5), ('get', .6, 1.), ('there', 1.2, 1.7), ('safely.', 2., 2.7))]


EXAMPLE_CONFIG = GrowingConfig(initial=3, growth=2, soft_limit=5, hard_limit=10,
                               context=.5, tail=.3, hold_words=1, minimum_advance=1)


class GrowingTests(unittest.IsolatedAsyncioTestCase):
    def audio(self, seconds, finished=True):
        audio = StreamingAudio(Speech())
        raw = (np.arange(round(seconds * 16000), dtype=np.int32) % 30000 + 1).astype('<i2').tobytes()
        audio.append(raw)
        if finished: audio.finish()
        return audio, raw

    async def test_grows_same_start_repairs_partial_word_and_advances_by_timestamp(self):
        audio, raw = self.audio(6)
        answers, calls = iter(example_answers()), []
        async def recognize(data, timestamps):
            self.assertTrue(timestamps)
            calls.append(data)
            return next(answers)
        engine = GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG, optimize_finish=False)
        self.assertEqual(await engine.run(), 'I am trying to get there safely.')
        self.assertEqual(calls[:2], [raw[:96000], raw[:160000]])
        # "am" ended at 1.2 seconds: next start retains 0.5 seconds of context.
        self.assertEqual(calls[2], raw[22400:118400])
        self.assertEqual(len(calls), 5)
        self.assertEqual(engine.rolls, 2)
        self.assertEqual(engine.resolved, 96000)
        self.assertFalse(engine.recovered)

    async def test_no_matching_prefix_keeps_same_start_and_final_result_can_resolve_it(self):
        audio, raw = self.audio(8)
        calls = []
        answers = iter([result(('Alpha', .1, 1.)), result(('Beta', .1, 1.)),
                        result(('Gamma', .1, 1.)), result(('Final', .1, 1.), ('complete.', 1.1, 2.))])
        async def recognize(data, timestamps): calls.append(data); return next(answers)
        engine = GrowingWindowTranscriber(audio, recognize,
            config=GrowingConfig(initial=2, growth=2, soft_limit=4, hard_limit=10), optimize_finish=False)
        self.assertEqual(await engine.run(), 'Final complete.')
        self.assertEqual(calls, [raw[:n * 32000] for n in (2, 4, 6, 8)])
        self.assertEqual(engine.rolls, 0)
        self.assertFalse(engine.recovered)

    async def test_hard_limit_waits_for_stop_and_full_recovery_includes_later_audio(self):
        audio, raw = self.audio(4, finished=False)
        pending = asyncio.Event()
        calls = []
        async def recognize(data, timestamps):
            calls.append((data, timestamps))
            if not timestamps: return AlignedTranscript('Entire recording including later speech.')
            return result((f'Changed{len(calls)}', .1, 1.))
        engine = GrowingWindowTranscriber(audio, recognize,
            config=GrowingConfig(initial=2, growth=2, soft_limit=4, hard_limit=4),
            on_progress=lambda p: pending.set() if p['recovery_required'] else None)
        task = asyncio.create_task(engine.run())
        await asyncio.wait_for(pending.wait(), 2)
        self.assertFalse(task.done())
        self.assertEqual(len(calls), 2)
        tail = np.ones(16000, '<i2').tobytes()
        audio.append(tail); audio.finish()
        self.assertEqual(await task, 'Entire recording including later speech.')
        self.assertEqual(calls[-1], (raw + tail, False))
        self.assertEqual(engine.sections, ['Entire recording including later speech.'])

    async def test_missing_alignment_after_commits_recovers_instead_of_guessing_context(self):
        audio, raw = self.audio(6)
        answers = iter([*example_answers()[:-1], AlignedTranscript('Unaligned remaining text.'),
                        AlignedTranscript('Authoritative original, including not.')])
        calls = []
        async def recognize(data, timestamps): calls.append((data, timestamps)); return next(answers)
        engine = GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG, optimize_finish=False)
        self.assertEqual(await engine.run(), 'Authoritative original, including not.')
        self.assertEqual(calls[-1], (raw, False))
        self.assertEqual(engine.sections, ['Authoritative original, including not.'])
        self.assertTrue(engine.recovered)

    async def test_failed_recovery_preserves_raw_audio_and_partial_state(self):
        for blank in (True, False):
            audio, raw = self.audio(6)
            answers = iter([*example_answers()[:-1], AlignedTranscript('Unaligned.')])
            async def recognize(data, timestamps):
                if timestamps: return next(answers)
                if blank: return AlignedTranscript('')
                raise OSError('offline')
            engine = GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG, optimize_finish=False)
            with self.assertRaises((ValueError, OSError)): await engine.run()
            self.assertTrue(engine.recovery_required)
            self.assertFalse(engine.recovered)
            self.assertEqual(engine.sections, ['I am', 'trying to'])
            self.assertEqual(audio.snapshot(0, 96000), raw)

    async def test_cancel_during_alignment_accepts_no_late_result(self):
        audio, _ = self.audio(3)
        entered = asyncio.Event()
        async def recognize(data, timestamps): entered.set(); await asyncio.sleep(30)
        engine = GrowingWindowTranscriber(audio, recognize)
        task = asyncio.create_task(engine.run())
        await entered.wait(); task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertEqual(engine.sections, [])

    async def test_short_final_command_does_not_require_alignment_or_a_minimum_duration(self):
        audio, _ = self.audio(.2)
        async def recognize(data, timestamps):
            self.assertFalse(timestamps)
            return AlignedTranscript('Yes.')
        engine = GrowingWindowTranscriber(audio, recognize)
        self.assertEqual(await engine.run(), 'Yes.')
        self.assertEqual(engine.requests, 1)

    async def test_stop_flushes_the_complete_pending_tail_once_and_preserves_punctuation(self):
        audio, raw = self.audio(9)
        calls = []
        async def recognize(data, timestamps):
            calls.append((data, timestamps))
            return AlignedTranscript('Because right now, it is giving me some Hindi words. Do not remove this comma.')
        engine = GrowingWindowTranscriber(audio, recognize)
        self.assertEqual(await engine.run(),
            'Because right now, it is giving me some Hindi words. Do not remove this comma.')
        self.assertEqual(calls, [(raw, False)])
        self.assertEqual(engine.requests, 1)
        self.assertEqual(engine.resolved, 144000)

    async def test_stop_reuses_only_a_byte_identical_successful_request(self):
        audio, raw = self.audio(4, finished=False)
        calls = []
        async def recognize(data, timestamps):
            calls.append((data, timestamps))
            audio.finish()  # Stop while this exact request is in flight.
            return result(('Please,', .1, .4), ('do', .4, .6), ('not', .6, .8), ('stop.', .8, 1.2))
        engine = GrowingWindowTranscriber(audio, recognize)
        self.assertEqual(await engine.run(), 'Please, do not stop.')
        self.assertEqual(calls, [(raw, True)])
        self.assertEqual(engine.reused, 1)
        self.assertEqual(engine.requests, 1)

    async def test_even_a_short_new_tail_requires_a_fresh_response(self):
        audio, raw = self.audio(4, finished=False)
        tail = np.ones(100, dtype='<i2').tobytes()
        calls = []
        async def recognize(data, timestamps):
            calls.append((data, timestamps))
            if len(calls) == 1:
                audio.append(tail); audio.finish()
                return result(('Do', .1, .4), ('drop', .4, .8))
            return AlignedTranscript('Do not drop the tail.')
        engine = GrowingWindowTranscriber(audio, recognize)
        self.assertEqual(await engine.run(), 'Do not drop the tail.')
        self.assertEqual(calls, [(raw, True), (raw + tail, False)])
        self.assertEqual(engine.reused, 0)

    async def test_optimized_finish_after_live_commits_still_requires_alignment_for_context(self):
        audio, _ = self.audio(6, finished=False)
        answers, calls = iter(example_answers()), []
        async def recognize(data, timestamps):
            calls.append((len(data), timestamps))
            if len(calls) == 4: audio.finish()
            return next(answers)
        engine = GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG)
        self.assertEqual(await engine.run(), 'I am trying to get there safely.')
        self.assertEqual(len(calls), 5)
        self.assertTrue(calls[-1][1])  # Never guess which words are left context.
        self.assertEqual(engine.rolls, 2)

    async def test_collapsed_word_near_boundary_stays_pending_instead_of_being_cut_away(self):
        audio, _ = self.audio(4, finished=False)
        response = result(('I', .1, .4), ('am', .4, 1.), ('to', 1., 1.), ('the', 1., 1.4), ('end', 1.4, 1.8))
        rolled = asyncio.Event()
        block = asyncio.Event()
        calls = 0
        async def recognize(data, timestamps):
            nonlocal calls
            calls += 1
            if calls > 2: await block.wait()
            return response
        engine = GrowingWindowTranscriber(audio, recognize,
            config=GrowingConfig(initial=2, growth=2, soft_limit=4, hard_limit=6,
                                 context=.2, tail=.2, minimum_advance=.3),
            on_progress=lambda p: rolled.set() if p['rolls'] else None)
        task = asyncio.create_task(engine.run())
        await asyncio.wait_for(rolled.wait(), 2)
        self.assertEqual(engine.sections, ['I'])  # "am to" share an ambiguous end time.
        task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task

    async def test_cancelled_recovery_cannot_accept_a_callback_that_suppresses_cancellation(self):
        audio, _ = self.audio(6)
        answers = iter([*example_answers()[:-1], AlignedTranscript('Unaligned.')])
        entered = asyncio.Event()
        async def recognize(data, timestamps):
            if timestamps: return next(answers)
            entered.set()
            try: await asyncio.sleep(30)
            except asyncio.CancelledError: return AlignedTranscript('Late text must not be accepted.')
        engine = GrowingWindowTranscriber(audio, recognize, config=EXAMPLE_CONFIG, optimize_finish=False)
        task = asyncio.create_task(engine.run())
        await entered.wait(); task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertFalse(engine.recovered)
        self.assertEqual(engine.sections, ['I am', 'trying to'])

    def test_alignment_requires_complete_text_and_finite_ordered_times(self):
        for words in [(), (TimedWord('Please', 0., 1.),),
                      (TimedWord('Please not', float('nan'), 1.),),
                      (TimedWord('Please not', -.1, 1.),),
                      (TimedWord('Please not', 0., 100.),)]:
            self.assertIsNone(validated_words(AlignedTranscript('Please not', words), 3))
        repeated = result(('very', 0., 1.), ('very', 1., 2.))
        self.assertEqual(len(validated_words(repeated, 3)), 2)

    async def test_client_requests_alignment_and_preserves_negation_and_raw_words(self):
        requests = []
        def respond(request):
            requests.append(json.loads(request.content))
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop',
                'message': {'content': '<asr_text>Do not.<|im_end|>'}}],
                'timestamps': [{'word': 'Do', 'start': 0., 'end': .2}, {'word': 'not.', 'start': .2, 'end': .5}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            aligned = await transcribe_aligned(client, 'http://test', b'\0\0' * 16000)
            final = await transcribe_aligned(client, 'http://test', b'\0\0' * 16000, False)
        self.assertTrue(requests[0]['return_time_stamps'])
        self.assertFalse(requests[1]['return_time_stamps'])
        self.assertEqual(aligned, result(('Do', 0., .2), ('not.', .2, .5)))
        self.assertEqual(final, AlignedTranscript('Do not.'))
