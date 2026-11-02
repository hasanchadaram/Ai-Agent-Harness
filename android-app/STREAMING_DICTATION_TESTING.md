# Test background transcription

1. Open **AI Notifications → Dictation**. Enable **Transcribe while speaking** and **Show transcription details**. Turn **Polish after Stop** off for the first test.
2. Tap **Try dictation in this app**. Speak for a minute with your normal pauses, then Stop.
3. Inspect the numbered updates below the transcript.

| Check | Expected result |
|---|---|
| Recognition audio length | Every background/final ASR request contains **at most 8,000 ms** of audio. The `audio` duration is shown separately from original recording positions, which can span removed excess silence. |
| Pauses detected | Increases as you pause. The detector should say **Silero VAD**. |
| Window starts at | Moves forward during dictation. It should not stay zero for the entire minute. |
| No-pause windows | Example: `0–8000`, `5000–13000`, `10000–18000` ms. The overlap is deliberate. Intermediate updates can use shorter growing windows. |
| Window checkpoint | The window reached its bound. Its ending is retained until the overlapping request agrees. |
| Overlap joined | Shared words matched and older text was committed. Repeated words are not simply deleted wherever they appear. |
| Stable preview | A prefix matched in consecutive updates of the same window. It can still be corrected. |
| Committed parts | Sections accepted after overlap reconciliation or Stop. Pauses do not finalize sections. The final result should contain the entire dictation once, in order. |
| Thinking pauses | Normal pauses remain in context. A gap longer than 1.5 seconds retains 750 ms at each edge in the recognition view, preserving speech overlap and the original recording. |
| Language | Android and Python explicitly request English from Qwen. Cleanup is unnecessary for this regression. |
| Stop | Remaining requests stay within eight seconds. There should be no ordinary `0–64000 ms` final request. |

The last 24 request details remain in memory for inspection after Stop. They are not written to files or logs. Enable details before starting a session. Cancel or a new session clears them.

Also try soft speech, room/fan noise, repeated words, a correction across a pause, and Cancel during recording/processing. Compare **Stop to result** across several attempts with background transcription on and off. Batch mode intentionally sends the full recording after Stop.

With an ambiguous overlap, the app tries a larger overlap once. If matching still fails, it reports an error and does not insert an uncertain assembled transcript. Actual speech quality and names still need manual acceptance; matching is not an accuracy guarantee.

## Automated checks

The JVM suite covers cumulative partial-word updates, punctuation revision across pauses, long thinking pauses, loud background noise with an injected speech classifier, 64 seconds without pauses, a complete minute queued before inference, exact-result reuse, short tails, failures, cancellation, and overlap reconciliation. Long recordings must preserve all synthetic words while every request stays within eight seconds. The first preview waits for two seconds of context, while Stop immediately decodes shorter commands. The recent three words remain provisional.

