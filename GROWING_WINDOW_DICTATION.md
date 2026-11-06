# Growing-window dictation (experimental)

This is a separate implementation in [growing_window_transcriber.py](./growing_window_transcriber.py) and [GrowingWindowTranscriber.kt](./android-app/app/src/main/java/com/companion/ainotifications/dictation/GrowingWindowTranscriber.kt). The existing overlap implementation remains available and remains the default. No Git branch or replacement of the old algorithm is required.

## Select or revert

On Windows, close the current dictation console, then run [run_pc_dictate_growing.bat](./run_pc_dictate_growing.bat). Both Python entrypoints also accept `--streaming-strategy growing`. Return to either original launcher, or pass `--streaming-strategy overlap`, to restore the existing flow. The single-instance guard applies to both strategies.

On Android, enable **Transcribe while speaking**, then **Growing windows with word timing (experimental)** in Dictation settings. Turn the latter off to restore the overlap flow. The selection is captured at preparation for the next recording; changing it cannot switch a running session. Batch mode is unchanged.

## Advancement policy

The [November 3 latency investigation](./DICTATION_LATENCY.md) adds a limited Stop optimization: flush a bounded remaining tail directly, omit unnecessary alignment for a whole-recording final response, and reuse an exact successful audio response. Recording-time agreement and context settings are unchanged. Old/new replay outputs were identical; longer requests still exceed 500 ms.

Consecutive requests start at the same audio position and contain progressively more audio. Compare the normalized word prefix, preserving negation and intentional repetition. Keep two ending words and at least 800 ms near the right edge provisional. Preview agreement does not immediately move the audio start.

Once the window reaches its target, a shared prefix with complete, finite, ordered word alignment can authorize advancement. Commit that prefix internally and retain 1.5 seconds before its estimated end as acoustic context. Re-observed words in that left context are excluded by their timestamps, rather than by searching for a matching suffix between shifted windows. Require at least two seconds of new confirmed progress per advance. Nothing is inserted until Stop.

If results disagree or alignment is absent/incomplete, keep the same start and grow. Alignment can contain zero-duration short words; these remain in the text, but a collapsed or overlapping boundary cannot authorize a cut. Never invent word timestamps. Word alignment remains an estimate and can drift; prefix agreement does not certify recognition accuracy.

Normal pauses are retained. The existing recognition view compresses only excess quiet in gaps longer than 1.5 seconds; its offsets map back to original PCM for progress. If no safe advance is possible at the 20-second limit, continue capture and defer to one full-original-recording decode after Stop. That result replaces every partial section. Failed/blank recovery does not produce an insertion. Cancellation rejects late responses. The five-minute capture cap and existing insertion checks remain in place.

## Starting defaults and measurements

| Parameter | Selected value |
|---|---:|
| First decode | 4 s of audio (short commands flush immediately at Stop) |
| New audio per growing update | 2 s |
| Target before timestamp advancement | 12 s |
| Maximum growing request | 20 s |
| Retained left context | 1.5 s |
| Provisional right edge | 800 ms and two words |
| Minimum new confirmed progress | 2 s |

These are initial settings for the current local Qwen3-ASR-1.7B service, not universal optimal values. Alignment adds inference work. The growing decoder can use more compute than the existing flow while leaving less work after release.

On November 2, 2026, local backlog replays compared initial/growth/target/context configurations using the supplied 50.30-second recording, a 5.729-second synthetic hesitation, and an 18.772-second synthetic repetition clip resampled to 16 kHz. The following long-recording measurements exclude capture time and include all ASR work:

| Initial / growth / target / context | Requests | Processing | Submitted audio | Full recovery |
|---|---:|---:|---:|---|
| Existing 8 s windows / 3 s overlap | 9 | 7.66 s | 114.30 s | Yes |
| 2 / 1 / 6 / 0.5 s | 57 | 22.59 s | 258.88 s | No |
| 3 / 1.5 / 8 / 0.5 s | 40 | 18.89 s | 241.09 s | No |
| 4 / 2 / 10 / 0.5 s | 26 | 15.78 s | 209.14 s | No |
| **4 / 2 / 12 / 1.5 s** | **30** | **17.84 s** | **240.06 s** | **No** |

The 12-second target with 1.5-second context retained the difficult “early scribes … cuneiform … trades and laws” passage better than the shorter-context candidates in this run. The supplied recording has no manually verified reference, so this is a qualitative comparison, not a word-error-rate benchmark. Raw recognition still varied in opening words, grammar, and punctuation. All first-sweep candidates preserved the normalized hesitation sentence and the synthetic repetition clip's words, including repeated “very,” “had,” “please,” and “no,” and negation.

A separate real-time replay through the actual Windows controller used the selected settings: 30 responses, five timestamp advances, six final sections, full coverage of 804,800 original samples, no failures or full recovery, and one simulated insertion. Remaining ASR after release was 1.047 seconds; cleanup and real editor insertion were disabled. These are individual local measurements, not promised latency.

Two actual phone replays used Silero VAD and the saved private phone-to-PC Qwen endpoint. The 5.729-second hesitation returned the exact expected single sentence in two requests, with two detected pauses and 720 ms remaining after Stop. A separate 16.917-second synthetic dictation made nine requests and one timestamp advance, retained both clauses and the intentional “please please” repetition, and finished 1,058 ms after Stop. Its first request after advancement lacked usable alignment; the worker grew from the same start and recovered alignment without a full-recording fallback. Both covered all original samples with zero request failures, no microphone access, and no editor insertion. The optimized app was installed afterward; existing settings and accessibility were preserved.

Validation passed 122 Python tests and 82 Android unit tests (one optional integration test skipped), debug/optimized builds, lint, the growing-window Kotlin/Python request trace, and all three existing overlap traces. Tests cover cut words, no agreement, invalid/missing/zero-duration timestamps, retained context, full-recovery replacement, failed/blank recovery, cancellation including callbacks that suppress cancellation, short commands, negation, single insertion, and strategy selection.

Reproduce backlog measurements with [benchmark_growing_windows.py](./tests/benchmark_growing_windows.py); it accepts explicit input paths and `--profiles`. Reports contain those inputs' transcripts and should remain local. Use [replay_pc_dictate_v2.py](./replay_pc_dictate_v2.py) with `--streaming-strategy growing` for real-time replay without microphone or editor access. The debug phone fixture accepts `--es strategy growing` and uses only an explicitly supplied synthetic WAV, without changing the saved strategy.

The approach uses prefix agreement described in [Whisper-Streaming](https://github.com/ufal/whisper_streaming), with Qwen's optional [forced-alignment timestamps](https://github.com/QwenLM/Qwen3-ASR/blob/main/examples/example_qwen3_asr_transformers.py). Those sources describe capabilities and policy; the defaults above come from local tests.
