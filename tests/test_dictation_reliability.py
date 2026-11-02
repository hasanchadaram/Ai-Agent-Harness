"""Deterministic regression tests; no GPU, microphone, or keyboard input."""
import base64
import ctypes
import io
import threading
import tempfile
from pathlib import Path
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf

import pc_dictate as pc
import asr_server as server


def response(words=(), preview=(), start=0.0, end=5.0, next_start=3.0):
    committed = [{"word": w, "start": a, "end": b} for w, a, b in words]
    pending = [{"word": w, "start": a, "end": b} for w, a, b in preview]
    return {"committed_words": committed, "preview_words": pending,
            "window_start_sec": start, "window_end_sec": end,
            "next_start_sec": next_start,
            "last_committed_time_sec": committed[-1]["end"] if committed else 0.0,
            "full_text": " ".join(w["word"] for w in committed + pending)}


def ok(data):
    r = Mock(status_code=200)
    r.json.return_value = data
    return r


class ClientRegressionTests(unittest.TestCase):
    def client(self, seconds=5):
        recovery = tempfile.TemporaryDirectory()
        self.addCleanup(recovery.cleanup)
        client = pc.PcDictationClient(play_sound=False, recovery_dir=recovery.name)
        client.start_recording("RAW")
        client.audio_buffer = np.zeros(int(seconds * client.sample_rate), np.float32)
        return client

    def test_intentional_repetition_survives_window_boundary(self):
        c = self.client()
        c.committed_words = ["very"]
        c.last_committed_time_sec = 1
        with patch.object(pc.requests, "post", return_value=ok(response([("very", 1.1, 1.5)]))):
            c.process_background_window()
        self.assertEqual(c.committed_words, ["very", "very"])

    def test_preserves_proper_name_case(self):
        c = self.client()
        with patch.object(pc.requests, "post", return_value=ok(response([
            ("Ask", 0, .3), ("Alice", .4, .8), ("Monday.", .9, 1.2)]))):
            c.process_background_window()
        self.assertEqual(c.committed_words, ["Ask", "Alice", "Monday."])

    def test_final_failure_does_not_paste_partial_transcript(self):
        c = self.client()
        c.committed_words = ["Partial"]
        c.preview_words = ["tail"]
        with patch.object(pc.requests, "post", side_effect=pc.requests.Timeout("offline")), \
             patch.object(pc, "output_text") as output, patch.object(pc.time, "sleep"):
            c.stop_recording()
        output.assert_not_called()
        self.assertEqual(c.preview_words, ["tail"])

    def test_short_utterance_is_not_discarded(self):
        c = self.client(.15)
        data = response([("Yes.", 0, .1)], end=.15, next_start=.15)
        with patch.object(pc.requests, "post", return_value=ok(data)) as post, \
             patch.object(pc, "output_text"):
            c.stop_recording()
        self.assertTrue(post.called)

    def test_cleanup_empty_response_returns_raw(self):
        c = self.client()
        result = {"choices": [{"message": {"content": ""}, "finish_reason": "stop"}]}
        with patch.object(pc.requests, "post", return_value=ok(result)):
            self.assertEqual(c.polish_transcript("Keep this."), "Keep this.")

    def test_cleanup_request_disables_reasoning(self):
        c = self.client()
        result = {"choices": [{"message": {"content": "Keep this."}, "finish_reason": "stop"}]}
        with patch.object(pc.requests, "post", return_value=ok(result)) as post:
            self.assertEqual(c.polish_transcript("Keep this."), "Keep this.")
        self.assertEqual(post.call_args.kwargs["json"]["reasoning_effort"], "none")

    def test_cleanup_truncation_returns_raw(self):
        c = self.client()
        result = {"choices": [{"message": {"content": "First half"}, "finish_reason": "length"}]}
        with patch.object(pc.requests, "post", return_value=ok(result)):
            self.assertEqual(c.polish_transcript("First half and second half."), "First half and second half.")

    def test_cleanup_cannot_delete_intentional_repetition(self):
        c = self.client()
        data = {"choices": [{"message": {"content": "Please ask Alice."}, "finish_reason": "stop"}]}
        with patch.object(pc.requests, 'post', return_value=ok(data)):
            self.assertEqual(c.polish_transcript('Please, please ask Alice.'), 'Please, please ask Alice.')

    def test_cleanup_may_remove_repeated_filler(self):
        c = self.client()
        data = {"choices": [{"message": {"content": "Ask Alice."}, "finish_reason": "stop"}]}
        with patch.object(pc.requests, 'post', return_value=ok(data)):
            self.assertEqual(c.polish_transcript('Um um ask Alice.'), 'Ask Alice.')

    def test_bad_response_does_not_mutate_state(self):
        c = self.client()
        data = response([("Hello", 0, 1)])
        data["next_start_sec"] = -1
        with patch.object(pc.requests, "post", return_value=ok(data)):
            c.process_background_window()
        self.assertEqual(c.committed_words, [])
        self.assertEqual(c.window_start_sec, 0)

    def test_final_transient_error_retries_same_audio_once(self):
        c = self.client(1)
        data = response([("Complete.", .1, .9)], end=1, next_start=1)
        with patch.object(pc.requests, "post", side_effect=[pc.requests.Timeout(), ok(data)]) as post, \
             patch.object(pc, "output_text") as output, patch.object(pc.time, "sleep"):
            c.stop_recording()
        self.assertEqual(post.call_count, 2)
        self.assertEqual(post.call_args_list[0].kwargs['json'], post.call_args_list[1].kwargs['json'])
        output.assert_called_once_with('Complete.', method='clipboard')

    def test_stop_waits_for_inflight_response_and_keeps_queued_tail(self):
        c = self.client(5)
        entered, release = threading.Event(), threading.Event()
        calls = []
        def post(url, **kw):
            payload = kw['json']
            calls.append(payload)
            if not payload['is_final']:
                entered.set()
                release.wait(3)
                return ok(response([('First', 0, 3)], [('tail.', 3.2, 4)], next_start=2.5))
            return ok(response([('tail.', 3.2, 4)], start=2.5, end=5.2, next_start=5.2))
        with patch.object(pc.requests, 'post', side_effect=post), patch.object(pc, 'output_text') as output:
            worker = threading.Thread(target=c.process_background_window)
            worker.start()
            self.assertTrue(entered.wait(1))
            c.audio_callback(np.ones((3200, 1), np.float32), 3200, None, None)
            stopper = threading.Thread(target=c.stop_recording)
            stopper.start()
            time.sleep(.03)
            self.assertTrue(stopper.is_alive())
            release.set()
            worker.join(3)
            stopper.join(3)
        self.assertFalse(stopper.is_alive())
        self.assertEqual(len(c.audio_buffer), 83200)
        output.assert_called_once_with('First tail.', method='clipboard')

    def test_audio_status_error_prevents_silent_partial_output(self):
        c = self.client(1)
        c.audio_callback(np.zeros((1600, 1), np.float32), 1600, None, 'input overflow')
        data = response([('Hello.', .1, .9)], end=1.1, next_start=1.1)
        with patch.object(pc.requests, 'post', return_value=ok(data)), \
             patch.object(pc, 'output_text') as output, patch.object(c, 'save_recovery') as recovery:
            c.stop_recording()
        output.assert_not_called()
        self.assertIn('input overflow', recovery.call_args.args[0])

    def test_new_session_clears_previous_audio_state_before_capture(self):
        c = self.client()
        c.is_recording = False
        c.committed_words = ['old']
        c.audio_queue.put(np.ones(500))
        c.start_recording('CLEANUP')
        self.assertEqual(c.committed_words, [])
        self.assertEqual(len(c.audio_buffer), 0)
        self.assertTrue(c.audio_queue.empty())
        self.assertEqual(c.recording_mode, 'CLEANUP')

    def test_failed_background_request_is_throttled_without_advancing(self):
        c = self.client()
        with patch.object(pc.requests, 'post', side_effect=pc.requests.Timeout()) as post:
            c.process_background_window()
            c.process_background_window()
        self.assertEqual(post.call_count, 1)
        self.assertEqual(c.window_start_sec, 0)

    def test_all_final_backlog_requests_are_bounded(self):
        c = self.client(22)
        lengths = []
        def post(url, **kw):
            p = kw['json']
            audio, rate = sf.read(io.BytesIO(base64.b64decode(p['audio_base64'])))
            duration = len(audio) / rate
            lengths.append(duration)
            start, end = p['window_start_sec'], p['window_start_sec'] + duration
            return ok(response(start=start, end=end, next_start=end if p['is_final'] else end - 1.5))
        with patch.object(pc.requests, 'post', side_effect=post), patch.object(pc, 'output_text'):
            c.stop_recording()
        self.assertGreater(len(lengths), 4)
        self.assertLessEqual(max(lengths), 5)
        self.assertEqual(c.window_start_sec, 22)

    def test_focus_change_preserves_transcript_without_insertion(self):
        c = self.client(1)
        data = response([('Hello.', .1, .9)], end=1, next_start=1)
        with patch.object(pc.requests, 'post', return_value=ok(data)), \
             patch.object(pc.user32, 'GetForegroundWindow', return_value=(c.target_window or 0) + 1), \
             patch.object(pc, 'output_text') as output, patch.object(c, 'save_recovery') as recovery:
            c.stop_recording()
        output.assert_not_called()
        self.assertIn('Focused window changed', recovery.call_args.args[0])

    def test_invalid_configuration(self):
        for window, overlap in [(0, 1), (5, 5), (5, -1), (float('nan'), 1)]:
            with self.assertRaises(ValueError):
                pc.PcDictationClient(window_size_sec=window, overlap_sec=overlap)

    def test_interrupted_recording_is_recoverable(self):
        c = self.client(1)
        c.audio_queue.put(np.ones(1600))
        with patch.object(c, 'save_recovery') as recovery:
            c.close()
        self.assertEqual(len(c.audio_buffer), 17600)
        self.assertTrue(c._shutdown.is_set())
        recovery.assert_called_once()

    def test_worker_recovers_after_unexpected_exception(self):
        c = self.client()
        survived = threading.Event()
        def process():
            if not survived.is_set():
                survived.set()
                raise RuntimeError('unexpected fault')
            c._shutdown.set()
        with patch.object(c, 'process_background_window', side_effect=process):
            worker = threading.Thread(target=c.audio_worker)
            worker.start()
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertTrue(survived.is_set())

    def test_recovery_contains_complete_accepted_audio_and_unicode_text(self):
        c = self.client(1)
        c.committed_words = ['Café,']
        c.preview_words = ['tail.']
        path = c.save_recovery('offline')
        samples, rate = sf.read(str(path) + '.wav')
        self.assertEqual(len(samples), 16000)
        self.assertEqual(rate, 16000)
        self.assertIn('Café,', Path(str(path) + '.txt').read_text(encoding='utf-8'))
        self.assertFalse(c._needs_recovery)

    def test_failed_recovery_cannot_be_discarded_by_new_session(self):
        c = self.client(1)
        c.is_recording = False
        with patch.object(pc.sf, 'write', side_effect=OSError('disk full')):
            with self.assertRaises(RuntimeError):
                c.start_recording('RAW')
        self.assertEqual(len(c.audio_buffer), 16000)

    def test_next_session_waits_for_previous_finalization(self):
        c = self.client(1)
        entered, release = threading.Event(), threading.Event()
        def post(*args, **kw):
            entered.set()
            release.wait(2)
            return ok(response([('Done.', .1, .9)], end=1, next_start=1))
        with patch.object(pc.requests, 'post', side_effect=post), patch.object(pc, 'output_text') as output:
            stopper = threading.Thread(target=c.stop_recording)
            stopper.start()
            self.assertTrue(entered.wait(1))
            starter = threading.Thread(target=c.start_recording, args=('CLEANUP',))
            starter.start()
            time.sleep(.02)
            self.assertTrue(starter.is_alive())
            release.set()
            stopper.join(2)
            starter.join(2)
        output.assert_called_once_with('Done.', method='clipboard')
        self.assertTrue(c.is_recording)
        self.assertEqual(c.committed_words, [])


