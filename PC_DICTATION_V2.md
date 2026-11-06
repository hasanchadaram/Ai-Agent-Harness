# PC dictation v2

Version 2 uses the phone's background dictation algorithm on Windows. The original [pc_dictate.py](./pc_dictate.py) launch command now starts the same controller, so both launch commands receive Qwen segmentation fixes. Its legacy alignment class remains importable for old replay tools. The processing port is in [mobile_streaming.py](./mobile_streaming.py); [pc_dictate_v2.py](./pc_dictate_v2.py) supplies Windows capture, hotkeys, output, and recovery.

An optional [growing-window strategy](./GROWING_WINDOW_DICTATION.md) compares consecutive results from the same audio start and advances using confirmed word times. It has a separate implementation, launcher, Android switch, tests, and measured defaults. The existing overlap strategy remains the default; select `--streaming-strategy growing` to test the alternative.

## Run

Open a **new** Command Prompt or PowerShell window and run:

```powershell
pc-dictate-v2
```

The launcher [pc-dictate-v2.cmd](./bin/pc-dictate-v2.cmd) is in `C:\AI\Ai-Notifications\bin`, which was added to the current user's PATH. It uses the existing Qwen Python environment regardless of the working directory. Existing terminals may need to be reopened to receive the updated PATH. [run_pc_dictate_v2.bat](./run_pc_dictate_v2.bat) is also available directly.

Both launch commands share a Windows session mutex. A second launch prints “PC dictation is already running” and exits before creating a client, opening a microphone, or listening for hotkeys. Close the running console before starting a replacement. The OS releases the lock when the process exits or crashes. Older running versions must be closed once to load this guard.

- Hold **Ctrl + Windows** for raw dictation; release to insert.
- Add **Shift**, including during a raw recording, to enable cleanup for that recording. Use `pc-dictate-v2 --cleanup` if you prefer cleanup without Shift, matching the phone's default.
- The ASR server must be running on `127.0.0.1:8000`; the optional cleanup server uses `127.0.0.1:8080`.
- Short start/stop chimes are on by default; use `--no-sound` to disable them.
- Default insertion sends the complete transcript with one clipboard paste and restores its restorable content. It never silently switches to per-letter typing. If the clipboard contains unsupported native content, the full transcript is retained for recovery and nothing is typed. `--paste-method sendinput` remains an explicit slower alternative.
- Run `pc-dictate-v2 --help` for URL, sound, and output options.

The Python runtime is `C:\AI\Audio\Qwen3-ASR\.venv\Scripts\python.exe`. Dependencies are listed in [requirements-dictation.txt](./requirements-dictation.txt). VAD uses the same pinned [Silero ONNX asset](./android-app/app/src/main/assets/vad/README.md) as the phone.

## Processing alignment

### Missing-character insertion regression (2026-11-02)

The user's missing-letter reports were accompanied by visibly sequential typing. The current Windows clipboard contained OLE bookkeeping (`DataObject`, `Ole Private Data`) and an empty `EnterpriseDataProtectionId` marker; the old snapshot rejected it and silently switched the default clipboard mode to Unicode typing with 2 ms between characters. Windows accepting those events did not verify that the editor retained every character.

The snapshot now excludes live OLE transport bookkeeping, skips the empty marker, and retains nonempty protection data and ordinary text/rich-text formats. Default output uses one Ctrl+V; unsupported content reports failure and retains the full transcript rather than typing a potentially incomplete result. Explicit SendInput mode uses 25 ms pacing. The actual clipboard changed from unsupported to safely restorable, and a synthetic round trip preserved both reported sentences, negation and Unicode while restoring the original content. No keyboard input was sent into a user editor by that check. [check_clipboard_roundtrip.py](./tests/check_clipboard_roundtrip.py) reproduces it. The 103 Python regressions pass; ASR, audio segmentation, Android insertion and cleanup training data were unchanged in this follow-up.

The defaults match the Android source, including:

| Setting | Mobile and PC v2 |
| --- | --- |
| Input | 16 kHz mono signed PCM16; 1,600-sample capture blocks |
| Session limit | 300 seconds |
| Silero VAD | 512-sample frames; 64-sample context; speech thresholds 0.50 on / 0.30 off |
| Pause | 320 ms of non-speech; detection only, never a sentence or section boundary |
| First background request | At least 32,000 samples (2 s); Stop flushes even a short command |
| Growing window | Maximum 128,000 samples (8 s) |
| Rolling overlap | 48,000 samples (3 s), capped at half the window |
| Non-speech pre-roll | 4,800 samples (0.3 s) |
| Long quiet gaps | Keep 750 ms on each side when a gap exceeds 1.5 s; original capture remains intact |
| Revisable ending | Last three words remain provisional even when consecutive hypotheses agree |
| Background pace / timeout / failure delay | 1,000 ms / 4 s / 1,500 ms |
| Model requests | Three attempts for transient failures; 250 ms and 500 ms retry delays |
| ASR request | `/v1/chat/completions`, same Qwen model, explicit English, audio WAV, temperature 0, 4,096 max tokens, non-streaming |
| Cleanup request | Same model, prompt, 4,096 max tokens, `reasoning_effort: none`, `enable_thinking: false` |

