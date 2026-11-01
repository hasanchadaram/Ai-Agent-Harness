package com.companion.ainotifications.dictation

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import com.companion.ainotifications.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import java.io.File

/** Debug-only native editors for insertion tests; absent from release APKs. */
class DictationEditorFixture : Activity() {
    lateinit var first: EditText
    lateinit var second: EditText
    private val checks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var preview: DictationOverlay? = null
    private var previewService: DictationAccessibilityService? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        first = OpaqueEditor(this).apply { hint = "First test editor"; setText("Hello world!"); setSingleLine(false) }
        second = EditText(this).apply { hint = "Second test editor"; setText("Keep this field unchanged.") }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
            if (intent.getBooleanExtra("dark_preview", false)) {
                setBackgroundColor(android.graphics.Color.rgb(18, 23, 32))
                first.setTextColor(android.graphics.Color.WHITE); second.setTextColor(android.graphics.Color.WHITE)
            }
            addView(first); addView(second)
        })
        first.requestFocus(); first.setSelection(6)
    }

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("focus_fixture", false) && !started) {
            started = true
            checks.launch {
                delay(400)
                val controller = DictationController.get(this@DictationEditorFixture)
                controller.settings.overlayEnabled = true
                DictationRecordingService.enableFromVisibleScreen(this@DictationEditorFixture)
                withTimeout(5000) { DictationRecordingService.readiness.first { it.ready } }
                first.requestFocus()
                withTimeout(3000) { while (!first.hasWindowFocus()) delay(20) }
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                delay(700)
                sendBroadcast(Intent(this@DictationEditorFixture, DictationFocusCheckReceiver::class.java))
            }
        } else if (intent.getBooleanExtra("startup_check", false) && !started) {
            started = true
            checks.launch { startupCheck() }
        } else if (intent.getBooleanExtra("run_checks", false) && !started) {
            started = true
            checks.launch { selfCheck() }
        } else if (intent.hasExtra("preview_phase") && !started) {
            started = true
            checks.launch {
                delay(400)
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                delay(700)
                val service = DictationAccessibilityService.connection.value ?: return@launch
                val phase = DictationPhase.valueOf(intent.getStringExtra("preview_phase")!!)
                val controller = DictationController.get(service)
                if (intent.getBooleanExtra("enable_overlay", false)) controller.settings.overlayEnabled = true
                previewService = service
                service.setOverlayPreviewActive(true)
                preview = DictationOverlay(service, controller, {})
                    .also { it.show(DictationState(phase = phase)) }
            }
        }
    }

    /** Real microphone start, immediately cancelled. No Stop/ASR request or audio file is created. */
    private suspend fun startupCheck() {
        val report = File(filesDir, "dictation-startup-check.txt")
        report.writeText("Testing microphone startup; audio discarded, no network\n")
        val controller = DictationController.get(this)
        try {
            controller.cancel()
            val service = withTimeout(5000) { DictationAccessibilityService.connection.filterNotNull().first() }
            controller.settings.overlayEnabled = true
            DictationRecordingService.enableFromVisibleScreen(this)
            withTimeout(5000) { DictationRecordingService.readiness.first { it.ready || it.error.isNotBlank() } }
            check(DictationRecordingService.isReady) { "Dictation service could not be armed" }
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            delay(900)
            val root = service.rootInActiveWindow
            val backgroundPackage = try { root?.packageName?.toString() } finally { root?.recycle() }
            check(backgroundPackage != null && backgroundPackage != packageName) { "App must be backgrounded for this test" }
            service.beginStartupCheck()
            val result = withTimeout(6000) {
                controller.state.first { it.phase == DictationPhase.RECORDING || it.phase == DictationPhase.ERROR }
            }
            check(result.phase == DictationPhase.RECORDING) { result.message }
            delay(200)
            controller.cancel()
            check(DictationRecordingService.isReady) { "Cancel must retain the idle ready service" }
            report.appendText("PASS Armed service records while app is backgrounded without a startup activity; Cancel retains readiness\nSUCCESS\n")
        } catch (error: Exception) {
            report.appendText("FAIL ${error.message}\n")
        } finally {
            controller.cancel()
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_dictation", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    /** Run inside the real app process: instrumentation would interrupt its accessibility service. */
    private suspend fun selfCheck() {
        val report = File(filesDir, "dictation-editor-check.txt")
        report.writeText("Running synthetic editor checks\n")
        val controller = DictationController.get(this)
        try {
            delay(400)
            val service = DictationAccessibilityService.connection.value ?: error("Accessibility service is disconnected")
            suspend fun capture(): DictationController.InsertionTarget {
                controller.cancel()
                first.setText("Hello world!"); first.requestFocus(); first.setSelection(6)
                delay(250)
                val target = service.captureInsertionTarget() ?: error("Fixture editor is not exposed")
                controller.prepare(target)
                return target
            }
            fun passed(name: String) { report.appendText("PASS $name\n") }

            var target = capture()
            check(target.insert("wonderful ").inserted) { "Insertion action failed" }
            check(first.text.toString() == "Hello wonderful world!") { "Existing text was not preserved" }
            check(first.selectionStart == 16) { "Cursor was not restored" }
            passed("Insert at cursor and preserve surrounding text")

            controller.cancel()
            first.setText("Hello world!"); first.requestFocus(); first.setSelection(6, 11)
            delay(250)
            target = service.captureInsertionTarget() ?: error("Selection editor not exposed")
            controller.prepare(target)
            check(target.insert("friend").inserted && first.text.toString() == "Hello friend!") { "Selected text replacement failed" }
            passed("Replace only selected text")

            target = capture()
            second.requestFocus(); delay(250)
            check(!target.insert("Blocked").inserted) { "Changed field accepted stale insertion" }
            check(second.text.toString() == "Keep this field unchanged.") { "Unrelated field was changed" }
            passed("Refuse changed field")

            target = capture()
            first.setSelection(7); delay(250)
            check(!target.insert("Blocked").inserted) { "Changed cursor accepted stale insertion" }
            passed("Refuse changed cursor")

            target = capture()
            first.setText("User changed this."); delay(250)
            check(!target.insert("Blocked").inserted) { "Edited field accepted stale insertion" }
            passed("Refuse changed text")

            target = capture()
            second.requestFocus(); delay(250)
            first.requestFocus(); first.setSelection(6); delay(250)
            check(!target.insert("Blocked").inserted) { "Returning revived a stale field" }
            passed("Refuse field switch followed by return")

            target = capture()
            controller.cancel()
            check(!target.insert("Blocked").inserted) { "Cancelled target accepted insertion" }
            passed("Refuse cancelled session")

            controller.cancel()
            first.setText(""); first.hint = "Message"; first.requestFocus(); first.setSelection(0)
            delay(250)
            val root = service.rootInActiveWindow
            val emptyNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            try {
                check(emptyNode != null && emptyNode.isShowingHintText) { "Empty editor must expose its placeholder" }
                report.appendText("Empty editor metadata: showingHint=${emptyNode.isShowingHintText}, selection=${emptyNode.textSelectionStart},${emptyNode.textSelectionEnd}\n")
            } finally { emptyNode?.recycle(); root?.recycle() }
            target = service.captureInsertionTarget() ?: error("Empty hinted editor not exposed")
            controller.prepare(target)
            val emptyOutcome = target.insert("Synthetic dictation.")
            check(emptyOutcome.inserted && first.text.toString() == "Synthetic dictation.") {
                "Empty hinted editor rejected insertion: ${emptyOutcome.message}"
            }
            passed("Insert into empty editor with placeholder and unavailable cursor")

            controller.cancel()
            first.setText(""); first.requestFocus(); first.setSelection(0)
            delay(250)
            target = service.captureInsertionTarget() ?: error("Empty editor not exposed")
            controller.prepare(target)
            first.setText("Message"); delay(250)
            check(!target.insert("Blocked").inserted && first.text.toString() == "Message") {
                "A real draft matching the placeholder was overwritten"
            }
            passed("Protect real text that matches the placeholder")
            suspend fun opaqueCapture(text: String, start: Int, end: Int = start): DictationController.InsertionTarget {
                controller.cancel()
                (first as OpaqueEditor).hideSelection = true
                first.setText(text); first.requestFocus(); first.setSelection(start, end)
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                delay(500)
                val result = service.capturePreparedInsertionTarget() ?: error("Opaque editor not exposed")
                controller.prepare(result)
                return result
            }
            target = opaqueCapture("", 0)
            val opaqueOutcome = target.insert("Synthetic dictation.")
            check(opaqueOutcome.inserted && first.text.toString() == "Synthetic dictation.") {
                "Hidden cursor empty insertion: ${opaqueOutcome.message}"
            }
            passed("Input connection inserts into empty editor with unflagged label and hidden cursor")

            target = opaqueCapture("Hello world!", 6)
            check(target.insert("wonderful ").inserted && first.text.toString() == "Hello wonderful world!") {
                "Input connection did not preserve existing draft"
            }
            passed("Input connection preserves draft around hidden cursor")

            target = opaqueCapture("Hello world!", 6, 11)
            check(target.insert("friend").inserted && first.text.toString() == "Hello friend!") {
                "Input connection selected replacement failed"
            }
            passed("Input connection replaces selection with hidden cursor")

            target = opaqueCapture("Hello world!", 6)
            first.setSelection(7); delay(300)
            check(!target.insert("Blocked").inserted) { "Hidden changed cursor accepted insertion" }
            passed("Input connection refuses changed hidden cursor")

            target = opaqueCapture("Hello world!", 6)
            first.setSelection(7); delay(300)
            first.setSelection(6); delay(300)
            check(!target.insert("Blocked").inserted) { "Returning revived hidden cursor" }
            passed("Input connection refuses hidden cursor movement followed by return")
            controller.cancel()
            val originalOverlayEnabled = controller.settings.overlayEnabled
            try {
                controller.settings.overlayEnabled = true
                val ime = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                first.requestFocus(); ime.showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                delay(700); DictationAccessibilityService.refreshOverlay()
                check(service.overlayVisible) { "Focused editor with keyboard must show mic" }
                passed("Show mic when editor and keyboard are visible")
                val savedPosition = service.overlayPosition
                ime.hideSoftInputFromWindow(first.windowToken, 0)
                delay(700); DictationAccessibilityService.refreshOverlay()
                check(first.hasFocus() && !service.overlayVisible) { "Mic remained after keyboard dismissal" }
                passed("Hide mic on keyboard dismissal even while editor retains focus")
                ime.showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                delay(700); DictationAccessibilityService.refreshOverlay()
                check(service.overlayVisible) { "Mic failed to reappear with keyboard" }
                passed("Show mic again when keyboard reopens")
                check(service.overlayPosition == savedPosition) { "Keyboard transition moved the saved position" }
                passed("Keep saved mic position after keyboard dismissal and reopening")

                repeat(3) {
                    var lastAlpha = service.overlayAlpha
                    check(lastAlpha > 0.95f) { "Mic must be fully visible before closing test" }
                    ime.hideSoftInputFromWindow(first.windowToken, 0)
                    repeat(45) {
                        delay(16)
                        val alpha = service.overlayAlpha
                        check(alpha <= lastAlpha + 0.025f) { "Dismissal blink: opacity increased from $lastAlpha to $alpha" }
                        lastAlpha = alpha
                    }
                    check(!service.overlayVisible) { "Closing fade did not release the mic window" }
                    ime.showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    delay(700)
                    check(service.overlayVisible) { "Mic did not return after closing test" }
                }
                passed("Three keyboard dismissals fade monotonically without blinking or reattaching")

                val probe = DictationOverlay(service, controller, {})
                service.setOverlayPreviewActive(true)
                try {
                    probe.show(DictationState())
                    delay(200)
                    check(probe.geometryValid()) { "Idle edge padding or placement is invalid" }
                    val position = probe.screenPosition
                    probe.show(DictationState(phase = DictationPhase.RECORDING))
                    delay(200)
                    check(probe.geometryValid() && probe.screenPosition.y == position.y) { "Recording moved the saved height" }
                    passed("Padded idle and listening controls retain fixed height")
                    val fixedRecordingPosition = probe.screenPosition
                    ime.hideSoftInputFromWindow(first.windowToken, 0)
                    repeat(20) {
                        delay(16)
                        check(probe.screenPosition == fixedRecordingPosition) { "Keyboard closing shifted actual overlay window" }
                    }
                    ime.showSoftInput(first, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    repeat(20) {
                        delay(16)
                        check(probe.screenPosition == fixedRecordingPosition) { "Keyboard opening shifted actual overlay window" }
                    }
                    passed("Actual overlay window stays fixed throughout keyboard opening and closing frames")
                    probe.show(DictationState(phase = DictationPhase.TRANSCRIBING))
                    delay(200)
                    check(probe.geometryValid() && probe.screenPosition.y == position.y) { "Spinner not centered or processing moved height" }
                    passed("Center processing spinner and retain fixed height")
                    val originalSide = controller.settings.overlaySide
                    try {
                        probe.show(DictationState()); delay(300)
                        val from = probe.screenPosition
                        controller.settings.overlaySide = if (originalSide == "left") "right" else "left"
                        probe.show(DictationState())
                        check(probe.dockingActive) { "Changing dock side skipped the animation" }
                        delay(50)
                        val middle = probe.screenPosition
                        delay(300)
                        val to = probe.screenPosition
                        check(middle.x in (minOf(from.x, to.x) + 1) until maxOf(from.x, to.x)) {
                            "Dock side transition teleported or never moved"
                        }
                        check(probe.geometryValid()) { "Animated dock did not reach the padded edge" }
                        passed("Dock side change moves through intermediate positions to the padded edge")

                        val delta = (80 * resources.displayMetrics.density).toInt() *
                            if (controller.settings.overlaySide == "left") 1 else -1
                        probe.dragForCheck(delta)
                        check(probe.dockingActive) { "Drag release skipped the snap animation" }
                        delay(50)
                        val returning = probe.screenPosition
                        delay(300)
                        check(probe.screenPosition == to && returning.x != to.x && probe.geometryValid()) {
                            "Drag release did not ease back to the chosen edge"
                        }
                        passed("Finger drag release eases into the edge instead of teleporting")
                    } finally { controller.settings.overlaySide = originalSide }
                    probe.hide(); delay(30)
                    probe.show(DictationState(phase = DictationPhase.CLEANING)); delay(200)
                    check(probe.isAttached && probe.geometryValid()) { "Reappearing during fade removed live controls" }
                    probe.hide(); delay(200)
                    check(!probe.isAttached) { "Faded controls retained their window" }
                    passed("Fade reversal keeps live controls and completed fade releases window")
                } finally { probe.hide(immediate = true); service.setOverlayPreviewActive(false) }
            } finally { controller.settings.overlayEnabled = originalOverlayEnabled }
            report.appendText("SUCCESS 25 checks; no microphone or network used\n")
        } catch (error: Exception) {
            report.appendText("FAIL ${error.message}\n")
        } finally {
            controller.cancel()
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_dictation", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    override fun onPause() {
        if (intent.getBooleanExtra("focus_fixture", false)) {
            // Reproduce note editors that explicitly dismiss their keyboard whenever paused.
            first.clearFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(first.windowToken, 0)
        }
        super.onPause()
    }

    override fun onStop() {
        preview?.hide(immediate = true)
        previewService?.setOverlayPreviewActive(false)
        super.onStop()
    }
    override fun onDestroy() { preview?.hide(immediate = true); checks.cancel(); super.onDestroy() }
}

/** Reproduces messaging editors that expose an unflagged label and selection -1,-1. */
private class OpaqueEditor(context: android.content.Context) : EditText(context) {
    var hideSelection = false
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (hideSelection) {
            info.text = if (text.isEmpty()) "Message" else text.toString()
            info.isShowingHintText = false
            info.hintText = null
            info.setTextSelection(-1, -1)
        }
    }
}
