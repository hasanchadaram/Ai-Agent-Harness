package com.companion.ainotifications.dictation

internal data class OverlayPlacement(val x: Int, val y: Int)

/** Screen coordinates only: keyboard animation bounds deliberately cannot influence placement. */
internal fun overlayPlacement(
    screenWidth: Int, screenHeight: Int, panelWidth: Int, panelHeight: Int,
    edgeGap: Int, safeTop: Int, safeBottom: Int, preferredTop: Int, right: Boolean
): OverlayPlacement {
    val available = (screenWidth - panelWidth).coerceAtLeast(0)
    val gap = edgeGap.coerceIn(0, available / 2)
    return OverlayPlacement(
        if (right) available - gap else gap,
        preferredTop.coerceIn(safeTop, maxOf(safeTop, screenHeight - safeBottom - panelHeight))
    )
}
