import asyncio
import base64
import io
import json
import re
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
import numpy as np
import soundfile as sf

import mobile_streaming as mobile
import pc_dictate


class Detector:
    frame_size = 512
    def __init__(self, values=None):
        self.values = iter(values) if values is not None else None
    def probability(self, frame):
        return next(self.values) if self.values is not None else (1.0 if frame[0] else 0.0)


def pcm(samples, value=1000):
    return np.full(samples, value, dtype='<i2').tobytes()


class AudioTests(unittest.TestCase):
    def test_context_preserves_normal_pauses_and_original_audio(self):
        audio = mobile.StreamingAudio(Detector())
        original = pcm(32768) + pcm(16384, 0) + pcm(8192, 2000)
        audio.append(original); audio.finish()
        context = mobile.RecognitionAudio(audio)
        position = context.position()
        self.assertEqual(context.snapshot(0, position.samples), original)
        self.assertEqual(audio.snapshot(0, audio.samples), original)

    def test_context_removes_only_excess_quiet_and_maps_final_coverage(self):
        audio = mobile.StreamingAudio(Detector())
        original = pcm(32768) + pcm(320000, 0) + pcm(8192, 2000)
        audio.append(original); audio.finish()
        context = mobile.RecognitionAudio(audio)
        position = context.position()
        data = np.frombuffer(context.snapshot(0, position.samples), dtype='<i2')
        self.assertEqual(np.count_nonzero(data == 1000), 32768)
        self.assertEqual(np.count_nonzero(data == 2000), 8192)
        self.assertLess(position.samples, mobile.WINDOW_SAMPLES)
        self.assertEqual(context.raw_offset(position.samples), audio.samples)
        self.assertEqual(audio.snapshot(0, audio.samples), original)

    def test_mobile_silero_hysteresis_and_320ms_pause(self):
        audio = mobile.StreamingAudio(Detector([.5, .31, .29] + [0.] * 9))
        audio.append(pcm(512 * 12))
        self.assertEqual(audio.last_speech, 1024)
        self.assertEqual(audio.pause, 6144)
        self.assertEqual(audio.pauses, [6144])
        self.assertTrue(audio.has_speech(0, 1024))
        self.assertFalse(audio.has_speech(1024, 6144))

    def test_five_minute_limit_and_exact_pcm(self):
        audio = mobile.StreamingAudio(Detector(), max_samples=1000)
        source = pcm(1100)
        self.assertEqual(audio.append(source), 1000)
        self.assertEqual(audio.snapshot(0, 1000), source[:2000])
        self.assertEqual(audio.append(source), 0)
        self.assertEqual(mobile.MAX_SAMPLES, 16000 * 300)

    def test_frame_state_is_session_local(self):
        a = mobile.StreamingAudio(Detector([1.0]))
        b = mobile.StreamingAudio(Detector([0.0]))
        a.append(pcm(512)); b.append(pcm(512))
        self.assertTrue(a.has_speech(0, 512))
        self.assertFalse(b.has_speech(0, 512))


