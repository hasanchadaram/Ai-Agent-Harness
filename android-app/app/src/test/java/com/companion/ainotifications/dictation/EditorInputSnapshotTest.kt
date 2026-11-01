package com.companion.ainotifications.dictation

import org.junit.Assert.*
import org.junit.Test

class EditorInputSnapshotTest {
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
}
