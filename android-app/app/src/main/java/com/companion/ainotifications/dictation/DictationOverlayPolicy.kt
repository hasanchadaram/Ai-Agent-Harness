package com.companion.ainotifications.dictation

/** A focused editor can survive keyboard dismissal. Old results must not keep the bubble alive. */
internal object DictationOverlayPolicy {
    fun visible(enabled: Boolean, editor: Boolean, keyboard: Boolean, busy: Boolean, locked: Boolean) =
        enabled && !locked && (busy || (editor && keyboard))

    fun offerCopy(state: DictationState) = !state.busy && !state.inserted && state.result.isNotBlank()
}
