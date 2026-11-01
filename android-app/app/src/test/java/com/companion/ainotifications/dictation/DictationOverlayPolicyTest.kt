package com.companion.ainotifications.dictation

import org.junit.Assert.*
import org.junit.Test

class DictationOverlayPolicyTest {
    @Test fun retainedEditorAfterKeyboardDismissalDoesNotKeepOverlayVisible() {
        assertTrue(DictationOverlayPolicy.visible(true, true, true, false, false))
        assertFalse(DictationOverlayPolicy.visible(true, true, false, false, false))
        assertFalse(DictationOverlayPolicy.visible(true, false, true, false, false))
    }

    @Test fun activeSessionKeepsStopAvailableButDisabledAndLockedAlwaysHide() {
        assertTrue(DictationOverlayPolicy.visible(true, false, false, true, false))
        assertFalse(DictationOverlayPolicy.visible(false, true, true, true, false))
        assertFalse(DictationOverlayPolicy.visible(true, true, true, true, true))
    }

    @Test fun onlyFailedInsertionOffersCopyNeverBusyOrSuccessfulInsertion() {
        val completed = DictationState(phase = DictationPhase.READY, result = "Synthetic text")
        assertTrue(DictationOverlayPolicy.offerCopy(completed))
        assertFalse(DictationOverlayPolicy.offerCopy(completed.copy(inserted = true)))
        assertFalse(DictationOverlayPolicy.offerCopy(completed.copy(phase = DictationPhase.CLEANING)))
        assertFalse(DictationOverlayPolicy.offerCopy(completed.copy(result = "")))
    }
}
