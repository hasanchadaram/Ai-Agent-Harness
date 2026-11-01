# Private dictation: first working version

This version records one complete utterance, transcribes it on the PC, optionally polishes it once, and attempts insertion into the original editor. Chunking, background ASR during recording, and performance tuning are deferred.

## PC setup verified on October 31, 2026

- ASR: llama.cpp b11707 on `127.0.0.1:8081`, Qwen3-ASR-1.7B-Q8_0 GGUF and its matching audio projector.
- ASR model ID: `C:/Users/hasan/.lmstudio/models/Qwen/Qwen3-ASR-1.7B-Q8_0/Qwen3-ASR-1.7B-Q8_0.gguf`.
- Cleanup: llama.cpp b11707 on `127.0.0.1:8080`, `Qwen3.8-27B-GSQ-RCO-IQ3_XXS-mtp.gguf`, alias `qwen3.8-27b-mtp`.
- Existing tailnet-only cleanup route: `https://hasan-pc.tail99bd0f.ts.net/v1`.
- Added tailnet-only ASR route: `https://hasan-pc.tail99bd0f.ts.net:8443/v1`.

Both servers stay on PC loopback. Tailscale Serve supplies HTTPS inside the tailnet. No Funnel/public endpoint or cloud fallback is configured. Both phone and PC must be connected to Tailscale, and the tailnet policy must allow the phone to reach the PC on ports 443 and 8443.

To restore the private ASR route if needed:

```powershell
tailscale serve --bg --https=8443 http://127.0.0.1:8081
tailscale serve status
```

To remove only that added route: `tailscale serve --https=8443 off`. This leaves the existing cleanup route in place.

The Dictation screen allows separate URLs, model IDs, and optional API keys. URLs must be Tailscale IPs or full `.ts.net` names ending in `/v1`. Test Connection validates `/v1/models` and the configured model ID; the device smoke test also exercises inference. Android sends base64 WAV audio directly to `/v1/chat/completions` with `input_audio`. Cleanup uses its own text-only prompt with thinking disabled; notification memory and tools are never supplied.

## Phone setup

1. Install the optimized APK as an update; existing notification settings are preserved.
2. Open **AI Notifications → Dictation**.
3. Tap **Allow microphone**, then grant the Android microphone permission. Keep notifications enabled so the recording notification and Stop/Cancel actions are visible.
4. Read the accessibility disclosure, tap **Open Accessibility settings**, and explicitly enable **Private dictation controls**. Depending on the phone this is under Downloaded apps. If Android blocks a sideloaded app's service, open its App info menu and use **Allow restricted settings**, then return to Accessibility.
5. Return to Dictation, turn on **Show floating microphone**, confirm **Dictation ready**, and use **Test connection**. Enabling prepares the microphone service while this app is visible; it does not capture audio.
6. Open a text field in WhatsApp, YouTube, a browser, ChatGPT, or Gemini. Tap **Mic**, wait for **Listening** in the floating controls, then speak. Tap the checkmark to transcribe, optionally polish, and insert. There is no separate startup text panel or dimmed background.

The floating controls are an accessibility overlay, so a second “draw over other apps” permission is not needed. Gboard remains the active keyboard. The idle control is one 48 dp mic button, visible only while a non-password editor is focused and an on-screen keyboard is visible. Closing the keyboard hides it even if the field retains focus or an old transcript/error remains. Locking the phone also hides it.

A quiet, ongoing **Dictation ready** notification keeps the service available between recordings. Its idle text explicitly says that the microphone is off. AudioRecord exists only during an explicit recording; Stop/Cancel release it, and completing/cancelling a session returns the service to idle readiness. **Turn off** in the ready notification, disabling the floating mic, or removing the accessibility service stops readiness too. After a phone restart or a stopped/killed service, open **Dictation** once to prepare it again. An unarmed overlay tap shows an instruction to open Dictation; it never silently launches an activity over the editor.

