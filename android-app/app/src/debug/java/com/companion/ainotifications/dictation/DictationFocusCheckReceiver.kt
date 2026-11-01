package com.companion.ainotifications.dictation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Shell-only diagnostic: leaves editor contents untouched and cancels before any network call. */
class DictationFocusCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val report = File(context.filesDir, "dictation-focus-check.txt")
            val controller = DictationController.get(context)
            var session = ""
            var original: AccessibilityNodeInfo? = null
            try {
                report.writeText("Testing editor focus; temporary audio discarded, no network or insertion\n")
                check(!controller.state.value.busy) { "A dictation is already running" }
                check(DictationRecordingService.isReady) { "Open Dictation once to arm the service" }
                if (intent.getBooleanExtra("disable_ready_check", false)) {
                    val enabled = controller.settings.overlayEnabled
                    try {
                        controller.settings.overlayEnabled = false
                        DictationRecordingService.disable(context)
                        withTimeout(3000) {
                            while (DictationRecordingService.isReady || context.getSystemService(android.app.NotificationManager::class.java)
                                    .activeNotifications.any { it.id == 4301 }) delay(20)
                        }
                        report.appendText("PASS Disabling controls stops the ready service and removes its notification\nSUCCESS\n")
                    } finally { controller.settings.overlayEnabled = enabled }
                    return@launch
                }
                val service = DictationAccessibilityService.connection.value ?: error("Accessibility disconnected")
                if (intent.getBooleanExtra("focus_editor", false)) {
                    val root = service.rootInActiveWindow
                    try {
                        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val needsFocus = focused?.isEditable != true || !service.keyboardVisible
                        focused?.recycle()
                        if (needsFocus && root != null) {
                            var remaining = 200
                            fun findEditor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                                if (--remaining < 0) return null
                                if (node.isEditable && !node.isPassword) return AccessibilityNodeInfo.obtain(node)
                                for (index in 0 until node.childCount) {
                                    val child = node.getChild(index) ?: continue
                                    val match = try { findEditor(child) } finally { child.recycle() }
                                    if (match != null) return match
                                }
                                return null
                            }
                            val editor = findEditor(root) ?: error("No editable field; open note editing first")
                            try {
                                val bounds = android.graphics.Rect()
                                editor.getBoundsInScreen(bounds)
                                val focusedNow = editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                val clicked = editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                report.appendText("Editor metadata: package=${editor.packageName} bounds=$bounds focusAction=$focusedNow clickAction=$clicked\n")
                            } finally { editor.recycle() }
                        }
                    } finally { root?.recycle() }
                    delay(700)
                }
                val root = service.rootInActiveWindow
                original = try { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root?.recycle() }
                val before = original ?: error("Focus the note editor first")
                check(before.isEditable && !before.isPassword && service.keyboardVisible) { "Editor and keyboard must be active" }
                // Contents are compared only in RAM, never written to the report.
                val text = before.text?.toString()
                val start = before.textSelectionStart; val end = before.textSelectionEnd
                service.beginStartupCheck()
                session = controller.state.value.id
                val state = withTimeout(5000) {
                    controller.state.first { it.phase == DictationPhase.RECORDING || it.phase == DictationPhase.ERROR }
                }
                check(state.phase == DictationPhase.RECORDING) { state.message }
                delay(250)
                val afterRoot = service.rootInActiveWindow
                val after = try { afterRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { afterRoot?.recycle() }
                try {
                    check(after != null && after == before && after.isFocused) { "Recording startup changed editor focus" }
                    check(after.text?.toString() == text && after.textSelectionStart == start && after.textSelectionEnd == end) {
                        "Recording startup changed editor contents or selection"
                    }
                    check(service.keyboardVisible) { "Recording startup closed the keyboard" }
                    report.appendText("PASS package=${before.packageName}: same editor, keyboard, text and cursor retained\nSUCCESS\n")
                } finally { after?.recycle() }
            } catch (error: Exception) {
                report.appendText("FAIL ${error.message}\n")
            } finally {
                if (session.isNotEmpty() && controller.state.value.id == session) controller.cancel()
                original?.recycle()
                result.finish()
                scope.cancel()
            }
        }
    }
}
