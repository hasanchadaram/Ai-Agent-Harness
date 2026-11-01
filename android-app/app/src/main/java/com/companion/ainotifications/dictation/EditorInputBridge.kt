package com.companion.ainotifications.dictation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accessibility's editing API coexists with the user's keyboard (Android 13+). */
@RequiresApi(33)
class EditorInputBridge(
    service: AccessibilityService,
    private val onSelection: (String?, Int, Int) -> Unit
) : InputMethod(service) {
    private var generation = 0L
    private var selectionRevision = 0L

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        generation++
        super.onStartInput(attribute, restarting)
        onSelection(attribute.packageName, attribute.initialSelStart, attribute.initialSelEnd)
    }

    override fun onFinishInput() {
        generation++
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                                   newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) {
            selectionRevision++
            onSelection(currentInputEditorInfo?.packageName, newSelStart, newSelEnd)
        }
    }

    class Handle internal constructor(
        internal val generation: Long,
        internal val selectionRevision: Long,
        internal val fieldId: Int,
        internal val packageName: String,
        internal val connection: AccessibilityInputConnection
    )

    fun handle(expectedPackage: String): Handle? {
        val editor = currentInputEditorInfo ?: return null
        if (!currentInputStarted || editor.packageName != expectedPackage || password(editor.inputType)) return null
        return Handle(generation, selectionRevision, editor.fieldId, expectedPackage, currentInputConnection ?: return null)
    }

    fun isCurrent(handle: Handle): Boolean = generation == handle.generation && currentInputStarted &&
        currentInputEditorInfo?.packageName == handle.packageName && currentInputEditorInfo?.fieldId == handle.fieldId

    fun unchanged(handle: Handle): Boolean = isCurrent(handle) && selectionRevision == handle.selectionRevision

    // getSurroundingText may wait on the target app. Never perform that IPC on the UI thread.
    suspend fun read(handle: Handle): EditorInputSnapshot? = withContext(Dispatchers.IO) {
        val value = handle.connection.getSurroundingText(SNAPSHOT_LIMIT, SNAPSHOT_LIMIT, 0)
            ?: return@withContext null
        EditorInputSnapshot(value.text.toString(), value.selectionStart, value.selectionEnd, value.offset)
            .takeIf { it.valid }
    }

    fun commit(handle: Handle, text: String) {
        check(isCurrent(handle)) { "The input connection changed." }
        handle.connection.commitText(text, 1, null)
    }

    fun metadata(): String {
        val editor = currentInputEditorInfo
        return "started=$currentInputStarted package=${editor?.packageName} field=${editor?.fieldId} " +
            "initialSelection=${editor?.initialSelStart},${editor?.initialSelEnd} generation=$generation"
    }

    private fun password(inputType: Int): Boolean {
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val type = inputType and android.text.InputType.TYPE_MASK_CLASS
        return (type == android.text.InputType.TYPE_CLASS_TEXT && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (type == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    companion object { private const val SNAPSHOT_LIMIT = 32_768 }
}
