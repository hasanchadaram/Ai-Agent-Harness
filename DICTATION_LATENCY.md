# Dictation latency investigation — November 3, 2026

The current 1.7B model does not consistently meet a 500 ms request or Stop-to-output target. A limited optimization removes redundant work after Stop while preserving the growing-window recording policy, model, language, context size, timestamps used for joins, and punctuation handling. Five paired real-time replays produced character-for-character identical output before and after the change, with exactly one simulated insertion each. This is regression evidence on these inputs, not a general accuracy guarantee.

## Two timings

- **Request time:** one HTTP request, including VAD, ASR generation, optional forced alignment, and transport. It depends on the actual request audio and decoded output, not just the total recording length.
- **Stop to output:** waiting for an in-flight request, processing the remaining tail, optional cleanup, and insertion. Background work keeps the tail smaller than the full session, but an in-flight request and a new tail request can both contribute to this delay.

The baseline below used the supplied 50.3-second PCM recording, prefixes of that same audio, the local Qwen3-ASR-1.7B endpoint, and three requests per duration/alignment condition. Alignment order was alternated. These are small-sample median client request times; the first cold request was retained. All text-only and aligned requests returned the same text at each duration.

| Request audio | Text only | With alignment |
|---|---:|---:|
| 4 s | 276 ms | 330 ms |
| 8 s | 526 ms | 555 ms |
| 12 s | 816 ms | 861 ms |
| 20 s | 1,156 ms | 1,168 ms |
| 50.3 s | 2,742 ms | 2,787 ms |

Server-reported times were close to local client times; HTTP overhead was generally a few milliseconds. Optional alignment added roughly 10–55 ms in these comparisons. Most measured time was already inside the ASR server. The 50.3-second request is diagnostic/recovery input, not the normal growing request size. Normal requests remain bounded at twenty seconds.

## Limited changes in both platforms

1. Once capture finishes, if the entire remaining recognition tail fits within the existing twenty-second cap, send it directly. There is no benefit in first sending successive shorter speculative windows. Backlogs longer than the cap retain their existing bounded processing/recovery policy.
2. If no earlier prefix was committed and this final request covers the entire recognition recording, return its complete ASR text without forced alignment. There is no earlier context to trim or join. Requests after an earlier commit still require validated timestamps; missing alignment still invokes the existing full-recording recovery.
3. If Stop covers a successful in-flight request exactly, reuse its response. This requires the same window start, end, and byte-identical PCM. Even a tiny new tail requires a fresh response. Cached data is bounded to one request. Blank or failed responses cannot be reused as successful output.

Recording-time agreement, advancement thresholds, retained context, maximum duration, capture, insertion checks, model generation settings, and cleanup are unchanged. These changes apply only to the growing strategy; the existing overlap strategy remains available.

## Paired real-time replay

Cleanup and actual editor insertion were disabled. Each input was replayed at recording speed through the Windows capture callback and the actual Qwen HTTP endpoint, first with `--baseline-finish`, then with optimized finishing. Both paths inserted into a test collector exactly once.

| Input | Before Stop delay | After Stop delay | Requests before / after | Exact text equal |
|---|---:|---:|---:|---|
| Original synthetic hesitation, 5.729 s | 500 ms | 453 ms | 2 / 2 | Yes |
| Stop just after an advance, 12.6 s | 1,375 ms | 750 ms | 7 / 6 | Yes |
| Synthetic dictation, 16.917 s | 906 ms | 906 ms | 9 / 9 | Yes |
| Intentional repetitions, 18.772 s | 1,109 ms | 1,141 ms | 10 / 10 | Yes |
| Supplied recording, 50.3 s | 1,094 ms | 1,156 ms | 30 / 30 | Yes |

The affected cases improved; the three unchanged request paths showed no gain, with small timing variation between runs. The 12.6-second baseline waited for the in-flight twelve-second request, then decoded a four-second intermediate and the final 4.1-second tail. Optimized finishing removed the intermediate request. This is a single pair per input, not a p95 performance benchmark. The 500 ms target remains unmet for longer final windows.

## Punctuation

Polish was off in the reported comma case. The raw joining path preserves punctuation returned by Qwen; it does not add commas. The exact recording/output containing that comma was not supplied in this investigation. The synthetic hesitation returned the expected single sentence, so it does not reproduce that exact punctuation complaint. No punctuation stripping or cleanup change was introduced. Tests explicitly retain a comma and negation through the optimized finish paths.

## Verification and reproduction

Validation passed 126 Python tests, 86 Android unit tests (one optional integration test skipped), debug and optimized builds, lint, and the Kotlin/Python request trace comparisons. Focused tests cover full-tail flushing, exact-audio reuse, rejection of stale reuse when new samples arrive, timestamp-required joins after earlier commits, negation, punctuation, failed/blank recovery, cancellation, and single insertion. Normal growing-window behavior is compared with the previous trace as well.

Two actual phone replays also passed with real Silero and the private Qwen route: the hesitation returned its expected single sentence with 536 ms remaining after Stop; the 16.917-second synthetic dictation exercised timestamp advancement and retained intentional repetition, finishing in 1,272 ms. Both covered all original samples without request failures or full recovery. The latter waited for an in-flight request as well as the final tail. These individual phone timings include transport and do not establish a speed improvement over previous phone runs. A Tailscale diagnostic found a direct path with a 39 ms round trip; network settings were left unchanged. The optimized APK was restored after testing, with existing settings and accessibility preserved.

Use [profile_dictation_latency.py](./tests/profile_dictation_latency.py) for paired aligned/text-only request timings. Use [compare_finish_latency.py](./tests/compare_finish_latency.py) for real-time old/new Stop-path comparisons. Its reports include the explicitly supplied input transcripts and should remain local. [replay_pc_dictate_v2.py](./replay_pc_dictate_v2.py) accepts `--baseline-finish` for the prior behavior; `GrowingWindowTranscriber(..., optimize_finish=False)` and Android's `optimizeFinish = false` preserve that behavior for testing and rollback.

Further reduction below 500 ms for longer requests needs separate inference-backend measurements or a change in how much speech is decoded after release. Shortening acoustic context or freezing a preview sooner could alter recognition, so neither was changed in this investigation. No model weights, quantization, attention backend, dependencies, or GPU power settings were changed.
