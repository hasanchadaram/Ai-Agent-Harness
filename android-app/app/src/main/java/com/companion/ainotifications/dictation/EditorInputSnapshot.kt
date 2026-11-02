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

    /** Verify the inserted range even if the editor returns a shifted/truncated surrounding window. */
    fun confirmsInsertion(expected: EditorInputSnapshot, insertedStart: Int, insertedLength: Int): Boolean {
        if (!valid || !expected.valid || insertedLength <= 0) return false
        if (confirmsEditorFormatting(expected, insertedStart, insertedLength)) return true
        if (absoluteStart != expected.absoluteStart || absoluteEnd != expected.absoluteEnd) return false
        val insertedEnd = insertedStart.toLong() + insertedLength
        val observedEnd = offset.toLong() + text.length
        val expectedEnd = expected.offset.toLong() + expected.text.length
        if (insertedStart < offset || insertedStart < expected.offset ||
            insertedEnd > observedEnd || insertedEnd > expectedEnd) return false
        val commonStart = maxOf(offset, expected.offset)
        val commonEnd = minOf(observedEnd, expectedEnd).toInt()
        return text.regionMatches(commonStart - offset, expected.text, commonStart - expected.offset,
            commonEnd - commonStart)
    }

    /** Notes/rich editors can add line breaks, NBSPs and paragraph padding on commit. */
    private fun confirmsEditorFormatting(expected: EditorInputSnapshot, insertedStart: Int, insertedLength: Int): Boolean {
        // Formatting can shift every later absolute position. Accept it only
        // with complete matching context from the beginning and the same logical
        // caret. Never turn a substring match or a submitted commit into success.
        if (offset != 0 || expected.offset != 0 || start != end || expected.start != expected.end ||
            insertedStart < 0 || insertedStart.toLong() + insertedLength > expected.text.length) return false
        val inserted = expected.text.substring(insertedStart, insertedStart + insertedLength)
        if (inserted.none { !it.isWhitespace() && it != '\u200B' }) return false
        fun canonical(value: String) = value.replace(Regex("[\\s\\p{Z}\\u200B]+"), " ").trim()
        return canonical(text) == canonical(expected.text) &&
            canonical(text.take(start)) == canonical(expected.text.take(expected.start))
    }
}
