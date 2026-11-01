package com.companion.ainotifications.dictation

/** Actual editor contents from InputConnection, not the accessibility display label. */
data class EditorInputSnapshot(val text: String, val start: Int, val end: Int, val offset: Int) {
    val valid: Boolean get() = start in 0..text.length && end in 0..text.length && offset >= 0
    val absoluteStart: Int get() = offset + start
    val absoluteEnd: Int get() = offset + end

    fun afterInsertion(inserted: String): EditorInputSnapshot {
        val (replacement, cursor) = DictationProtocol.insertAtSelection(text, start, end, inserted)
        return EditorInputSnapshot(replacement, cursor, cursor, offset)
    }
}
