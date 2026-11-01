# Background transcription implementation plan

Status: proposed; application code has not been changed. Reviewed November 1, 2026.

## Intended behavior

While the user speaks, Android captures microphone audio and sends cumulative audio snapshots to the existing Qwen3-ASR server in the background. Stop finishes the remaining transcription, optionally cleans the complete raw transcript, and inserts once through the existing editor guards. Cancel discards the session and cancels its requests.

The request interval is not the audio window length. For example, requests at 0.8, 1.6, and 2.4 seconds may contain audio ranges [0, 0.8], [0, 1.6], and [0, 2.4]. A partial word at the end of one snapshot remains available in full context on the next pass. Independent 300–500 ms audio clips are not the proposed design.

## What already exists

- [PcmRecorder.kt](./app/src/main/java/com/companion/ainotifications/dictation/PcmRecorder.kt) captures 16 kHz mono PCM16 in memory, with a five-minute limit, but exposes the audio only when recording ends.
- [DictationController.kt](./app/src/main/java/com/companion/ainotifications/dictation/DictationController.kt) currently starts ASR in `stop()`, then optionally runs cleanup and guarded insertion.
- [DictationClient.kt](./app/src/main/java/com/companion/ainotifications/dictation/DictationClient.kt) already sends in-memory WAV audio over cancellable OkHttp calls to `/v1/chat/completions`.
- [DictationSettings.kt](./app/src/main/java/com/companion/ainotifications/dictation/DictationSettings.kt) already separates the private ASR and cleanup endpoints.
- The running PC process was inspected: llama.cpp b11707, Qwen3-ASR-1.7B-Q8_0 plus audio projector, port 8081, context size 6000, GPU layers 99, Flash Attention on. This confirms the launch configuration, not inference health or streaming throughput.

## First implementation

### 1. Capture audio independently of inference

Extend the recorder with session-owned PCM storage indexed by sample count. Preserve all captured audio until completion/cancellation, bounded by the existing five-minute limit (about 9.6 MB of PCM). Provide immutable range snapshots for the worker without blocking capture on WAV encoding, HTTP, or decoding. Take large copies off the UI thread and keep synchronization brief.

At Stop, stop the microphone and await capture completion before determining the final sample index. This ensures the final read is included. Cancellation must release the microphone even if a worker never starts.

### 2. Add one background ASR worker per session

Introduce a `StreamingTranscriber` with injectable ASR and timing dependencies for JVM tests. Begin requesting after approximately 0.8–1.0 seconds of captured audio, then target a 600–900 ms interval when new audio exists. These are initial tuning values, not latency promises.

Allow at most one ASR request in flight. Coalesce speculative work to the latest available snapshot; do not create a coroutine or network backlog for every tick. Associate each request with session ID, audio start/end indices, and whether it is provisional or final. Snapshot endpoint settings at session start so a recording cannot mix models or routes.

Keep recording while a request runs. Prefer necessary segment finalization over speculative passes. If the server is slower than the interval, reduce request frequency. Request timeout/backoff must prevent a stalled speculative call from monopolizing Stop handling; test server cancellation behavior before relying on cancelling/replacing requests as an optimization.

### 3. Separate stable hypotheses from final audio segments

Implement LocalAgreement-2 over consecutive full hypotheses for the same audio window. Calculate a common word prefix and hold back the final word. Track stable text and the revisable tail without repeatedly appending complete hypotheses.

Agreement is advisory: final segment inference may revise even a previously stable prefix. Preserve original spelling, punctuation, and deliberate repetitions. Do not deduplicate adjacent segments by text; repeated words may be intentional. Reset agreement state when the audio start changes.

No partial text is inserted into the target editor. Partial transcription can remain internal or appear in the existing result screen without changing the compact overlay.

### 4. Finalize at conservative silence boundaries

Analyze short PCM frames locally for sustained quiet, starting with a configurable, conservative energy detector and approximately 650 ms of silence. This detector is a heuristic, not Silero VAD, and needs quiet/noisy-room validation. Avoid treating a momentary dip within speech as a boundary. Use sample indices to place a candidate split inside the detected silence and preserve audio on both sides in storage.

Run a final ASR request for the exact closing segment. Advance the segment start only after successful, non-truncated finalization. Concatenate finalized segment transcripts in audio order. A successful speculative response can satisfy finalization only if its audio range exactly matches the required range and meets final-result validation.

Retain unresolved audio after errors. Coalesce pending boundaries into a larger unresolved range if inference falls behind; do not drop speech to keep pace. Silent ranges need explicit handling: blank provisional output is allowed, while an empty final result must not silently discard a range believed to contain speech.

A pause boundary is not a semantic boundary. Cleanup still sees the complete raw dictation, including corrections such as “wait, scratch that.”

### 5. Bound speculative work without cutting speech

Initially cap cumulative speculative requests at roughly 12 seconds per unresolved segment, subject to the server's measured context limits. If continuous speech reaches that limit without a credible pause, suspend speculative passes while capture continues. Finalize the unresolved range at the next pause or Stop using the existing batch path where the model permits it.