Tap the mic to start. The control expands to a 248 by 48 dp pill: Cancel, a pulsing red dot with an explicit **Listening** label, a **Polish** toggle, and a checkmark to finish/transcribe. Listening appears only when the controller is actually recording; startup retains a separate Starting state. Processing uses a smaller pill with a centered 24 dp spinner, a fixed circular track, and Cancel. Successful insertion returns to the single mic. Copy is offered only when a transcript exists and insertion did not succeed; Copy or dismiss then collapses the control. Detailed transcripts and errors remain in the Dictation screen rather than filling the overlay. Active recording/processing controls remain available if the keyboard is dismissed, so Stop/Cancel are still reachable.

Select **Dock microphone: Left / Right** in Dictation, or drag the mic and release to snap to the nearest side with a 12 dp gap (plus any cutout safety margin). Its height is stored in screen dp and remains fixed across keyboard animation and editor changes; keyboard bounds govern visibility only. The first position is 42% down the screen, and dragging saves a new height. Rotation clamps to screen safety bounds without overwriting the saved height. Old keyboard-relative offsets are no longer used.

Idle visibility must settle for 150 ms before a fade starts, so transient keyboard/window snapshots cannot repeatedly reverse the fade during dismissal. Showing and hiding both fade over 140 ms without moving the control. Active recording controls appear immediately; locking the phone, disabling the overlay, service teardown and debug preview suppression still hide immediately. Visibility settling uses one cancellable delayed task per pending change, not a polling loop.

Fades now animate WindowManager.LayoutParams.alpha for the complete overlay surface, including the mic glyph, border and shadow. The root view remains opaque; the user-adjustable background fill opacity is independent. System window enter/exit animations are explicitly disabled so they cannot run a second transition. Earlier checks sampled a root-view alpha property and did not prove the complete rendered surface faded correctly. Window-level alpha is now sampled by the same dismissal tests. The attempted screen-recording diagnostic stalled without capturing frames. The phone denied stopping the two native recorder processes; the user restarted the phone, after which both processes were confirmed gone and accessibility reconnected. Frame-by-frame visual confirmation is not claimed.

Dragging follows the finger directly. On release, controls ease into the nearest edge over 220 ms; changing the dock side also animates across the screen. A new touch interrupts docking at the current position. Preference callbacks cannot teleport the window during a drag, and window events generated by the motion do not restart it. Hiding or teardown cancels the docking animator. Disabling system animations also disables docking/fades and the listening pulse.

The background is light with **50% opacity** by default. **Background opacity** in Dictation settings adjusts it from 20% to 100%; lower values are more transparent. Only the surface fill changes opacity, keeping icons and text fully opaque. Disable **Show floating microphone** in the app to turn it off. Stop and Cancel are also available in the foreground-service notification. **Try dictation in this app** records into the result panel without cross-app insertion.

## Behavior and limits