class AgreementTests(unittest.TestCase):
    def test_overlap_reuses_continuing_sentence_case_at_fresh_window_start(self):
        self.assertEqual(mobile.overlap_join('Because right now it is giving me some Hindi words',
            'Some Hindi words in the transcript output sometimes.'),
            ('Because right now it is giving me', 'some Hindi words in the transcript output sometimes.'))
        self.assertEqual(mobile.overlap_join('Some Hindi words in the transcript',
            'In the transcript output sometimes.'), ('Some Hindi words', 'in the transcript output sometimes.'))

    def test_overlap_preserves_sentence_starts_names_acronyms_quotes_and_pronoun_i(self):
        for previous, current in [
            ('End of sentence. Some Hindi words', 'Some Hindi words remain.'),
            ('Ask Alice about the transcript', 'Alice about the transcript output.'),
            ('Ask NASA about the transcript', 'NASA about the transcript output.'),
            ('Yesterday I checked the transcript', 'I checked the transcript output.'),
            ('He said " In the transcript', 'In the transcript output.'),
        ]:
            with self.subTest(previous=previous):
                self.assertEqual(mobile.overlap_join(previous, current)[1], current)

    def test_overlap_uses_new_punctuation_and_keeps_real_sentence_breaks(self):
        self.assertEqual(mobile.overlap_join('Because right now it is giving me.',
            'it is giving me some Hindi words.'), ('Because right now', 'it is giving me some Hindi words.'))
        self.assertEqual(mobile.overlap_join('First sentence. The next sentence is',
            'The next sentence is complete.'), ('First sentence.', 'The next sentence is complete.'))

    def test_mobile_repeated_and_partial_overlap_examples(self):
        self.assertEqual(mobile.overlap_join('Please please send the revised docu',
                                            'fragment send the revised document today'),
                         ('Please please', 'send the revised document today'))
        self.assertIsNone(mobile.overlap_join('very very very', 'very very'))
        self.assertIsNone(mobile.overlap_join('Please send the revised', 'An unrelated sentence entirely'))

    def test_mobile_local_agreement_holds_back_recent_ending(self):
        agreement = mobile.LocalAgreement2()
        agreement.update('I am try'); self.assertEqual(agreement.stable, '')
        agreement.update('I am trying to'); self.assertEqual(agreement.stable, 'I')
        agreement.update('I am trying to book'); self.assertEqual(agreement.stable, 'I am')
        agreement.update('I was trying to book'); self.assertEqual(agreement.stable, 'I')

    def test_mobile_defaults_and_cleanup_prompt_remain_in_sync(self):
        root = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/java/com/companion/ainotifications/dictation'
        worker = (root / 'StreamingTranscriber.kt').read_text(encoding='utf-8')
        capture = (root / 'StreamingAudio.kt').read_text(encoding='utf-8')
        detector = (root / 'SileroSpeechDetector.kt').read_text(encoding='utf-8')
        protocol = (root / 'DictationProtocol.kt').read_text(encoding='utf-8')
        client = (root / 'DictationClient.kt').read_text(encoding='utf-8')
        def number(source, pattern):
            return int(re.search(pattern, source).group(1).replace('_', ''))
        self.assertEqual(mobile.SAMPLE_RATE, number(protocol, r'SAMPLE_RATE\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.MAX_SAMPLES, mobile.SAMPLE_RATE * number(protocol, r'MAX_SECONDS\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.FRAME_SAMPLES, number(detector, r'frameSize\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.SILENCE_SAMPLES, number(capture, r'silenceMs:\s*Int\s*=\s*([\d_]+)') * 16)
        self.assertEqual(mobile.MINIMUM_SAMPLES, number(worker, r'minimumSamples:\s*Int\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.WINDOW_SAMPLES, number(worker, r'speculativeLimitSamples:\s*Int\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.OVERLAP_SAMPLES, number(worker, r'overlap\s*=\s*minOf\(([\d_]+)'))
        self.assertEqual(mobile.PRE_ROLL_SAMPLES, number(worker, r'end\s*-\s*([\d_]+)\)'))
        self.assertEqual(mobile.INTERVAL_SEC * 1000, number(worker, r'intervalMs:\s*Long\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.BACKGROUND_TIMEOUT_SEC * 1000, number(worker, r'backgroundTimeoutMs:\s*Long\s*=\s*([\d_]+)'))
        self.assertEqual(mobile.RETRY_DELAY_SEC * 1000, number(worker, r'retryDelayMs:\s*Long\s*=\s*([\d_]+)'))
        content = client.split('put("content", ', 1)[1].split('addJsonObject { put("role", "user")', 1)[0]
        prompt = ''.join(re.findall(r'"([^"\n]*)"', content))
        self.assertEqual(pc_dictate.CLEANUP_SYSTEM_PROMPT, prompt)


class EngineTests(unittest.IsolatedAsyncioTestCase):
    async def test_final_overlap_keeps_one_sentence_with_mid_sentence_lowercase(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(24000)); audio.finish()
        answers = iter(['Because right now it is giving me some Hindi words',
                        'Some Hindi words in the transcript output sometimes.'])
        async def asr(data): return next(answers)
        result = await mobile.StreamingTranscriber(audio, asr, limit=16000).run()
        self.assertEqual(result, 'Because right now it is giving me some Hindi words in the transcript output sometimes.')

    async def test_short_command_waits_for_context_during_recording_but_flushes_on_stop(self):
        audio = mobile.StreamingAudio(Detector())
        calls = []
        async def asr(data):
            calls.append(data)
            return 'Yes.'
        task = asyncio.create_task(mobile.StreamingTranscriber(audio, asr).run())
        audio.append(pcm(12800) + pcm(5120, 0))
        await asyncio.sleep(.05)
        self.assertEqual(calls, [])
        audio.finish()
        self.assertEqual(await asyncio.wait_for(task, 1), 'Yes.')
        self.assertEqual(len(calls), 1)

    async def test_hesitations_do_not_commit_or_reset_and_final_repairs_punctuation(self):
        audio = mobile.StreamingAudio(Detector())
        calls, progress = asyncio.Queue(), asyncio.Queue()
        async def asr(data):
            answer = asyncio.get_running_loop().create_future()
            await calls.put((data, answer))
            return await answer
        engine = mobile.StreamingTranscriber(audio, asr, interval=.001, on_progress=progress.put_nowait)
        task = asyncio.create_task(engine.run())
        original = pcm(32768) + pcm(5120, 0)
        audio.append(original)
        data, answer = await asyncio.wait_for(calls.get(), 1)
        self.assertEqual(data, original)
        answer.set_result('Because right now it is giving me.')
        while (await asyncio.wait_for(progress.get(), 1))['completed'] != 1:
            pass
        self.assertEqual(engine.sections, [])
        self.assertEqual(engine.start, 0)
        tail = pcm(8192, 2000) + pcm(16000, 0) + pcm(4096, 3000)
        audio.append(tail); audio.finish()
        data, answer = await asyncio.wait_for(calls.get(), 1)
        self.assertEqual(data, original + tail)
        expected = 'Because right now it is giving me some Hindi words in the transcript output sometimes.'
        answer.set_result(expected)
        self.assertEqual(await asyncio.wait_for(task, 1), expected)
        self.assertEqual(engine.sections, [expected])

    async def test_long_thinking_pause_keeps_acoustic_context_instead_of_a_tiny_tail(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(32768) + pcm(320000, 0) + pcm(8192, 2000)); audio.finish()
        calls = []
        async def asr(data):
            calls.append(data)
            values = np.frombuffer(data, dtype='<i2')
            self.assertIn(1000, values)
            self.assertIn(2000, values)
            return 'I paused while thinking and continued.'
        states = []
        result = await mobile.StreamingTranscriber(audio, asr, on_progress=states.append).run()
        self.assertEqual(result, 'I paused while thinking and continued.')
        self.assertEqual(len(calls), 1)
        self.assertLessEqual(len(calls[0]) // 2, mobile.WINDOW_SAMPLES)
        self.assertEqual(states[-1]['finalized_samples'], audio.samples)

    async def test_timeout_before_stop_retries_same_audio_as_final(self):
        audio = mobile.StreamingAudio(Detector())
        entered = asyncio.Event()
        calls = []
        async def asr(data):
            calls.append(data)
            if len(calls) == 1:
                entered.set()
                await asyncio.sleep(5)
            return 'After timeout.'
        engine = mobile.StreamingTranscriber(audio, asr, timeout=.05, retry_delay=.01, interval=.01, minimum=12800)
        task = asyncio.create_task(engine.run())
        audio.append(pcm(16000))
        await asyncio.wait_for(entered.wait(), 1)
        audio.finish()
        self.assertEqual(await asyncio.wait_for(task, 2), 'After timeout.')
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(engine.failures, 1)

    async def test_stop_reuses_an_exact_successful_request(self):
        audio = mobile.StreamingAudio(Detector())
        completed = asyncio.Event()
        calls = []
        async def asr(data):
            calls.append(data)
            return 'Already complete.'
        engine = mobile.StreamingTranscriber(audio, asr, interval=2, minimum=12800,
            on_progress=lambda p: completed.set() if p['completed'] else None)
        task = asyncio.create_task(engine.run())
        audio.append(pcm(12800))
        await asyncio.wait_for(completed.wait(), 1)
        audio.finish()
        self.assertEqual(await asyncio.wait_for(task, 1), 'Already complete.')
        self.assertEqual(len(calls), 1)
        self.assertEqual(engine.reused, 1)

    async def test_queued_long_audio_is_drained_with_mobile_windows(self):
        audio = mobile.StreamingAudio(Detector())
        for value in range(1, 81):
            audio.append(pcm(4000, value))
        audio.finish()
        ranges = []
        async def asr(data):
            values = np.frombuffer(data, dtype='<i2')
            ids = [int(values[0])] + [int(values[i]) for i in range(1, len(values)) if values[i] != values[i-1]]
            ranges.append((ids[0], len(values)))
            return ' '.join(f'word{value}' for value in ids)
        engine = mobile.StreamingTranscriber(audio, asr, interval=.001)
        result = await asyncio.wait_for(engine.run(), 5)
        self.assertEqual(result, ' '.join(f'word{i}' for i in range(1, 81)))
        self.assertTrue(all(size <= 128000 for _, size in ranges))
        self.assertGreater(engine.rolls, 0)
        self.assertEqual(engine.resolved, audio.samples)

    async def test_no_speech_is_not_fabricated(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(16384, 0)); audio.finish()
        calls = []
        async def asr(data):
            calls.append(data)
            return 'hallucination'
        engine = mobile.StreamingTranscriber(audio, asr)
        with self.assertRaisesRegex(ValueError, 'No speech'):
            await engine.run()
        self.assertEqual(calls, [])

    async def test_mobile_subframe_tail_is_sent_for_safe_final_check(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(100, 0)); audio.finish()
        calls = []
        async def asr(data):
            calls.append(data)
            return ''
        with self.assertRaisesRegex(ValueError, 'No speech'):
            await mobile.StreamingTranscriber(audio, asr).run()
        self.assertEqual(len(calls), 1)

    async def test_final_failure_does_not_commit_partial_transcript(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(16000)); audio.finish()
        async def asr(data): raise OSError('offline')
        engine = mobile.StreamingTranscriber(audio, asr)
        with self.assertRaisesRegex(OSError, 'offline'):
            await engine.run()
        self.assertEqual(engine.sections, [])
        self.assertEqual(engine.resolved, 0)

    async def test_unmatched_overlap_fails_safely_after_wider_retry(self):
        audio = mobile.StreamingAudio(Detector())
        audio.append(pcm(32000)); audio.finish()
        answers = iter(['Please bring the blue notebook', 'Entirely unrelated text', 'Still unrelated text'])
        async def asr(data): return next(answers)
        engine = mobile.StreamingTranscriber(audio, asr, limit=16000)
        with self.assertRaisesRegex(OSError, 'reliably join'):
            await asyncio.wait_for(engine.run(), 3)
        self.assertLessEqual(engine.max_request_samples, 16000)


class RequestTests(unittest.IsolatedAsyncioTestCase):
    def test_accepts_both_pc_root_and_mobile_v1_urls(self):
        self.assertEqual(mobile.completion_url('http://localhost:8000'), 'http://localhost:8000/v1/chat/completions')
        self.assertEqual(mobile.completion_url('https://pc.example/v1/'), 'https://pc.example/v1/chat/completions')

    async def test_transcription_payload_and_pcm_match_mobile(self):
        requests = []
        def respond(request):
            body = json.loads(request.content)
            requests.append(body)
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop',
                'message': {'content': 'language English<asr_text>Very, very good.<|im_end|>'}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            self.assertEqual(await mobile.transcribe(client, 'http://test', pcm(100)), 'Very, very good.')
        body = requests[0]
        self.assertEqual((body['temperature'], body['max_tokens'], body['stream']), (0, 4096, False))
        self.assertNotIn('return_time_stamps', body)
        self.assertEqual(body['language'], 'English')
        encoded = body['messages'][0]['content'][0]['input_audio']['data']
        wav, rate = sf.read(io.BytesIO(base64.b64decode(encoded)), dtype='int16')
        self.assertEqual(rate, 16000)
        self.assertEqual(wav.tobytes(), pcm(100))

    async def test_cleanup_uses_mobile_settings_and_disables_thinking(self):
        calls = []
        def respond(request):
            calls.append(json.loads(request.content))
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {'content': 'Text.'}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            self.assertEqual(await mobile.polish(client, 'http://test', 'model', 'prompt', 'text'), 'Text.')
        self.assertEqual(calls[0]['max_tokens'], 4096)
        self.assertEqual(calls[0]['reasoning_effort'], 'none')
        self.assertEqual(calls[0]['chat_template_kwargs'], {'enable_thinking': False})

    async def test_transient_failure_retries_but_truncation_does_not(self):
        statuses = [500, 200]
        calls = []
        def respond(request):
            calls.append(json.loads(request.content))
            status = statuses.pop(0)
            return httpx.Response(status, json={'choices': [{'finish_reason': 'stop', 'message': {'content': 'OK'}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            with patch.object(mobile.asyncio, 'sleep', return_value=None):
                self.assertEqual(await mobile.transcribe(client, 'http://test', pcm(10)), 'OK')
        self.assertEqual(calls[0], calls[1])
        def truncated(request):
            return httpx.Response(200, json={'choices': [{'finish_reason': 'length', 'message': {'content': 'partial'}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(truncated)) as client:
            with self.assertRaisesRegex(mobile.ModelError, 'output limit'):
                await mobile.transcribe(client, 'http://test', pcm(10))


if __name__ == '__main__':
    unittest.main()
