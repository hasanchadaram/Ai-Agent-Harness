# Dictation reliability review — 2 November 2026

The current Android and desktop launch commands use continuous Qwen audio context across pauses. See [the shared PC policy and regression results](./PC_DICTATION_V2.md) and [phone validation](./android-app/STREAMING_DICTATION_TESTING.md). The forced-alignment implementation and measurements below document the earlier pipeline; the legacy Python class remains available to replay tools.

The PC streaming pipeline had reproducible data-loss, concurrency, and non-speech hallucination failures. The fixes preserve background transcription and make finalization recoverable. They do not make ASR or forced alignment infallible.

## Reproduced problems and changes

| Problem | Resulting behavior |
| --- | --- |
| Background inference and release/finalization mutated shared state concurrently; a dequeued audio block could disappear during stop. | One processing owner, synchronized callback admission, and a final drain of every accepted block. Session start/stop are serialized. |
| A final request could fail after the tentative tail was cleared, then paste a partial transcript as success. | Three finalization attempts, atomic response validation before applying changes, and recovery files when finalization fails. Partial results are not pasted as complete. |
| A spelling-only suffix rule removed intentional repeats such as “very very.” | Re-observed context is matched by overlapping word intervals and spelling. Separate spoken occurrences survive. |
| Cutting precisely at the last estimated word end clipped context; timestamp drift could duplicate short boundary words. | Retain half a second of acoustic context around speech boundaries and reconcile timed words. Silence gaps allow safe forward progress. |
| Silent and noise-only recordings generated invented speech. | CPU Silero VAD rejects non-speech and trims leading/trailing non-speech before ASR. Quiet audio is amplified for detection only. The original audio clock is restored after trimming. |
| Aligner word ends sometimes extended 220–254 ms beyond a five-second clip. | Permit bounded edge drift up to 500 ms, clamp returned timestamps to the captured interval, and retain the uncertain nonfinal tail for re-decoding. Invalid, non-finite, or reversed timing still fails explicitly. |
| ASCII-only, short-lookahead punctuation matching could miss Unicode, contractions, or mismatched tokenization. | Restore original text by complete normalized character coverage. Split/merged aligner tokens preserve the original words and punctuation. Incomplete coverage fails instead of silently deleting text. |
| Capitalization heuristics changed Alice, London, and Monday to lowercase. | Preserve model capitalization. |
| Releases shorter than 200 ms were discarded; final backlogs could become one huge request or lose audio in the standalone transcriber. | Submit every nonempty final tail, pad very short encoder input, and drain backlogs with bounded requests. The standalone transcriber exposes `finish()`. |
| Empty/truncated cleanup responses replaced valid raw text; the live cleanup model also deleted repeated “please.” | Use raw text when cleanup fails, is incomplete, is empty, or removes repeated content words. Repeated filler can still be removed. |
| Worker exceptions, microphone overflow, rejected keyboard input, or a focus change could look like success. | Keep worker faults visible, save recoverable audio/text, check input submission, wait for modifier release, and avoid inserting into a different focused window. |
| Clipboard restoration retained only plain text and could overwrite a new user copy. | Snapshot supported global-memory formats, use direct Unicode input for native clipboard handles, and restore only if the clipboard generation still matches. |
| Synchronous model work blocked FastAPI’s event loop. | Run inference in a thread pool and serialize GPU generation/model loading. |

Core code: [PC client](./pc_dictate.py), [ASR server](./asr_server.py), [shared reconciliation](./asr_streaming.py), [speech detection](./speech_detection.py), and [standalone transcriber](./sliding_window_transcriber.py).

## Verification performed

**57 deterministic tests passed** in [tests](./tests/). They cover intentional repetition, actual observed alignment drift, equal timestamps, invalid timing, Unicode, contractions, partial alignment, short releases, backlog bounds, stereo handling, invalid audio, missing timestamps, request retries, capture overflow, worker exceptions, stop during inference, immediate next-session start, recovery-file contents, recovery write failure, focus changes, cleanup failures, surrogate pairs, modifier barriers, clipboard-generation changes, and GPU serialization/event-loop responsiveness. Keyboard and clipboard mutations are mocked; tests do not type into applications or open the microphone.

The seven initial client regressions failed against the original code and passed after the changes.

Actual GPU replay used Qwen3-ASR-1.7B + Qwen3-ForcedAligner-0.6B on the RTX 5070 Ti. Ten synthetic audio fixtures were compared before/after, with seven additional replay configurations: three boundary shifts, four/eight-second windows, real-time callback/worker feeding, and deliberately queued/delayed processing. These are 17 updated replay scenarios; the first ten also have baseline runs. Speech fixtures use Windows speech synthesis, with deterministic quiet/noise/silence variants.