- One recording, one ASR request, optional one cleanup request. The Polish switch's value at Stop is used. Changing it while recording is supported.
- Mono 16 kHz, signed 16-bit PCM, wrapped in WAV. Capture continues independently of the network because networking starts after Stop.
- Five-minute recording cap bounds memory use; reaching the cap automatically finishes capture and processes it. Short recordings are flushed at Stop.
- Original and polished transcripts remain in process memory for review/copy. **Use original** selects the original for copying; it does not rewrite a field that may have subsequently changed.
- Cleanup failure retains the original for review/copy. Network failures show errors and allow a new recording. Audio is not retained for retry after failure in this version.
- Cancel invalidates the session, stops/releases the microphone, cancels the HTTP call, and prevents late results from inserting. Stop finishes; Cancel discards.
- Insertion checks the original accessibility node, window, focus, complete text, and selection. Detected app/field/cursor/content changes invalidate that destination even if the user later returns. If identity or insertion cannot be confirmed, the transcript is retained for Copy. There is no automatic paste into a newly focused field.
- On Android 13+, accessibility also connects to the active editor through `InputMethod.AccessibilityInputConnection`. This coexists with Gboard without replacing the keyboard. A bounded surrounding-text snapshot supplies the actual selection even when the accessibility node reports `-1, -1` or an unflagged placeholder. The original node, window, editor identity, text and selection must still match before one `commitText` call. Selection changes invalidate the destination even if the cursor returns. The result is read back; an uncertain acknowledgement never triggers a second commit. Blocking editor reads run on Dispatchers.IO at dictation start and insertion/verification, not while scrolling.
- Older Android versions and editors without a usable input connection use `ACTION_SET_TEXT` where supported. The replacement preserves surrounding text and restores the cursor where possible. Unknown positions in a nonempty accessibility node are never guessed. Web editors and app versions differ; there is no guarantee for every app/field. Nothing is sent/submitted automatically.
- Password fields are excluded. The accessibility service has broad Android screen access, but reads focused-editor metadata/text locally; screen contents are not sent to the PC.
- Audio and transcripts are not written to files or logs. Audio is held temporarily in RAM; transcripts disappear on a new recording, cancellation, service removal, or process exit. Copy explicitly uses the system clipboard, whose behavior depends on Android/keyboard settings.
- The microphone foreground service is armed from the resumed Dictation screen to meet Android's while-in-use permission rules. Overlay taps send a recording command to that existing service. No recording-start activity is launched, so hosts such as ColorOS Notes are not paused and do not dismiss their keyboard. The previous transparent 1 by 1 pixel startup activity was removed: transparency/nonfocusability did not prevent a host activity from being paused. This change preserves the original focus/selection guards rather than restoring focus to a possibly changed editor. Android reference: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start .
- Timing labels distinguish audio preparation, complete ASR/cleanup requests, insertion attempt, and Stop-to-result. They are not network-only or server-only measurements, and do not claim to measure the target app's rendering latency.

## Build and verification

The local Android Studio JBR is Java 25, which this project's Gradle 8.11.1 cannot use. Use the existing JDK 21 at `C:\Users\hasan\.jdks\jbr-21.0.11` for this project. The repository currently has wrapper properties but no `gradlew` scripts/wrapper JAR; the installed Gradle distribution can build it:

