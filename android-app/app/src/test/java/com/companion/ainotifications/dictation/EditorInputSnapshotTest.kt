package com.companion.ainotifications.dictation

import org.junit.Assert.*
import org.junit.Test

class EditorInputSnapshotTest {
    @Test fun notesParagraphPaddingCanShiftTheCursorWithoutLosingTheInsertion() {
        val expected = EditorInputSnapshot("Hello\n\nworld.", 13, 13, 0)
        val notes = EditorInputSnapshot("\nHello\n\n\nworld.\u00A0\n\n", 18, 18, 0)
        assertEquals(expected.text.length + 5, notes.text.length)
        assertTrue(notes.confirmsInsertion(expected, 0, expected.text.length))
    }

    @Test fun editorWhitespaceNormalizationMustPreserveWholeTextAndLogicalCaret() {
        val expected = EditorInputSnapshot("draft Hello world. suffix", 18, 18, 0)
        assertTrue(EditorInputSnapshot("draft\u00A0Hello\nworld. suffix", 18, 18, 0)
            .confirmsInsertion(expected, 6, 12))
        assertFalse(EditorInputSnapshot("draft Hello WRONG. suffix", 18, 18, 0)
            .confirmsInsertion(expected, 6, 12))
        assertFalse(EditorInputSnapshot("draft Hello world. suffix", 6, 6, 0)
            .confirmsInsertion(expected, 6, 12))
        assertFalse(EditorInputSnapshot("draft Helloworld. suffix", 17, 17, 0)
            .confirmsInsertion(expected, 6, 12))
    }

    @Test fun formattingCannotConfirmWhitespaceOnlyInsertOrShiftedUnknownContext() {
        assertFalse(EditorInputSnapshot("hello", 5, 5, 0)
            .confirmsInsertion(EditorInputSnapshot("hello ", 6, 6, 0), 5, 1))
        assertFalse(EditorInputSnapshot("Hello\nworld.", 12, 12, 10)
            .confirmsInsertion(EditorInputSnapshot("Hello world.", 12, 12, 10), 10, 12))
    }

    @Test fun usesActualEmptyInputRegardlessOfAccessibilityPlaceholder() {
        val empty = EditorInputSnapshot("", 0, 0, 0)
        assertTrue(empty.valid)
        assertEquals(EditorInputSnapshot("Dictated text.", 14, 14, 0), empty.afterInsertion("Dictated text."))
    }

    @Test fun preservesDraftAndSelectionUsingActualEditorPositions() {
        val draft = EditorInputSnapshot("Hello world!", 6, 11, 0)
        assertEquals(EditorInputSnapshot("Hello friend!", 12, 12, 0), draft.afterInsertion("friend"))
        assertNotEquals(draft, draft.copy(start = 7))
        assertNotEquals(draft, draft.copy(text = "User's edit!"))
        assertNotEquals(draft, draft.copy(offset = 4))
    }

    @Test fun absoluteSelectionIncludesSurroundingTextOffset() {
        val input = EditorInputSnapshot("a selected region", 2, 10, 100)
        assertEquals(102, input.absoluteStart)
        assertEquals(110, input.absoluteEnd)
        assertFalse(input.copy(start = -1).valid)
        assertFalse(input.copy(end = 100).valid)
        assertFalse(input.copy(offset = -1).valid)
    }

    @Test fun verifiesInsertionAcrossShiftedSurroundingTextWindows() {
        val expected = EditorInputSnapshot("prefix hello suffix", 12, 12, 100)
        assertTrue(EditorInputSnapshot("fix hello suff", 9, 9, 103)
            .confirmsInsertion(expected, 107, 5))
        assertTrue(EditorInputSnapshot("prefix hello suffix extra", 12, 12, 100)
            .confirmsInsertion(expected, 107, 5))
    }

    @Test fun refusesUnverifiedInsertionOrDifferentContextAndCursor() {
        val expected = EditorInputSnapshot("prefix hello suffix", 12, 12, 100)
        assertFalse(EditorInputSnapshot("fix hell suff", 9, 9, 103).confirmsInsertion(expected, 107, 5))
        assertFalse(EditorInputSnapshot("hello", 5, 5, 107).confirmsInsertion(expected, 107, 6))
        assertFalse(expected.copy(start = 11).confirmsInsertion(expected, 107, 5))
        assertFalse(expected.copy(text = "Prefix hello suffix").confirmsInsertion(expected, 107, 5))
    }
}
