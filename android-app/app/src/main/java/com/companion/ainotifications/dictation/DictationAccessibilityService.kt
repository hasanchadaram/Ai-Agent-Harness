package com.companion.ainotifications.dictation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.*
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-enabled accessibility supplies the floating controls and best-effort editor insertion. */
class DictationAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = DictationController.get(this)
    private var overlay: DictationOverlay? = null
    private var previewingOverlay = false
    internal fun setOverlayPreviewActive(value: Boolean) {
        previewingOverlay = value
        updateOverlay()
    }
    internal val overlayVisible get() = overlay?.isAttached == true
    internal val overlayPosition get() = overlay?.screenPosition
    internal val keyboardVisible get() = keyboardTop != null
    private var target: EditorTarget? = null
    private var writing = false
    private var lastEditorAvailable = false
    private var keyboardTop: Int? = null
    private var insertionDiagnostic = "No target captured"
    private var inputBridge: EditorInputBridge? = null
    private val inputMethodPackages by lazy {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }
    private var stateObserver: Job? = null
    private var visibilityJob: Job? = null
    private var pendingVisibility: Boolean? = null
    private var committedVisibility = false
    internal val overlayAlpha get() = overlay?.displayedAlpha ?: 0f
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "overlay" || key == "overlay_side" || key == "overlay_opacity") {
            scope.launch { refreshEditorVisibility() }
        }
    }

    override fun onServiceConnected() {
        stateObserver?.cancel()
        cancelVisibilityChange()
        committedVisibility = false
        overlay?.hide(immediate = true)
        connected.value = this
        getSharedPreferences("private_dictation", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        overlay = DictationOverlay(this, controller, onRecord = { begin() })
        stateObserver = scope.launch {
            var wasBusy = controller.state.value.busy
            controller.state.collect { state ->
                val finished = wasBusy && !state.busy
                wasBusy = state.busy
                // Insertion suppresses events while writing; reconcile any keyboard transition now.
                if (finished) refreshEditorVisibility() else updateOverlay()
            }
        }
        refreshEditorVisibility()
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = EditorInputBridge(this) { editorPackage, start, end ->
        if (!writing && controller.state.value.busy) {
            target?.selectionChanged(editorPackage, start, end)
        }
    }.also { inputBridge = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || writing) return
        if (!controller.settings.overlayEnabled && target == null) return
        // Text changes in an already focused idle editor cannot affect visibility.
        if (target == null && lastEditorAvailable &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) return
        val sourcePackage = event.packageName?.toString()
        val windowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // Keyboard window transitions are essential even when the editor retains focus on dismissal.
        if (sourcePackage in inputMethodPackages && !windowEvent) return
        val overlayEvent = event.source?.let { source ->
            try {
                source.window?.let { window ->
                    try { window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    finally { window.recycle() }
                } ?: false
            } finally { source.recycle() }
        } ?: false
        if (overlayEvent) return
        if (windowEvent || event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) updateKeyboardBounds()
        val root = rootInActiveWindow
        try {
            val rootPackage = root?.packageName?.toString()
            val current = focusedEditor(root)
            lastEditorAvailable = current != null
            val captured = target
            if (captured != null && controller.state.value.busy) {
                // Own startup activity and keyboard windows are deliberately ignored. External
                // focus/text/selection transitions latch invalidation even if the user returns later.
                if (rootPackage != null && rootPackage !in inputMethodPackages) {
                    if (current == null || !captured.sameEditorAndContents(current)) captured.invalidate()
                }
            }
            current?.recycle()
            updateOverlay()
        } finally { root?.recycle() }
    }

    private fun focusedEditor(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        try {
            return focusedEditor(root)
        } finally { root.recycle() }
    }

    private fun focusedEditor(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (node.isEditable && !sensitive(node)) return node
        node.recycle()
        return null
    }

    private fun sensitive(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val type = node.inputType and InputType.TYPE_MASK_CLASS
        return node.isPassword || (type == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun editorText(node: AccessibilityNodeInfo) =
        DictationProtocol.editorText(node.text?.toString(), node.isShowingHintText)

    private fun metadata(node: AccessibilityNodeInfo?): String = if (node == null) "no focused editor" else
        "package=${node.packageName} class=${node.className} window=${node.windowId} " +
            "focused=${node.isFocused} textLength=${node.text?.length ?: 0} showingHint=${node.isShowingHintText} " +
            "hasHint=${node.hintText != null} textMatchesHint=${node.hintText != null && node.text?.toString() == node.hintText?.toString()} " +
            "selection=${node.textSelectionStart},${node.textSelectionEnd} actions=${node.actionList.joinToString { it.id.toString() }}"

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Private dictation metadata (no text/audio contents)")
        writer.println("phase=${controller.state.value.phase}")
        writer.println("overlay: attached=${overlay?.isAttached} editor=$lastEditorAvailable keyboardTop=$keyboardTop side=${controller.settings.overlaySide} ${overlay?.placementMetadata()}")
        val current = focusedEditor()
        try { writer.println("current: ${metadata(current)}") } finally { current?.recycle() }
        writer.println("last insertion: $insertionDiagnostic")
        if (Build.VERSION.SDK_INT >= 33) writer.println("input: ${inputBridge?.metadata()}")
    }

    private fun begin() {
        if (controller.state.value.busy) return
        val captured = captureInsertionTarget() as? EditorTarget
        val id = controller.prepare(captured)
        scope.launch {
            try {
                captured?.prepareInput()
                if (controller.state.value.id != id || controller.state.value.phase != DictationPhase.STARTING) return@launch
                DictationRecordingService.startExistingSession(this@DictationAccessibilityService, id)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                controller.startupFailed(id, "Cannot prepare dictation input. Please focus the field and try again.")
            }
        }
    }

    /** Invoked only by the shell-protected debug fixture; release builds have no caller. */
    internal fun beginStartupCheck() { begin() }

    internal fun captureInsertionTarget(): DictationController.InsertionTarget? {
        // Keep a node identity, complete text, and selection only for this session, in memory.
        target = focusedEditor()?.let { EditorTarget(it) }
        return target
    }

    internal suspend fun capturePreparedInsertionTarget(): DictationController.InsertionTarget? {
        val captured = captureInsertionTarget() as? EditorTarget
        captured?.prepareInput()
        return captured
    }

    private fun updateOverlay() {
        val settings = controller.settings
        val state = controller.state.value
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val show = !previewingOverlay && DictationOverlayPolicy.visible(settings.overlayEnabled, lastEditorAvailable,
            keyboardTop != null, state.busy, locked)
        if (previewingOverlay || locked || !settings.overlayEnabled) {
            cancelVisibilityChange()
            committedVisibility = false
            overlay?.hide(immediate = true)
            return
        }
        if (state.busy) {
            cancelVisibilityChange()
            committedVisibility = true
            overlay?.show(state) // Stop/Cancel must remain immediately available.
            return
        }
        if (committedVisibility) overlay?.show(state)
        if (show == committedVisibility) {
            cancelVisibilityChange()
            return
        }
        if (pendingVisibility == show && visibilityJob?.isActive == true) return
        cancelVisibilityChange()
        pendingVisibility = show
        // IME/window snapshots can alternate briefly during keyboard dismissal. Settle the
        // eligibility decision before starting a fade, rather than repeatedly reversing it.
        visibilityJob = scope.launch {
            delay(150)
            visibilityJob = null; pendingVisibility = null
            committedVisibility = show
            if (show) overlay?.show(controller.state.value) else overlay?.hide()
        }
    }

    private fun cancelVisibilityChange() {
        visibilityJob?.cancel(); visibilityJob = null; pendingVisibility = null
    }

    private fun refreshEditorVisibility() {
        updateKeyboardBounds()
        val current = focusedEditor()
        lastEditorAvailable = current != null
        current?.recycle()
        updateOverlay()
    }

    private fun updateKeyboardBounds() {
        var top: Int? = null
        val bounds = Rect()
        // Visible interactive windows only. No text traversal and no polling loop.
        for (window in windows) {
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    window.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && bounds.bottom > 0 && bounds.top >= 0) {
                        top = minOf(top ?: bounds.top, bounds.top)
                    }
                }
            } finally { window.recycle() }
        }
        keyboardTop = top
    }

    private inner class EditorTarget(private val node: AccessibilityNodeInfo) : DictationController.InsertionTarget {
        init { insertionDiagnostic = "captured: ${metadata(node)}" }
        private val original = editorText(node)
        private val selectionStart = if (original.isEmpty()) 0 else node.textSelectionStart
        private val selectionEnd = if (original.isEmpty()) 0 else node.textSelectionEnd
        private val window = node.windowId
        private val editorPackage = node.packageName?.toString().orEmpty()
        private var actualInput: EditorInputSnapshot? = null
        private var inputFieldId: Int? = null
        private var invalid = false
        private var released = false
        fun invalidate() { invalid = true }
        fun selectionChanged(changedPackage: String?, start: Int, end: Int) {
            val snapshot = actualInput ?: return
            if (changedPackage == editorPackage && start >= 0 && end >= 0 &&
                (start != snapshot.absoluteStart || end != snapshot.absoluteEnd)) invalid = true
        }

        suspend fun prepareInput() {
            if (Build.VERSION.SDK_INT < 33 || released) return
            val bridge = inputBridge ?: return
            val handle = bridge.handle(editorPackage) ?: return
            val snapshot = bridge.read(handle) ?: return
            if (released) return
            if (!bridge.unchanged(handle) || !stillMatches()) { invalid = true; return }
            actualInput = snapshot
            inputFieldId = handle.fieldId
            insertionDiagnostic += "; actualLength=${snapshot.text.length} actualSelection=${snapshot.absoluteStart},${snapshot.absoluteEnd}"
        }

        private fun stillMatches(): Boolean {
            val current = focusedEditor()
            return try { !invalid && current != null && sameEditorAndContents(current) }
                finally { current?.recycle() }
        }
        fun sameEditorAndContents(current: AccessibilityNodeInfo): Boolean = !released &&
            node == current && current.windowId == window && current.isFocused && current.isEditable &&
            !sensitive(current) && editorText(current) == original &&
            (if (original.isEmpty()) 0 else current.textSelectionStart) == selectionStart &&
            (if (original.isEmpty()) 0 else current.textSelectionEnd) == selectionEnd

        override suspend fun insert(text: String): DictationController.InsertionOutcome {
            if (Build.VERSION.SDK_INT >= 33 && actualInput != null) return insertThroughInputConnection(text)
            val current = focusedEditor()
            insertionDiagnostic = "capture: ${metadata(node)}; current: ${metadata(current)}; invalid=$invalid released=$released"
            try {
                if (invalid || current == null || !sameEditorAndContents(current) || !node.refresh()) {
                    return DictationController.InsertionOutcome(false, "The app, field, text, or cursor changed. Transcript retained; use Copy.")
                }
                val (replacement, cursor) = try {
                    DictationProtocol.insertAtSelection(original, selectionStart, selectionEnd, text)
                } catch (_: IllegalArgumentException) {
                    return DictationController.InsertionOutcome(false, "The editor did not expose its cursor. Use Copy to paste the transcript.")
                }
                if (current.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                    return DictationController.InsertionOutcome(false, "This editor does not support accessibility insertion. Use Copy.")
                }
                writing = true
                val success = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
                })
                if (!success) return DictationController.InsertionOutcome(false, "The editor declined insertion. Transcript retained; use Copy.")
                current.refresh()
                // Avoid a second write on failure: an app may accept the action asynchronously.
                if (editorText(current) != replacement) {
                    return DictationController.InsertionOutcome(false, "Insertion could not be confirmed. Check the field before using Copy.")
                }
                current.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
                return DictationController.InsertionOutcome(true, "Inserted. Transcript is also available in Dictation.")
            } finally { writing = false; current?.recycle() }
        }

        @RequiresApi(33)
        private suspend fun insertThroughInputConnection(text: String): DictationController.InsertionOutcome {
            if (!stillMatches()) return changedDestination()
            val originalInput = actualInput ?: return changedDestination()
            val bridge = inputBridge ?: return changedDestination()
            val handle = bridge.handle(editorPackage) ?: return changedDestination()
            if (handle.fieldId != inputFieldId) return changedDestination()
            val before = bridge.read(handle)
            if (!bridge.unchanged(handle) || !stillMatches() || before != originalInput) return changedDestination()
            val expected = originalInput.afterInsertion(text)
            writing = true
            try {
                // Commit only once. A delayed/uncertain acknowledgement must never cause a duplicate paste.
                bridge.commit(handle, text)
                insertionDiagnostic = "InputConnection commit submitted; package=$editorPackage"
                repeat(4) {
                    delay(50)
                    if (released || !bridge.isCurrent(handle)) return unconfirmedInsertion()
                    val after = bridge.read(handle) ?: return unconfirmedInsertion()
                    if (bridge.isCurrent(handle) && after == expected) {
                        insertionDiagnostic = "InputConnection insertion verified; package=$editorPackage"
                        return DictationController.InsertionOutcome(true, "Inserted. Transcript is also available in Dictation.")
                    }
                }
                return unconfirmedInsertion()
            } finally { writing = false }
        }

        private fun changedDestination() = DictationController.InsertionOutcome(false,
            "The app, field, text, cursor, or input connection changed. Transcript retained; use Copy.")

        private fun unconfirmedInsertion() = DictationController.InsertionOutcome(false,
            "Insertion was requested but could not be confirmed. Check the field before using Copy.")

        override fun release() {
            if (!released) { released = true; node.recycle() }
            if (target === this) target = null
        }
    }

    override fun onInterrupt() { controller.cancel() }
    override fun onDestroy() {
        cancelVisibilityChange()
        DictationRecordingService.disable(this)
        overlay?.hide(immediate = true); overlay = null
        getSharedPreferences("private_dictation", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (connected.value === this) connected.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val connected = MutableStateFlow<DictationAccessibilityService?>(null)
        val connection = connected.asStateFlow()
        fun refreshOverlay() { connected.value?.refreshEditorVisibility() }
    }
}