```powershell
$env:JAVA_HOME = 'C:\Users\hasan\.jdks\jbr-21.0.11'
& 'C:\Users\hasan\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat' :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Unit tests cover audio packaging, selection replacement, endpoint privacy checks, Qwen metadata, request structure, model mismatch, truncated results, server failures, and HTTP cancellation.

The optional `PrivatePcConnectionTest` instrumentation test exercises the phone-to-PC routes with synthetic data without opening the microphone. It requires the local servers and Tailscale. `EditorInsertionTest` uses a debug-only fixture and requires the user-enabled accessibility service. Neither test sends messages or starts microphone capture.

On a daily-use phone, build the APKs with `:app:assembleDebug :app:assembleDebugAndroidTest`, install them with `adb install -r` / `adb install -r -t`, re-enable accessibility if the phone disables it on update, and run the installed test runner directly:

```powershell
adb shell am instrument -w -r com.companion.ainotifications.test/androidx.test.runner.AndroidJUnitRunner
```

This leaves the app installed. Avoid `connectedDebugAndroidTest` on this phone: the Gradle device runner removed the target app during cleanup in verification, which reset local app settings and permission toggles. Both Gradle and direct runs passed the two PC connection tests. Four insertion checks were skipped: starting instrumentation interrupts the main-process accessibility service and Android marks it as disconnected/crashed. Refreshing the APK with `adb install -r` restores that binding without clearing app data or permission grants. Use manual cross-app acceptance for insertion until the test harness is isolated from the running service.

A debug-only self-check now tests the live service without instrumentation. The fixture is protected by the shell/system `DUMP` permission and does not exist in release builds:

```powershell
adb shell am start -W -n com.companion.ainotifications/.dictation.DictationEditorFixture --ez run_checks true
adb shell run-as com.companion.ainotifications cat files/dictation-editor-check.txt
```

An empty-field regression was reproduced on the connected Android 16 phone: Android exposed the placeholder as node text with selection `-1, -1`, which the original implementation mistook for a nonempty draft. The insertion path now treats text explicitly marked `isShowingHintText` as empty and uses position zero. It does not guess the cursor for a nonempty draft, or infer emptiness by comparing text to the placeholder string. Focus, field identity, and content-change guards remain in effect.

The initial nine self-check cases cover insertion at cursor, selected text, changed field/cursor/text, switch-and-return, cancellation, empty hinted editors and drafts matching a placeholder. A real WhatsApp failure then exposed an additional case: the focused EditText reported seven characters, no hint flag and selection `-1, -1`. A blinking visual cursor does not guarantee accessible selection metadata.

The self-check now adds five input-connection cases with those deliberately obscured accessibility properties: empty insertion, preserving a draft, replacing selected text, rejecting changed cursor and rejecting cursor movement followed by return. All 14 cases passed on the connected Android 16 phone without microphone or network use. All 21 JVM tests passed. Debug and optimized builds and lint are also checked. Real WhatsApp/Telegram dictation still needs manual acceptance after this update.

The compact-overlay update adds JVM regressions for keyboard dismissal while editor focus remains, active-session/lock-screen visibility, and Copy eligibility. Placement tests cover left/right gaps, expansion without height changes, and rotation clamping (26 JVM tests total, all passed). The live fixture passed all 25 checks on the unlocked phone, including keyboard show/dismiss/reopen, retaining position across those transitions, idle/listening geometry, centered processing, fade reversal, and releasing a hidden window. Actual view coordinates were also sampled every 16 ms for 20 samples during each keyboard closing/opening transition and stayed fixed. Three consecutive keyboard dismissals were sampled for nonincreasing opacity and final window removal; dock-side changes and synthetic finger-drag releases were checked for intermediate animation positions and correct final placement. These checks use synthetic text without microphone capture or network calls.

Debug-only preview states can be launched with `--es preview_phase RECORDING` (or another DictationPhase name) instead of `--ez run_checks true`; `--ez dark_preview true` supplies a dark test backdrop. The service's own overlay is temporarily suppressed while this synthetic preview is visible, without changing saved overlay preferences. These fixtures are excluded from the installed optimized variant. Visual recording and processing previews were inspected against a dark backdrop; real speech and third-party editor compatibility still require manual acceptance.

The shell-protected debug mode `--ez startup_check true` arms readiness while the fixture is foreground, brings Android Settings forward, starts recording through the existing service, and cancels while checking that readiness remains. Its metadata-only result is in `files/dictation-startup-check.txt`. The older startup-window test passed before removal of that window; it did not establish keyboard preservation.

`--ez focus_fixture true` uses a synthetic editor that deliberately clears focus/hides the keyboard in onPause. A real start/cancel passed with the same editor, text, cursor and keyboard retained. A shell-protected `DictationFocusCheckReceiver` also ran that check in the actual ColorOS Notes app (`com.coloros.note`) and passed without inserting or altering note text. Audio was discarded in RAM; no Stop/ASR/cleanup call or audio file was made. Android AppOps confirmed no active microphone operation after cancellation and while readiness was idle. A separate diagnostic confirmed disabling readiness stops the service and removes its notification. These debug-only helpers are absent from optimized builds.

On-demand `adb shell dumpsys activity service com.companion.ainotifications/.dictation.DictationAccessibilityService` diagnostics report package/field/window identifiers, lengths, selection positions and insertion state, without text or audio contents. No continuous logging is added. Android API reference: https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection .

Manual acceptance: test a short sentence; several minutes of speech; deliberate repetition; Polish on/off and changing it before Stop; cancel during recording/ASR/cleanup; switch apps/fields/cursor during processing; edit existing text; replace selected text; unavailable PC/Tailscale; unsupported web editors; deny/revoke microphone permission; dismiss/re-enable the overlay. Verify that no text is ever submitted as a message automatically.
