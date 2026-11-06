import asyncio
import base64
import io
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

import asr_server as server
import speech_detection
from asr_streaming import AlignmentError, attach_punctuation, partition_window, deduplicate_timed_words
from sliding_window_transcriber import SlidingWindowTranscriber


class ChatLanguageTests(unittest.TestCase):
    def test_explicit_language_reaches_qwen_and_omitted_language_keeps_auto_detection(self):
        result = [SimpleNamespace(text='English transcript.', language='English', time_stamps=[])]
        for language, expected in [('English', ['English']), (None, None)]:
            payload = {'model': 'Qwen/Qwen3-ASR-1.7B', 'messages': [{'role': 'user', 'content': [
                {'type': 'input_audio', 'input_audio': {'data': encoded_audio(), 'format': 'wav'}}]}]}
            if language:
                payload['language'] = language
            with patch.object(server, 'transcribe_audio', return_value=result) as transcribe:
                with TestClient(server.app) as client:
                    response = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(transcribe.call_args.args[3], expected)
            self.assertIn('English transcript.', response.json()['choices'][0]['message']['content'])


def word(text, start, end):
    return {"word": text, "start": start, "end": end}


def encoded_audio(seconds=5, channels=1, value=.01):
    out = io.BytesIO()
    sf.write(out, np.full((round(seconds * 16000), channels), value, dtype=np.float32), 16000,
             format="WAV", subtype="PCM_16")
    return base64.b64encode(out.getvalue()).decode()


class AlignmentTests(unittest.TestCase):
    def test_observed_qwen_tail_drift_is_clamped(self):
        result = partition_window("the repetition", [word("the", 4.582, 4.662), word("repetition", 4.662, 5.222)], 3.34, 5, 1.5)
        self.assertEqual(result['preview_words'][-1]['end'], 8.34)

    def test_observed_eighty_ms_boundary_drift_does_not_duplicate_to(self):
        previous = [word('to', 9.47, 9.55)]
        incoming = [word('to', 9.53, 9.61), word('keep', 9.61, 9.85)]
        self.assertEqual(deduplicate_timed_words(previous, incoming), incoming[1:])

    def test_timed_dedup_preserves_intentional_repetition(self):
        previous = [word('very,', 1, 1.3)]
        incoming = [word('very', 1.28, 1.6)]
        self.assertEqual(deduplicate_timed_words(previous, incoming), incoming)

    def test_unicode_punctuation_and_symbols(self):
        items = [word("Café", 0, 1), word("देवनागरी", 1, 2), word("350", 2, 3)]
        attach_punctuation('“Café,” देवनागरी $3.50!', items)
        self.assertEqual([w["word"] for w in items], ['“Café,”', 'देवनागरी', '$3.50!'])

    def test_split_contraction_restored_once(self):
        items = [word("don", 0, .2), word("t", .2, .3), word("stop", .4, .8)]
        attach_punctuation("Don't stop!", items)
        self.assertEqual(items, [word("Don't", 0, .3), word("stop!", .4, .8)])

    def test_merged_alignment_restores_phrase_without_loss(self):
        items = [word("NewYork", 0, 1), word("City", 1, 2)]
        attach_punctuation("New York City.", items)
        self.assertEqual(items, [word("New York", 0, 1), word("City.", 1, 2)])

    def test_missing_alignment_cannot_silently_drop_words(self):
        with self.assertRaises(AlignmentError):
            attach_punctuation("Keep every word.", [word("Keep", 0, 1)])

    def test_equal_timestamps_do_not_erase_tokens(self):
        result = partition_window("one two three", [word(w, 1, 1.2) for w in ["one", "two", "three"]], 0, 5, 1.5)
        self.assertEqual(len(result["committed_words"]), 3)

    def test_context_duplicate_is_suppressed_despite_end_drift(self):
        result = partition_window("very very good.", [word("very", .0, .6), word("very", .6, 1), word("good", 1, 1.4)],
                                  2.5, 5, 1.5, last_committed=3.0)
        self.assertEqual([w["word"] for w in result["committed_words"]], ["very", "good."])

    def test_cutoff_crossing_word_is_tentative_and_has_left_context(self):
        result = partition_window("First second", [word("First", 2.5, 3.2), word("second", 3.3, 3.8)], 0, 5, 1.5)
        self.assertEqual(len(result["committed_words"]), 1)
        self.assertEqual(len(result["preview_words"]), 1)
        self.assertLess(result["next_start_sec"], 3.2)

    def test_silence_makes_progress_without_fabricating_words(self):
        result = partition_window("", [], 10, 5, 1.5, last_committed=8)
        self.assertEqual(result["next_start_sec"], 13.5)
        self.assertEqual(result["last_committed_time_sec"], 8)

    def test_short_early_word_does_not_stall_window(self):
        result = partition_window("Hi.", [word("Hi", 0, .2)], 0, 5, 1.5)
        self.assertGreater(result["next_start_sec"], 0)

    def test_bad_timestamps_rejected(self):
        cases = [[word("Hi", -1, 1)], [word("Hi", 0, float('nan'))],
                 [word("Hi", 2, 1)], [word("Hi", 0, 6)],
                 [word("Hi", 1, 2), word("there", .5, 1)]]
        for items in cases:
            with self.subTest(items=items), self.assertRaises(AlignmentError):
                partition_window(" ".join(w["word"] for w in items), items, 0, 5, 1.5)

    def test_final_commits_tail_and_consumes_entire_window(self):
        result = partition_window("End.", [word("End", .1, .4)], 10, .5, 1.5, 9, True)
        self.assertEqual(result["next_start_sec"], 10.5)
        self.assertEqual(result["preview_words"], [])
        self.assertEqual(result["committed_words"][0]["word"], "End.")


class EndpointTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(server.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def post(self, **kw):
        return self.client.post('/v1/streaming/window', json={"audio_base64": encoded_audio(), **kw})

    def model_result(self, text="Ask Alice.", stamps=None):
        if stamps is None:
            stamps = [word("Ask", .1, .4), word("Alice", .5, .9)]
        return [SimpleNamespace(text=text, time_stamps=[SimpleNamespace(text=w["word"], start_time=w["start"], end_time=w["end"]) for w in stamps])]

    def test_mobile_chat_does_not_require_forced_alignment(self):
        with patch.object(server, 'transcribe_audio', return_value=self.model_result('Complete transcript.', [])) as model:
            result = self.client.post('/v1/chat/completions', json={
                'messages': [{'role': 'user', 'content': [{'type': 'input_audio',
                    'input_audio': {'data': encoded_audio(), 'format': 'wav'}}]}]})
        self.assertEqual(result.status_code, 200, result.text)
        self.assertFalse(model.call_args.args[4])
        self.assertEqual(result.json()['choices'][0]['message']['content'], '<asr_text>Complete transcript.<|im_end|>')

    def test_optional_alignment_mismatch_does_not_fail_chat(self):
        with patch.object(server, 'transcribe_audio', return_value=self.model_result('Keep every word.', [word('Keep', 0, 1)])):
            result = self.client.post('/v1/chat/completions', json={
                'return_time_stamps': True,
                'messages': [{'role': 'user', 'content': [{'type': 'input_audio',
                    'input_audio': {'data': encoded_audio(), 'format': 'wav'}}]}]})
        self.assertEqual(result.status_code, 200, result.text)
        self.assertIn('Keep every word.', result.json()['choices'][0]['message']['content'])
        self.assertEqual(result.json()['timestamps'], [])

    def test_bad_base64_and_audio_return_400(self):
        for payload in ["%%%", base64.b64encode(b'not audio').decode(), ""]:
            self.assertEqual(self.post(audio_base64=payload).status_code, 400)

    def test_negative_and_infinite_configuration_rejected(self):
        for field in ['window_start_sec', 'last_committed_time_sec', 'overlap_sec']:
            self.assertEqual(self.post(**{field: -1}).status_code, 422)

    def test_excessive_overlap_and_future_frontier_rejected(self):
        self.assertEqual(self.post(overlap_sec=5).status_code, 400)
        self.assertEqual(self.post(last_committed_time_sec=10).status_code, 400)

    def test_oversized_window_rejected_before_loading_model(self):
        with patch.object(server, "transcribe_audio") as model:
            self.assertEqual(self.post(audio_base64=encoded_audio(31)).status_code, 400)
        model.assert_not_called()

    def test_exact_silence_does_not_load_or_hallucinate(self):
        with patch.object(server, "transcribe_audio") as model:
            result = self.post(audio_base64=encoded_audio(value=0))
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["full_text"], "")
        model.assert_not_called()

    def test_stereo_downmix_and_case_preservation(self):
        with patch.object(server, "transcribe_audio", return_value=self.model_result()) as model:
            result = self.post(audio_base64=encoded_audio(channels=2))
        self.assertEqual(result.status_code, 200, result.text)
        self.assertEqual(model.call_args.args[0].ndim, 1)
        self.assertEqual(result.json()["committed_words"][1]["word"], "Alice.")

    def test_missing_timestamps_is_explicit_failure(self):
        with patch.object(server, "transcribe_audio", return_value=self.model_result("Missing words", [])):
            result = self.post()
        self.assertEqual(result.status_code, 503)

    def test_short_final_audio_padded_only_for_encoder(self):
        with patch.object(server, "transcribe_audio", return_value=self.model_result("Yes.", [word('Yes', 0, .1)])) as model:
            result = self.post(audio_base64=encoded_audio(.15), is_final=True)
        self.assertEqual(result.status_code, 200, result.text)
        self.assertEqual(len(model.call_args.args[0]), 4800)
        self.assertEqual(result.json()["window_end_sec"], .15)

    def test_models_remain_responsive_during_inference(self):
        entered, release = threading.Event(), threading.Event()
        def slow(*args):
            entered.set()
            release.wait(3)
            return self.model_result()
        with patch.object(server, "transcribe_audio", side_effect=slow):
            thread = threading.Thread(target=self.post)
            thread.start()
            try:
                self.assertTrue(entered.wait(1))
                started = time.monotonic()
                self.assertEqual(self.client.get('/v1/models').status_code, 200)
                self.assertLess(time.monotonic() - started, .5)
            finally:
                release.set()
                thread.join(3)

    def test_gpu_inference_is_serialized(self):
        active = maximum = 0
        guard = threading.Lock()
        def generate(**kw):
            nonlocal active, maximum
            with guard:
                active += 1
                maximum = max(maximum, active)
            time.sleep(.03)
            with guard:
                active -= 1
            return []
        model = Mock()
        model.transcribe.side_effect = generate
        with patch.object(server, "get_or_load_model", return_value=model), \
             patch.object(speech_detection, "speech_bounds", return_value=(0, 10)):
            threads = [threading.Thread(target=server.transcribe_audio, args=(np.ones(10), 16000)) for _ in range(4)]
            for thread in threads: thread.start()
            for thread in threads: thread.join(2)
        self.assertEqual(maximum, 1)


