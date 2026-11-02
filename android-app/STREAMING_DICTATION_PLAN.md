# Background transcription

The app keeps Qwen3-ASR and your existing private PC endpoints.

- While you speak, it sends audio windows of at most **8 seconds**.
- **Silero VAD** detects speech and reports pauses after about 320 ms of non-speech. A pause never finalizes a section or resets Qwen's context.
- Previews begin after two seconds of context and update about once per second. Stop immediately decodes shorter commands. Android and Python explicitly request English.
- Windows move regardless of pauses: roughly **0–8 s → 5–13 s → 10–18 s** in recognition audio. The three-second overlap protects boundary words.
- Normal pauses remain intact. For quiet gaps over 1.5 seconds, the recognition view retains 750 ms at each edge, preserving speech overlap across a long thinking pause. Original PCM remains untouched in RAM. Progress maps recognition positions back to original recording positions and shows actual request audio duration separately.
- Matching words in the overlap join the sections. One retry can use a larger overlap, still within the eight-second request bound. An unresolved join produces an error instead of silently discarding speech or resending the full recording.
- **LocalAgreement-2** separately identifies stable text in consecutive updates of the same window, keeping the recent three words provisional. The newer overlap hypothesis can revise boundary punctuation and capitalization. Stable preview text is revisable; accepted section text is held until the complete dictation is optionally cleaned and inserted once.
- Stop finishes the remaining bounded windows. It no longer disables background ASR after 12 seconds or submits a normal `0–64 s` final request.

All captured audio stays in RAM until the session ends, with the existing five-minute limit. Retaining it does not mean repeatedly transmitting it. Snapshot lookup starts at the active window rather than scanning the entire recording.

Turn off **Transcribe while speaking** for the original explicit batch mode. **Show transcription details** displays request ranges, detected pause count, window moves, stable previews, and committed parts.

See [the testing guide](./STREAMING_DICTATION_TESTING.md).

## Why the previous version failed

The previous fixed loudness gate could miss pauses over background noise and required 660 ms of quiet. Its 12-second safety cap then stopped speculative requests until Stop, when the full unresolved recording was transcribed. The short silent-gap replay did not cover that failure.

## Wispr Flow comparison

Wispr Flow is the commercial product discussed here. Its [Canto research post](https://wisprflow.ai/canto) describes its own model and training, but does not disclose an exact chunk-window or LocalAgreement-2 algorithm. This implementation uses a tested bounded-window approach for the current Qwen endpoint; it is not a reproduction of Wispr Flow's private backend.

## Dependencies

Silero's bundled 16 kHz ONNX model is 1.29 MB and MIT licensed. [Model provenance and checksum](./app/src/main/assets/vad/README.md) are included. ONNX Runtime Android runs VAD on one CPU thread during recording; the PC still performs ASR. The native runtime increases APK size. The locally signed optimized variant includes ARM64 only for the existing phone; debug and release retain other architectures.

Word timestamps and a native stateful ASR backend are not required for this implementation. Overlap reconciliation is a heuristic and requires evaluation on real speech; it does not guarantee recognition accuracy.