class OutputTests(unittest.TestCase):
    def test_held_modifiers_block_insertion(self):
        with patch.object(pc, 'wait_for_all_modifiers_released', return_value=False), \
             patch.object(pc, 'paste_ephemeral_clipboard') as paste:
            self.assertFalse(pc.output_text('hello'))
        paste.assert_not_called()

    def test_rejected_unicode_input_reports_failure_without_paste_retry(self):
        with patch.object(pc, 'wait_for_all_modifiers_released', return_value=True), \
             patch.object(pc.user32, 'SendInput', return_value=0), \
             patch.object(pc, 'paste_ephemeral_clipboard') as paste:
            self.assertFalse(pc.output_text('hello', method='sendinput'))
        paste.assert_not_called()

    def test_non_bmp_unicode_uses_surrogate_pairs(self):
        scans = []
        def send(count, entries, size):
            scans.extend(entries[i].ki.wScan for i in range(count))
            return count
        with patch.object(pc.user32, 'SendInput', side_effect=send), patch.object(pc.time, 'sleep'):
            self.assertTrue(pc.type_text_sendinput('😀'))
        self.assertEqual(scans, [0xD83D, 0xD83D, 0xDE00, 0xDE00])

    def test_unsupported_clipboard_never_falls_back_to_typing_partial_text(self):
        with patch.object(pc, 'snapshot_clipboard', return_value=None), \
             patch.object(pc, 'type_text_sendinput', return_value=True) as direct, \
             patch.object(pc, 'set_clipboard_text') as write:
            self.assertFalse(pc.paste_ephemeral_clipboard('Why is it not working properly?'))
        direct.assert_not_called()
        write.assert_not_called()

    def test_ole_bookkeeping_and_empty_marker_do_not_disable_plain_or_rich_text_paste(self):
        formats = [49161, 13, 49346, 49171, 49347]
        names = {49161: 'DataObject', 49346: 'EnterpriseDataProtectionId',
                 49171: 'Ole Private Data', 49347: 'HTML Format'}
        for marker in (None, b'nonempty-protection-id'):
            with self.subTest(marker_present=marker is not None):
                content = {13: 'Original clipboard\0'.encode('utf-16-le'),
                           49347: b'<b>Original clipboard</b>\0', 49346: marker}
                buffers = {fmt: ctypes.create_string_buffer(data) for fmt, data in content.items() if data}
                def enum(previous):
                    return formats[0] if previous == 0 else next(iter(formats[formats.index(previous) + 1:]), 0)
                def name(fmt, output, size):
                    output.value = names.get(fmt, '')
                    return len(output.value)
                with patch.object(pc.user32, 'OpenClipboard', return_value=True), \
                     patch.object(pc.user32, 'CloseClipboard'), \
                     patch.object(pc.user32, 'EnumClipboardFormats', side_effect=enum), \
                     patch.object(pc.user32, 'GetClipboardFormatNameW', side_effect=name), \
                     patch.object(pc.user32, 'GetClipboardData', side_effect=lambda fmt: fmt if content.get(fmt) else 0) as read, \
                     patch.object(pc.kernel32, 'GlobalSize', side_effect=lambda handle: len(content[handle])), \
                     patch.object(pc.kernel32, 'GlobalLock', side_effect=lambda handle: ctypes.addressof(buffers[handle])), \
                     patch.object(pc.kernel32, 'GlobalUnlock'):
                    snapshot = pc.snapshot_clipboard()
                self.assertEqual(snapshot, {fmt: data for fmt, data in content.items() if data})
                self.assertNotIn(49161, [call.args[0] for call in read.call_args_list])
                self.assertNotIn(49171, [call.args[0] for call in read.call_args_list])

    def test_atomic_paste_keeps_every_character_and_negation(self):
        for text in ('Because right now, it is giving me some Hindi words in the transcript output sometimes.',
                     'Why is it not working properly?', 'Keep ₹50.\nDo not change iPhone or NASA.'):
            with self.subTest(text=text), \
                 patch.object(pc, 'snapshot_clipboard', return_value={13: b'original'}), \
                 patch.object(pc, 'set_clipboard_text', return_value=True) as write, \
                 patch.object(pc, 'restore_clipboard', return_value=True) as restore, \
                 patch.object(pc.user32, 'GetClipboardSequenceNumber', return_value=7), \
                 patch.object(pc.user32, 'SendInput', return_value=4) as send, \
                 patch.object(pc, 'type_text_sendinput') as type_characters, \
                 patch.object(pc.time, 'sleep'):
                self.assertTrue(pc.paste_ephemeral_clipboard(text))
            write.assert_called_once_with(text)
            self.assertEqual(send.call_count, 1)
            self.assertEqual(send.call_args.args[0], 4)  # One Ctrl+V, never per-letter events.
            type_characters.assert_not_called()
            restore.assert_called_once_with({13: b'original'}, 7)

    def test_explicit_unicode_typing_is_paced_for_editors(self):
        with patch.object(pc.user32, 'GetForegroundWindow', return_value=7), \
             patch.object(pc.user32, 'SendInput', return_value=2), \
             patch.object(pc.time, 'sleep') as delay:
            self.assertTrue(pc.type_text_sendinput('No'))
        self.assertEqual([call.args[0] for call in delay.call_args_list], [.025, .025])

    def test_new_user_copy_is_not_overwritten_during_restoration(self):
        with patch.object(pc.user32, 'OpenClipboard', return_value=True), \
             patch.object(pc.user32, 'CloseClipboard'), \
             patch.object(pc.user32, 'GetClipboardSequenceNumber', return_value=101), \
             patch.object(pc.user32, 'EmptyClipboard') as empty:
            self.assertTrue(pc.restore_clipboard({13: b'old'}, expected_sequence=100))
        empty.assert_not_called()


class PunctuationTests(unittest.TestCase):
    def test_unicode_and_repeated_words(self):
        items = [{"word": w, "start": i, "end": i + 1} for i, w in enumerate(["Café", "very", "very", "good"])]
        server.attach_punctuation("Café, very, very good!", items)
        self.assertEqual([w["word"] for w in items], ["Café,", "very,", "very", "good!"])


if __name__ == "__main__":
    unittest.main()