| Audio fixture | Before: token disagreement | After: token disagreement | Updated final request delay* |
| --- | ---: | ---: | ---: |
| Continuous speech, 18.4 s | 10.9% | 6.5% | 343 ms |
| Intentional repetitions, 18.8 s | 2.3% | 0% | 328 ms |
| Faster speech, 8.4 s | 10.3% | 0% | 281 ms |
| Short “Yes,” 1.3 s | 0% | 0% | 140 ms |
| Leading/trailing silence, 27.4 s | 19.6% | 6.5% | <10 ms |
| Speech with added noise, 18.4 s | 6.5% | 6.5% | 344 ms |
| Quiet speech, 18.4 s | 10.9% | 6.5% | 344 ms |
| Long repeated passage, 77.5 s | 12.0% | 9.8% | 391 ms |
| Digital silence, 12 s | Invented “Okay. So, I’m business” | Empty | <10 ms |
| Noise only, 12 s | Invented “Okay.” | Empty | 15 ms |

The reference in this table is the **same baseline whole-file ASR transcript**, not a human accuracy label. Comparison lowercases and strips punctuation. It counts `$700` versus “seven hundred dollars” and `42` versus “forty-two” as differences. These percentages measure consistency with whole-file inference; they are **not a real-world WER benchmark**. Remaining differences in the continuous and inspected long passages were number formatting; punctuation and capitalization can still vary between windows.

\*These fast-feeding comparison runs process windows synchronously before stop; the delay measures finalization after those windows. Separately, the **real-time worker replay** committed 40 words before release and finalized in **360 ms**, using six requests capped at five seconds. The deliberately queued 18-second capture with 400 ms injected latency per request took **5.09 seconds** after stop; overload still increases finalization time, but does not produce oversized requests or silently discard the backlog. The headless replay fixes the simulated foreground window; actual focus changes are tested separately.

Live API checks also passed for chat-format audio and multipart transcription. Two simultaneous transcription requests returned the expected “Yes.” while model discovery responded in approximately **16 ms**. Three synthetic cleanup prompts exercised the running local cleanup model, including numbers, negation, questions, and repetition.

Local replay JSON/logs and synthetic WAVs are under [test-data/dictation-reliability](./test-data/dictation-reliability/), excluded from Git. They include the original source snapshots used for comparison. No microphone was recorded during this review.

## Reproduce

Use the existing CUDA-enabled Qwen environment. New runtime dependency installed for this review: `onnxruntime==1.31.1`. Other dependencies are listed in [requirements-dictation.txt](./requirements-dictation.txt); installing these into a fresh environment still requires a suitable CUDA PyTorch build. Speech detection reuses the Android client's pinned [Silero ONNX model](./android-app/app/src/main/assets/vad/README.md).

From the repository root in PowerShell:

```powershell
& C:/AI/Audio/Qwen3-ASR/.venv/Scripts/python.exe -m unittest discover -s tests -v
& ./tests/create_dictation_fixtures.ps1
& C:/AI/Audio/Qwen3-ASR/.venv/Scripts/python.exe ./replay_dictation.py ./test-data/dictation-reliability/continuous.wav --worker --realtime --report ./test-data/dictation-reliability/my-replay.json
```

The replay tool substitutes text output and does not paste, press hotkeys, or open the microphone. It exits with failure if the current client needs recovery. For a real accuracy measurement, pass a human reference with `--reference`. `--window-sec`, `--overlap-sec`, `--chunk-sec`, and `--request-delay` support boundary and overload testing.

## Using the changes

The running ASR server on port 8000 was restarted with these fixes and the same 1.7B model/host configuration. **Restart the existing PC dictation client using [run_pc_dictate.bat](./run_pc_dictate.bat)**; the already-running client still contains the old Python code. Avoid running two client instances at once.

On capture/finalization/insertion failure, the client saves accepted audio as WAV and a UTF-8 recovery note/transcript under [dictation-recovery](./dictation-recovery/). Successful sessions do not write recordings. Recovery files are local, excluded from Git, and retained until manually removed. You can replay a saved WAV with [replay_dictation.py](./replay_dictation.py) to recover its transcription.

## Limits of this review

- The aligner estimates locations for the ASR's proposed text; it cannot prove that a recognized word was spoken. This remains an approximate streaming wrapper over repeated offline inference.
- VAD reduces hallucinations, but may miss some whispers or unusual speech. Real accents, microphones, room acoustics, and overlapping speakers need recordings representative of actual use. Synthetic results cannot establish accuracy for your voice.
- Raw ASR can still change punctuation or number formatting, and the cleanup model can still make semantic edits despite its prompt. The added fallback checks protect the reproduced failure cases, not every possible model mistake.
- Windows accepting input does not prove that every application consumed it. Clipboard restoration uses a 300 ms delay; application-specific manual testing is still needed. No actual cross-application pasting was performed during this review.
- Audio is kept in memory until the next session for recovery. The longest tested recording was 77.5 seconds; this review does not establish hours-long capture behavior.
