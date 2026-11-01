package com.companion.ainotifications.dictation

import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    @Test fun leavesGapAtBothEdgesAndKeepsSavedHeightAcrossPanelExpansion() {
        fun place(width: Int, right: Boolean) = overlayPlacement(360, 800, width, 48, 12, 32, 24, 320, right)
        assertEquals(OverlayPlacement(12, 320), place(48, false))
        assertEquals(OverlayPlacement(300, 320), place(48, true))
        assertEquals(OverlayPlacement(100, 320), place(248, true))
        assertEquals(OverlayPlacement(12, 320), place(248, false))
    }

    @Test fun clampsOnlyToScreenSafetyBoundsAndRetainsPreferredHeightAfterRotation() {
        assertEquals(32, overlayPlacement(360, 800, 48, 48, 12, 32, 24, -200, true).y)
        assertEquals(728, overlayPlacement(360, 800, 48, 48, 12, 32, 24, 900, true).y)
        assertEquals(288, overlayPlacement(800, 360, 248, 48, 12, 32, 24, 600, true).y)
        assertEquals(600, overlayPlacement(360, 800, 248, 48, 12, 32, 24, 600, true).y)
    }
}