This is a deliberate first-version limit: uninterrupted long speech may retain batch-like Stop latency. The five-minute capture limit does not guarantee that the ASR model accepts five minutes in one request; surface context/output-limit failures rather than inserting incomplete text.

Do not reset the audio buffer every 5–6 seconds. A later phase can add tested VAD, overlapping-window reconciliation, or a supported aligner for bounded long-speech windows. Word-prefix agreement alone cannot map text onto PCM offsets.

### 6. Finish only unresolved work at Stop

Stop capture, establish the final audio range, and let the worker finish or supersede outstanding provisional work under a defined policy. Reuse results only for matching ranges; transcribe all remaining audio, including very short tails. The remainder can be an entire active segment, not necessarily the last 400 ms.

Assemble the complete raw transcript, then invoke the existing optional cleanup once with the Polish value captured at Stop. Preserve cleanup-failure review/copy behavior and editor identity/selection validation. Cancellation invalidates the session before stopping capture and HTTP; late results cannot publish or insert.

If speculative ASR fails, keep capturing and retry the unresolved audio at finalization/Stop with bounded retry behavior. If final transcription fails, show the existing error flow rather than publishing an incomplete assembled result.

### 7. Add measurements and a rollback switch

Add an opt-out setting for background transcription, keeping the existing batch flow available for comparison and recovery. Update the Dictation explanation because audio now leaves the phone during recording.

Measure background request count/duration, audio duration per request, finalized audio coverage at Stop, outstanding request wait, final ASR duration, cleanup duration, and Stop-to-result. Keep measurements free of audio, transcript contents, and credentials. No idle ASR timer or request should run outside an explicit recording session.

## Verification

- JVM tests: cumulative windows repair partial words; stable-prefix updates do not duplicate text; intentional repetition survives; silence splits preserve contiguous sample coverage; a slow server coalesces work without dropping audio; Stop includes the last samples and short tails; cancellation rejects late results; provisional blanks are allowed; truncated/failed finalization never advances coverage; background failures recover through the unresolved range.
- MockWebServer tests: one in-flight request, actual HTTP cancellation, range-specific result reuse, and batch fallback.
- Build debug and optimized variants, run JVM tests and lint using the documented JDK 21/Gradle setup.
- Phone/PC acceptance: short sentences, long dictation with pauses, uninterrupted speech, noisy/quiet environments, spoken corrections, deliberate repetition, Polish on/off, repeated Stop/Cancel, unavailable PC/Tailscale, and changing editors during recording/processing. Preserve the existing keyboard/focus and single-insertion behavior.
- Compare baseline and streaming using the same replayable speech recordings. Report median/p95 Stop latency, request load, and transcription errors separately from cleanup quality. Live speech verifies capture and device behavior; replayable audio makes accuracy/latency comparisons meaningful.

## Optional follow-up optimizations

1. Verify the deployed llama.cpp build's actual assistant-continuation API and Qwen ASR chat template with controlled audio. Do not assume `continue_final_message` is supported or that a final assistant message returns only a new transcript tail. Growing audio still requires encoder work; text prefill does not make that free.
2. Add Silero VAD if energy gating is inadequate. This requires a compatible model asset, its license review, an Android inference runtime, and device tests. A forced aligner is another optional dependency; Qwen3-ASR model-family streaming features do not establish support in this llama.cpp serving path.
3. Investigate cleanup KV prewarming only after ASR changes are measured. Prefix reuse depends on the exact serving template, slot API, prefix stability, and request serialization. Do not hard-code the pasted `slot_id`/`n_predict` example or custom delimiter tokens without verification.
4. Consider speculative clause cleanup separately. A one-sentence lookback cannot guarantee correction handling across an entire dictation; retain full raw context and evaluate cross-clause instructions before adopting it.

## Workspace and external requirements

The first implementation can be written and locally tested entirely in this repository. It requires no new Python service, model retraining, forced aligner, or additional model download. Keep the existing Tailscale routes and configured model IDs rather than introducing the pasted example's port 8082.

End-to-end validation needs the PC ASR and cleanup services, Tailscale on both devices, and the phone for an in-place APK update and live speech acceptance. Only the ASR launch configuration was verified in this review; cleanup availability, saved phone endpoint settings, inference health, and API continuation support remain to be checked during implementation/testing.

[notation_additions.json](../test-data/gpt-sol-6.1/unsloth-notation-v3/notation_additions.json) is cleanup training data. It does not enable streaming or require modification for this work. Its notation/correction examples are useful cleanup regressions, not recorded ASR test audio. The current client contains a cleanup system prompt, whereas the v3 dataset describes a no-system-prompt model format; aligning that serving configuration should be a separate, explicit change once the actual deployed cleanup model is confirmed.

The supplied 350 ms and 80 ms estimates are unverified. Stop latency depends on outstanding inference, unresolved audio, network transport, cleanup output length, and insertion. A fine-tuned cleanup model can still hallucinate; neither fine-tuning nor LocalAgreement-2 supplies a correctness guarantee.
