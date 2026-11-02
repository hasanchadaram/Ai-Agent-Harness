import asyncio
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf

import mobile_streaming as mobile
import pc_dictate_v2 as v2


class Speech:
    frame_size = 512
    def probability(self, frame): return 1.0


class PcV2Tests(unittest.TestCase):
    def test_original_launch_command_uses_the_shared_controller(self):
        with patch('sys.argv', ['pc_dictate.py', '--no-sound']), \
             patch.object(v2.win, 'SingleDictationInstance') as guard, \
             patch.object(v2, 'PcDictationV2') as factory:
            guard.return_value.__enter__.return_value = True
            v2.win.main()
        factory.assert_called_once_with(asr_url='http://127.0.0.1:8000',
            cleanup_url='http://127.0.0.1:8080', play_sound=False, paste_method='clipboard')
        factory.return_value.run.assert_called_once()
        factory.return_value.close.assert_called_once()

    def test_both_entrypoints_refuse_a_second_instance_before_creating_client(self):
        for main in (v2.win.main, v2.main):
            with self.subTest(main=main.__module__), \
                 patch('sys.argv', ['dictation', '--no-sound']), \
                 patch.object(v2.win, 'SingleDictationInstance') as guard, \
                 patch.object(v2, 'PcDictationV2') as factory:
                guard.return_value.__enter__.return_value = False
                main()
                factory.assert_not_called()

    def test_single_instance_guard_is_held_until_client_cleanup_completes(self):
        events = []
        class Guard:
            def __enter__(self): events.append('acquire'); return True
            def __exit__(self, *_): events.append('release')
        client = Mock()
        client.run.side_effect = lambda: events.append('run')
        client.close.side_effect = lambda: events.append('close')
        def create(): events.append('create'); return client
        with patch.object(v2.win, 'SingleDictationInstance', Guard):
            v2.win.run_single_instance(create)
        self.assertEqual(events, ['acquire', 'create', 'run', 'close', 'release'])

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.client = v2.PcDictationV2(play_sound=False, recovery_dir=self.directory.name)
        self.audio_patch = patch.object(v2, 'StreamingAudio', side_effect=lambda: mobile.StreamingAudio(Speech()))
        self.audio_patch.start()
        self.addCleanup(self.audio_patch.stop)

    def test_hotkeys_choose_raw_or_cleanup_and_allow_shift_upgrade(self):
        states = [
            {v2.win.VK_CONTROL, v2.win.VK_LWIN}, set(),
            {v2.win.VK_CONTROL, v2.win.VK_LWIN, v2.win.VK_SHIFT}, set(),
            {v2.win.VK_CONTROL, v2.win.VK_LWIN},
            {v2.win.VK_CONTROL, v2.win.VK_LWIN, v2.win.VK_SHIFT}, set(),
        ]
        current = [-1]
        completed = []

        def next_poll(_):
            current[0] += 1
            if current[0] == len(states):
                raise KeyboardInterrupt

        def start(mode):
            self.client.recording_mode = mode

        with patch.object(v2.sd, 'InputStream'), \
             patch.object(v2.time, 'sleep', side_effect=next_poll), \
             patch.object(v2.win, 'is_pressed', side_effect=lambda key: key in states[current[0]]), \
             patch.object(v2.win, 'mask_start_menu'), \
             patch.object(self.client, 'start_recording', side_effect=start), \
             patch.object(self.client, 'stop_recording', side_effect=lambda: completed.append(self.client.recording_mode)):
            with self.assertRaises(KeyboardInterrupt):
                self.client.run()
        self.assertEqual(completed, ['RAW', 'CLEANUP', 'CLEANUP'])

    def test_captures_and_outputs_once_after_release(self):
        self.assertEqual((self.client.window_size_sec, self.client.overlap_sec), (8.0, 3.0))
        self.assertFalse(self.client.default_cleanup)
        async def transcribe(client, url, data): return 'Complete transcript.'
        with patch.object(v2, 'transcribe', side_effect=transcribe), \
             patch.object(v2.win, 'output_text') as output, \
             patch.object(v2.win.user32, 'GetForegroundWindow', return_value=123):
            self.client.start_recording('RAW')
            self.client.audio_callback(np.ones((16000, 1), np.int16), 16000, None, None)
            self.client.stop_recording()
        output.assert_called_once_with('Complete transcript.', method='clipboard')
        self.assertEqual(self.client.audio.position().samples, 16000)

    def test_final_failure_saves_all_accepted_audio_without_pasting(self):
        async def transcribe(client, url, data): raise OSError('offline')
        with patch.object(v2, 'transcribe', side_effect=transcribe), \
             patch.object(v2.win, 'output_text') as output:
            self.client.start_recording('RAW')
            self.client.audio_callback(np.ones((16000, 1), np.int16), 16000, None, None)
            self.client.stop_recording()
        output.assert_not_called()
        recordings = list(Path(self.directory.name).glob('*.wav'))
        self.assertEqual(len(recordings), 1)
        samples, rate = sf.read(recordings[0])
        self.assertEqual((len(samples), rate), (16000, 16000))

    def test_stop_waits_for_inflight_processing_and_flushes_tail(self):
        entered = threading.Event()
        release = threading.Event()
        calls = []
        async def transcribe(client, url, data):
            calls.append(len(data) // 2)
            if len(calls) == 1:
                entered.set()
                await asyncio.to_thread(release.wait, 3)
            return 'Complete transcript.'
        with patch.object(v2, 'transcribe', side_effect=transcribe), \
             patch.object(v2.win, 'output_text') as output, \
             patch.object(v2.win.user32, 'GetForegroundWindow', return_value=123):
            self.client.start_recording('RAW')
            self.client.audio_callback(np.ones((32000, 1), np.int16), 32000, None, None)
            self.assertTrue(entered.wait(2))
            self.client.audio_callback(np.ones((1600, 1), np.int16), 1600, None, None)
            stopper = threading.Thread(target=self.client.stop_recording)
            stopper.start()
            time.sleep(.03)
            self.assertTrue(stopper.is_alive())
            release.set()
            stopper.join(5)
        self.assertFalse(stopper.is_alive())
        self.assertEqual(self.client.audio.position().samples, 33600)
        output.assert_called_once_with('Complete transcript.', method='clipboard')

    def test_cleanup_uses_mobile_model_request_and_no_insertion_after_failure(self):
        async def transcribe(client, url, data): return 'Original transcript.'
        async def polish(*args): raise OSError('cleanup offline')
        with patch.object(v2, 'transcribe', side_effect=transcribe), \
             patch.object(v2, 'polish', side_effect=polish), \
             patch.object(v2.win, 'output_text') as output:
            self.client.start_recording('CLEANUP')
            self.client.audio_callback(np.ones((16000, 1), np.int16), 16000, None, None)
            self.client.stop_recording()
        output.assert_not_called()
        notes = list(Path(self.directory.name).glob('*.txt'))
        self.assertEqual(len(notes), 1)
        self.assertIn('Original transcript.', notes[0].read_text(encoding='utf-8'))

    def test_capture_finishes_at_mobile_limit_before_hotkey_release(self):
        async def transcribe(client, url, data): return 'Limit reached.'
        tiny_audio = mobile.StreamingAudio(Speech(), max_samples=512)
        with patch.object(v2, 'StreamingAudio', return_value=tiny_audio), \
             patch.object(v2, 'MAX_SAMPLES', 512), \
             patch.object(v2, 'transcribe', side_effect=transcribe), \
             patch.object(v2.win, 'output_text') as output, \
             patch.object(v2.win.user32, 'GetForegroundWindow', return_value=123):
            self.client.start_recording('RAW')
            self.client.audio_callback(np.ones((700, 1), np.int16), 700, None, None)
            self.assertTrue(self.client.limit_reached.wait(2))
            self.client.ingest_thread.join(2)
            self.assertTrue(tiny_audio.position().finished)
            self.assertEqual(tiny_audio.position().samples, 512)
            self.client.stop_recording()
        output.assert_called_once_with('Limit reached.', method='clipboard')

    def test_interrupt_while_finalizing_keeps_audio_and_cancels_request(self):
        entered = threading.Event()
        async def transcribe(client, url, data):
            entered.set()
            await asyncio.sleep(30)
        with patch.object(v2, 'transcribe', side_effect=transcribe):
            self.client.start_recording('RAW')
            self.client.audio_callback(np.ones((32000, 1), np.int16), 32000, None, None)
            self.assertTrue(entered.wait(2))
            # Reproduce a Ctrl+C after stop has disabled capture and queued the flush.
            with self.client._capture_lock:
                self.client.is_recording = False
                self.client.audio_queue.put(None)
            started = time.monotonic()
            self.client.close()
        self.assertLess(time.monotonic() - started, 3)
        self.assertEqual(len(list(Path(self.directory.name).glob('*.wav'))), 1)


class WindowsInstanceTests(unittest.TestCase):
    def test_actual_cli_entrypoints_exit_without_opening_a_second_hotkey_loop(self):
        root = Path(__file__).resolve().parents[1]
        with v2.win.SingleDictationInstance():
            # Whether this test or the daily-use app owns the object, both
            # commands must refuse it before opening a microphone or hotkeys.
            for script in ('pc_dictate.py', 'pc_dictate_v2.py'):
                with self.subTest(script=script):
                    result = subprocess.run([sys.executable, str(root / script), '--no-sound'],
                        cwd=root, capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn('PC dictation is already running.', result.stdout)
                    self.assertNotIn('RECORDING STARTED', result.stdout)

    def test_actual_named_mutex_blocks_another_process_and_releases_after_close(self):
        name = 'Local\\AiNotifications.Test.' + uuid.uuid4().hex
        code = ('import sys; from pc_dictate import SingleDictationInstance\n'
                'with SingleDictationInstance(sys.argv[1]) as acquired: print(acquired)\n')
        def child():
            result = subprocess.run([sys.executable, '-c', code, name],
                cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()
        with v2.win.SingleDictationInstance(name) as first:
            self.assertTrue(first)
            with v2.win.SingleDictationInstance(name) as duplicate:
                self.assertFalse(duplicate)
            self.assertEqual(child(), 'False')
        self.assertEqual(child(), 'True')

    def test_actual_named_mutex_does_not_leave_a_stale_lock_after_a_crash(self):
        name = 'Local\\AiNotifications.Test.' + uuid.uuid4().hex
        code = ('import os,sys; from pc_dictate import SingleDictationInstance\n'
                'with SingleDictationInstance(sys.argv[1]) as acquired:\n'
                ' assert acquired\n'
                ' os._exit(0)\n')
        result = subprocess.run([sys.executable, '-c', code, name],
            cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        with v2.win.SingleDictationInstance(name) as acquired:
            self.assertTrue(acquired)


if __name__ == '__main__':
    unittest.main()
