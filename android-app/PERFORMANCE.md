# Performance review and changes

Reviewed UI composition/list rendering, network requests, notification processing, saved memory access, audio capture, and accessibility/overlay lifecycles.

## Changes

- Dictation settings use a keyed LazyColumn rather than eagerly composing every setting and text field in a vertically scrolling Column. Endpoint edit state remains at screen scope so off-screen rows do not lose unsaved input.
- Feed/chat rows remember their parsed Markdown by content. The inline Markdown regex is compiled once, rather than once per line per composition. Lazy lists provide content types for reuse.
- UI StateFlow observers use lifecycle-aware collection. Background activities stop observing UI updates while the recording service remains independent.
- Chat history is bounded to 200 displayed messages; model history remains the last eight messages. List copies are now bounded instead of growing with the lifetime of the app. Initial positioning jumps directly to the latest message, and incoming assistant replies do not pull readers away from older messages.
- Memory file creation and reads are deferred until needed. The dialog loads/saves on Dispatchers.IO. Repeated agent requests reuse cached memory until an app edit or append invalidates it. Saved facts are not pruned or discarded.
- The notification agent uses cancellable asynchronous HTTP, closes response bodies on success and error, and releases connections for reuse. Its connection-test response previously remained open. Coroutine cancellation now cancels the actual socket call instead of leaving it waiting for its timeout.
- At most two notification tool loops perform inference concurrently; further notifications wait for a permit without being dropped. Counts and log updates handle concurrent tasks correctly. A sustained notification backlog can still accumulate pending coroutine work; this is not a persistent bounded queue.
- Dictation sessions and connection tests reuse the same OkHttp pools. Cleanup payload preparation runs off the UI thread.
- Accessibility skips window inspection when the overlay is disabled and no target is active, skips redundant idle text/selection events, and reuses one active-window snapshot per event. Active-session destination validation remains immediate. Preference changes for model settings or overlay coordinates no longer trigger visibility refreshes. Reconnection cancels the previous state observer before registering another.
- Android 13+ dictation insertion uses the active input connection when available, including editors that hide their cursor in accessibility nodes. Bounded editor snapshots are read on Dispatchers.IO at session start and insertion/verification. There is no new polling while scrolling or recording, and no keyboard replacement. A commit is issued once, then verified without retrying the write.
- Compact dictation controls show only with an eligible editor and visible keyboard, apart from an active session's Stop/Cancel controls. Keyboard window bounds are read on focus/window events; no timer polls for keyboard visibility. Layout updates occur only when size or saved screen position changes. Keyboard animation cannot move the saved screen position. Idle has one 48 dp button; recording expands horizontally to 248 by 48 dp with explicit Listening feedback. A small dot pulses only during visible recording, and the spinner runs only during startup/inference. Hide/destroy cancels the listening animation; there is no idle animation. Short show/hide fades respect the system animation setting.
- AudioRecord cleanup also runs if cancellation happens before the capture coroutine starts. Capture buffers start at one second of audio to avoid the initial series of small array expansions. The five-minute cap and whole-recording workflow remain in place.
- Release builds now enable R8 and resource shrinking. The locally signed `optimized` variant is non-debuggable, uses the existing app ID/debug signing key for in-place updates, and excludes debug fixtures/tooling. It is intended for local everyday testing, not store distribution with a debug key.

## Evidence and limits

Before the update, the phone's cumulative debug-process frame counters reported 40 missed frame deadlines out of 429 frames (9.32%), a 5 ms median, and a 32 ms 95th percentile. These counters mixed startup, overlay, and activity usage; they are not a controlled scrolling benchmark and cannot establish the speedup from this change.

The optimized APK is approximately 1.7 MB versus approximately 17 MB for the debug APK. Smaller APK size is a packaging result, not a measured frame-rate improvement.

All 21 JVM tests passed, covering the existing dictation protocol/client, input-connection snapshots, concurrent saved-memory writes/cache invalidation, HTTP response reuse on success/error, and actual request cancellation. Debug and optimized builds and lint are checked. The in-process editor fixture passed all 14 insertion safeguards without recording or network access. Initial attempts were blocked by the phone's lock screen; all cases passed once the phone was unlocked with the fixture visible.

Source review found concrete resource cleanup risks and removed them. It does not establish that the application is free of every possible leak; that would require repeated-session heap analysis. Scrolling improvement should be assessed on the optimized build during normal use, followed by a focused frame trace if stutters persist.

The overlay polish update passed 26 JVM tests. All 22 device fixture checks passed on the unlocked phone, including insertion safeguards, keyboard visibility, fixed saved placement, actual window coordinates sampled during keyboard animation, centered spinner geometry, fade reversal and hidden-window removal. Debug and optimized builds and lint passed. Synthetic listening/processing previews were inspected against a dark backdrop. No audio or network was used by these fixture checks.

The dismissal/docking follow-up passed the same 26 JVM tests and all 25 expanded device checks. Idle eligibility settles for 150 ms using a single cancellable task, eliminating repeated fade reversal from brief contradictory window snapshots. Opening and closing use matching 140 ms fades. Edge docking uses a 220 ms position animator only during a snap/side change; a new touch, hide or teardown cancels it. Window events generated by the animation retain the same destination instead of restarting it. Actual finger movement remains immediate. Tests sampled three keyboard dismissals for monotonic opacity, plus intermediate positions during side changes and drag release. Debug/optimized builds and lint passed.

The subsequent complete-surface fade animates window alpha instead of root-view alpha, with system window animations disabled. The short 140 ms transition updates window alpha only while showing/hiding; it introduces no idle polling. The 25 device checks passed with window alpha, and a separate real microphone start/cancel check passed while the app was backgrounded. The startup dialog is replaced by a transparent nonfocusable 1 by 1 pixel activity window. Video capture was blocked by phone policy, so root-view/property tests must not be described as frame-by-frame proof of the user's reported artifact.

The Notes focus follow-up removes that startup activity entirely. A microphone foreground service is prepared from the resumed Dictation screen and retained as **Dictation ready** while floating controls are enabled. Idle readiness has no AudioRecord, wake lock, timer, polling loop or network request; it retains a service object and silent foreground notification. This does not establish a measured battery cost. Session completion/cancellation releases the recorder and returns to readiness; disabling controls/accessibility stops the service. After reboot or a stopped service, the user opens Dictation to arm it again.

The 26 JVM tests and 25 editor/overlay checks passed with the new flow. A pause-sensitive synthetic editor and the actual ColorOS Notes editor both retained the same keyboard, field, text and cursor through a real recording start/cancel. No note text was inserted or changed and no audio left the phone. AppOps showed capture stopped after cancellation and no active microphone operation in idle readiness. Turning readiness off was checked for service shutdown and notification removal.

## Building

Use JDK 21 with the project's Gradle 8.11.1 distribution. Build `:app:assembleOptimized` for the everyday APK, or `:app:assembleDebug` for debug fixtures and `run-as` diagnostics. Install updates with `adb install -r`; do not uninstall or use Gradle's connected-test cleanup against the daily-use installation.

General Compose guidance: https://developer.android.com/develop/ui/compose/performance/bestpractices