Use the JDK 21 / Gradle build command in [DICTATION.md](./DICTATION.md#build-and-verification). Use `adb install -r` for an in-place update; do not uninstall the daily-use app.

The optional [PC replay test](./app/src/test/java/com/companion/ainotifications/dictation/StreamingAsrIntegrationTest.kt) uses the real PC ASR and a supplied synthetic PCM16 mono 16 kHz WAV. Set `DICTATION_LIVE_ASR_WAV` and run `:app:testDebugUnitTest --tests '*StreamingAsrIntegrationTest' --rerun-tasks`. Its reference fixture contains the words “ticket” and “notebook.”

The shell-protected [phone replay fixture](./app/src/debug/java/com/companion/ainotifications/dictation/DictationStreamingFixture.kt) uses actual Silero VAD and the saved phone-to-PC ASR route. It opens no microphone, inserts no text, and does not change saved settings. Push a synthetic WAV to `/sdcard/Android/data/com.companion.ainotifications/files/synthetic-streaming-check.wav`, then run:

```powershell
adb shell am start -W -n com.companion.ainotifications/.dictation.DictationStreamingFixture
adb shell run-as com.companion.ainotifications cat files/dictation-streaming-check.txt
```

Wait for `SUCCESS` or `FAIL`. For the minute-long reference fixture, all eight section markers—apple, banana, carrot, flower, garden, jacket, kitchen, lemon—must survive. The report verifies the request bound and multiple detected pauses.

Pause segmentation is always disabled in the current worker. The historical `--ez force_rolling true` flag now suppresses pause reporting only. Relaunch with a fresh activity so new flags take effect. These debug fixtures are absent from the optimized variant; metadata-only test reports are retained for review.

For the Qwen hesitation regression, generate inputs with [create_qwen_context_fixture.ps1](../tests/create_qwen_context_fixture.ps1). Push `hesitation-750.wav` (or the long-pause fixture) to the same synthetic WAV destination and launch with `--es scenario hesitation`. It checks the exact sentence, actual detected pauses, full sample coverage, and absence of artificial sentence breaks. SSML requested pauses are not measured acoustic gap durations. The initial 350 ms requested fixture bridges most pauses in Silero and is useful for transcript comparison rather than the fixture's multiple-detection assertion.

## Continuous Qwen context verified on the phone (2026-11-02)

Follow-up: overlap tests now reproduce “Some Hindi words” and “In the transcript” at the start of a later request, checking that a continuing sentence retains the earlier lowercase opening. Separate cases preserve genuine sentence starts, known names, acronyms, quoted text and “I.” The updated optimized APK was installed in place. The suite now passes 68 Android unit tests (one optional integration test skipped) and 100 Python tests, plus builds, lint and the three platform trace comparisons. The duplicated desktop output was traced to two independent hotkey listeners; both Python entrypoints now enforce one Windows session mutex. Raw Qwen capitalization mistakes within one response remain outside this narrow join repair.

Both runs used actual Silero, the saved HTTPS phone-to-PC route, Qwen3-ASR-1.7B, and the raw transcript before cleanup. Each returned exactly “Because right now it is giving me some Hindi words in the transcript output sometimes.” with one final period.

| Synthetic fixture | Recorded duration | Requests | Detected pauses | Final sections | Longest request audio | ASR after Stop |
|---|---:|---:|---:|---:|---:|---:|
| Requested 750 ms hesitation | 5,729 ms | 4 | 2 | 1 | 5,729 ms | 718 ms |
| Requested 20 s thinking pause | 21,179 ms | 6 | 2 | 1 | 6,711 ms | 726 ms |

Both had zero failures and complete original sample coverage. The long recording remained one acoustic context without a silence-only overlap. These are individual synthetic runs, not a latency or microphone-accuracy benchmark. The requested SSML break times are not measured acoustic gaps. A separate 350 ms requested replay returned the same correct sentence but failed the fixture's multiple-pause-detection assertion because Silero detected only one pause; the longer fixtures verify actual detected hesitations.

The final suite passed 65 JVM tests (one optional live JVM integration test skipped), 92 Python tests, and three exact mobile/Python trace comparisons. Debug/optimized builds and lint passed. The optimized APK was installed in place and accessibility was restored after the update; existing settings were retained. No microphone recording or editor insertion was used. The desktop app and Qwen service were restarted with the updated code. The cleanup model and training data are unchanged.

## Historical pause-segmented phone validation (2026-11-02)

Both replays used the same 62.69-second synthetic recording with added room-like noise, actual Silero VAD, and the saved phone-to-PC ASR route. No microphone recording or editor insertion was involved.

| Replay | ASR requests | Longest request | Detected pauses | Window rolls | Remaining ASR after Stop |
|---|---:|---:|---:|---:|---:|
| Normal pause segmentation | 68 | 5,088 ms | 16 | 0 | 241 ms |
| Pause segmentation deliberately disabled | 64 | 8,000 ms | 0 | 11 | 265 ms |

Both passed full audio coverage, all eight topic markers, eight occurrences each of the repeated words “notebook” and “meeting,” and stable preview before Stop. Short-window ASR changed “banana” to “bananas”; the coverage check explicitly accepts either form. This checks section coverage, not word-for-word ASR accuracy. Stop timings exclude cleanup and insertion and are individual measurements, not a latency benchmark.

The regular JVM run passed 51 tests; the optional live integration test was skipped in that run and passed separately against the PC ASR. Build, lint, and 25 phone editor/overlay checks passed.

For diagnosis, the phone fixture can explicitly include its **synthetic** result text with `--ez include_synthetic_text true`; the default report contains metadata only. The optional PC replay accepts `DICTATION_LIVE_ASR_FAST_REPLAY=true` for backlog testing and `DICTATION_LIVE_ASR_SYNTHETIC_TEXT=true` to print synthetic text. Keep those text diagnostics off for real speech.

The optimized-build crash fix was also checked on the phone: a microphone start ran Silero VAD and completed four background ASR requests with no failures, then Cancel stopped the microphone. This is a startup and connection smoke test, not a recognition accuracy evaluation. The earlier minute-long replay timings used the previous llama.cpp ASR server, not the current Python ASR server.