class StandaloneTests(unittest.TestCase):
    def test_alignment_failure_defers_until_finish_and_replaces_partial_text_without_fake_timestamps(self):
        t = self.transcriber()
        t.add_audio_samples(np.ones(80000, np.float32))
        t.committed_words = [SimpleNamespace(text='Old prefix', start_time=0, end_time=1)]
        t.model.transcribe.return_value = [SimpleNamespace(text='Complete original recording.', time_stamps=[])]
        with patch.object(speech_detection, 'speech_bounds', side_effect=lambda wav, sr: (0, len(wav))), \
             patch('sliding_window_transcriber.partition_window', side_effect=AlignmentError('mismatch')):
            self.assertTrue(t.step().recovery_required)
            self.assertFalse(t.should_process())
            t.add_audio_samples(np.ones(16000, np.float32) * .25)
            self.assertIsNone(t.step())
            updates = list(t.finish())
        self.assertEqual(len(updates), 1)
        self.assertTrue(updates[0].is_final)
        self.assertTrue(updates[0].recovered)
        self.assertEqual(updates[0].total_text, 'Complete original recording.')
        self.assertEqual(updates[0].committed_words, [])
        self.assertEqual(len(t.model.transcribe.call_args.kwargs['audio'][0][0]), 96000)
        self.assertFalse(t.model.transcribe.call_args.kwargs['return_time_stamps'])
        self.assertEqual(t.window_start_sample, 96000)

    def test_failed_legacy_recovery_keeps_original_buffer_and_partial_state(self):
        t = self.transcriber()
        original = np.ones(96000, np.float32)
        t.add_audio_samples(original)
        t.recovery_required = True
        t.model.transcribe.side_effect = OSError('offline')
        with patch.object(speech_detection, 'speech_bounds', side_effect=lambda wav, sr: (0, len(wav))):
            with self.assertRaisesRegex(OSError, 'offline'):
                list(t.finish())
        self.assertTrue(t.recovery_required)
        self.assertFalse(t.recovered)
        np.testing.assert_array_equal(t.audio_buffer, original)

    def transcriber(self):
        t = SlidingWindowTranscriber.__new__(SlidingWindowTranscriber)
        t.sample_rate, t.window_size_sec, t.overlap_sec, t.language = 16000, 5, 1.5, 'English'
        t.model = Mock()
        t.model.transcribe.return_value = [SimpleNamespace(text="", time_stamps=[])]
        t.reset()
        return t

    def test_backlog_finish_consumes_all_audio_with_bounded_requests(self):
        t = self.transcriber()
        t.add_audio_samples(np.zeros(16000 * 21 + 123))
        with patch.object(speech_detection, 'speech_bounds', side_effect=lambda wav, sr: (0, len(wav))):
            updates = list(t.finish())
        self.assertGreater(len(updates), 4)
        self.assertTrue(updates[-1].is_final)
        self.assertEqual(t.window_start_sample, len(t.audio_buffer))
        self.assertGreater(t.model.transcribe.call_count, 4)
        self.assertTrue(all(len(call.kwargs['audio'][0][0]) <= 80000 for call in t.model.transcribe.call_args_list))

    def test_stereo_and_nonfinite_samples_are_not_flattened(self):
        t = self.transcriber()
        for audio in [np.ones((10, 2)), np.array([float('nan')])]:
            with self.assertRaises(ValueError): t.add_audio_samples(audio)


class SpeechDetectionTests(unittest.TestCase):
    def test_silence_skips_backend(self):
        backend = Mock()
        result = speech_detection.transcribe_speech(np.zeros(16000, np.float32), 16000, backend)
        self.assertEqual(result[0].text, '')
        backend.assert_not_called()

    def test_noise_only_is_rejected_by_actual_pinned_detector(self):
        noise = np.random.default_rng(42).normal(0, .002, 16000 * 3).astype(np.float32)
        self.assertIsNone(speech_detection.speech_bounds(noise, 16000))

    def test_trimmed_speech_restores_absolute_offset(self):
        result = SimpleNamespace(text='Yes.', language='English',
                                 time_stamps=[SimpleNamespace(text='Yes', start_time=.1, end_time=.3)])
        backend = Mock(return_value=[result])
        with patch.object(speech_detection, 'speech_bounds', return_value=(16000, 32000)):
            result = speech_detection.transcribe_speech(np.ones(48000), 16000, backend)[0]
        self.assertEqual(result.time_stamps[0].start_time, 1.1)
        self.assertEqual(result.time_stamps[0].end_time, 1.3)
        self.assertEqual(len(backend.call_args.kwargs['audio'][0][0]), 16000)


if __name__ == '__main__':
    unittest.main()