Like mobile, v2 keeps a continuous context across pauses, uses two-pass local agreement for provisional text, reconciles actual overlapping windows, and widens an ambiguous overlap once. If matching still fails, capture continues until release. One final request transcribes the entire original recording, including later speech and unmodified pauses. Its result replaces all partial text before cleanup and a single insertion. A failed or blank recovery still saves the audio and partial text without inserting them. Normal windows remain limited to eight seconds; this explicit fallback can contain the full five-minute recording. Progress reports its duration separately from the largest normal window. The newer overlapping hypothesis supplies punctuation and capitalization at the join; genuine sentence breaks elsewhere are preserved. It reuses a completed response when Stop covers exactly the same recognition audio. Excess silence is removed only from Qwen's recognition view; no speech timestamps are guessed from text. Both desktop launch commands use this policy. The older forced-alignment transcriber also defers alignment failures to a full-recording decode in `finish()`, returning authoritative text without invented word timestamps.

There are unavoidable platform differences: Windows uses its selected microphone and push-to-talk keys; Android uses its `VOICE_RECOGNITION` capture source and mobile editor insertion. Microphone processing, input-device latency, request completion timing, and operating-system scheduling can shift exact window boundaries and model output even with the same thresholds. At the five-minute limit, v2 stops accepting audio and finishes background processing; release the hotkey to paste. For failed ASR, cleanup, microphone capture, or insertion, v2 retains audio and partial text in the same local `dictation-recovery` location as v1 instead of pasting an incomplete result. On Android, a failed cleanup presents the raw transcript for copying; on Windows, v2 saves it for recovery.

## Validation

The [Kotlin parity test](./android-app/app/src/test/java/com/companion/ainotifications/dictation/MobilePcParityTest.kt) runs the actual Android streaming classes against deterministic audio. [compare_mobile_pc_parity.py](./tests/compare_mobile_pc_parity.py) runs the same cases through the Python port and checks the complete transcript and every submitted range. Continuous speech, ordinary pauses, and long thinking pauses match exactly, including four rolling requests for the 20-second continuous case.

### Continuous-context regression (2026-11-02)

Follow-up after the user's real-speech trials: two independent desktop dictation workers were running, one from an automatically started background copy and one from the normal launcher. Each could paste its own transcription after the same hotkey release, explaining the two complete sentences without a separating space. The extra copy was stopped. Both entrypoints now enforce the shared kernel mutex, including across separate Python processes; no transcript text is deleted to conceal duplicate insertion. The updated daily-use instance was restarted in a visible console.

Android and Python also reconcile capitalization of the first matched word in a new overlapping window when the earlier hypothesis already placed that word lowercase inside a continuing sentence. Existing sentence starts, quoted openings, known capitalized names/acronyms and pronoun “I” remain unchanged. This fixes case introduced at joins; it does not claim to correct arbitrary capitalization inside a single raw Qwen response. The follow-up passed 100 Python tests, 68 Android unit tests (one optional integration test skipped), builds, lint and all three platform trace comparisons. Actual CLI subprocess tests cover both entrypoints refusing a second listener, and mutex release after a crash.

The synthetic sentence “Because right now it is giving me some Hindi words in the transcript output sometimes.” was replayed directly against Qwen3-ASR-1.7B, with cleanup disabled. All four fixtures (SSML requested pauses of 350, 750, 1,500, and 20,000 ms) returned the exact words with one final period. Requested SSML break durations are not measured acoustic gaps; the speech synthesizer's rate changes silence duration. The longer fixtures produced multiple actual Silero pause detections. Decoding those detected sections separately produced “Because right now, it is giving me. Some Hindi words in the transcript output sometimes.” The continuous view repaired that boundary without deleting genuine punctuation globally.

The 5.41-second desktop push-to-talk replay made three successful requests with no failures and approximately 484 ms remaining ASR after release. This is one synthetic measurement, not a latency benchmark. Regenerate the synthetic fixtures with [create_qwen_context_fixture.ps1](./tests/create_qwen_context_fixture.ps1), then compare both segmentation strategies with [replay_qwen_context.py](./tests/replay_qwen_context.py). Neither utility opens a microphone, inserts text, or invokes cleanup. The text-only cleanup training corpus is unchanged.

Earlier pause-segmented validation: the phone's 7.01-second synthetic test WAV was replayed through the v2 callback, real Silero VAD, and actual Qwen server. V2 made eight requests with zero failures and returned: “Please book the train ticket for tomorrow. Write the meeting time in the blue notebook.” One raw-mode run finalized about 281 ms after release. The corresponding phone replay also made eight requests, with two sections and no failures. A separate fast-queued replay also successfully exercised the live cleanup model. These historical runs predate the continuous-context change and are not an accuracy or latency benchmark; the timing-dependent window lengths differed.

Additional live replays retained the intentional repetitions in an 18.8-second fixture, and a deliberately queued 77.5-second fixture completed all audio in requests no longer than eight seconds. Deterministic tests cover pause detection, thresholds, overlap ambiguity, short tails, retries, request payloads, cancellation, stop during inference, cleanup failure, full-audio recovery, and the five-minute limit.

To reproduce the Python tests and live synthetic replay:

```powershell
& C:/AI/Audio/Qwen3-ASR/.venv/Scripts/python.exe -m unittest discover -s tests -v
& C:/AI/Audio/Qwen3-ASR/.venv/Scripts/python.exe ./replay_pc_dictate_v2.py ./android-app/app/build/mobile-sanity/synthetic-mobile-replay.wav
```

The replay uses no microphone and replaces text insertion with an in-memory collector. `--fast` deliberately queues a backlog; `--cleanup` also calls the cleanup model. Reports and synthetic inputs under build directories and `test-data/dictation-reliability/` are local scratch data. Real microphone quality and Windows application insertion still require normal hands-on use.
