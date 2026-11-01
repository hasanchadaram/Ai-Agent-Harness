package com.companion.ainotifications.dictation

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.companion.ainotifications.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Non-focusable edge controls. Idle is one 48dp target; only active controls expand. */
class DictationOverlay(
    private val context: Context,
    private val controller: DictationController,
    private val onRecord: () -> Unit
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var attached = false
    internal val isAttached get() = attached
    internal val displayedAlpha get() = if (attached) layout.alpha else 0f
    internal val dockingActive get() = dockAnimator?.isStarted == true
    internal val screenPosition: OverlayPlacement
        get() {
            if (!attached) return OverlayPlacement(layout.x, layout.y)
            val coordinates = IntArray(2)
            panel.getLocationOnScreen(coordinates)
            return OverlayPlacement(coordinates[0], coordinates[1])
        }
    private var rendered: DictationState? = null
    private var renderedOpacity = -1
    private var hiding = false
    private var dragging = false
    private var touching = false
    private var dockAnimator: ValueAnimator? = null
    private var dockTarget: OverlayPlacement? = null
    private var fadeAnimator: ValueAnimator? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val inkColor = Color.rgb(16, 24, 40)
    private val accentColor = Color.rgb(40, 75, 145)
    private val layout = WindowManager.LayoutParams(
        dp(48), dp(48), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT // Physical edge, independent of text direction.
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        windowAnimations = 0 // The complete surface has one fade owner; no extra system enter/exit animation.
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0) // Keyboard bounds use screen coordinates.
    }
    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(24).toFloat()
    }
    private val panelSurface = surface(Color.TRANSPARENT).apply {
        setStroke(dp(1), Color.argb(160, 170, 190, 220))
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = panelSurface
        elevation = dp(4).toFloat()
    }
    private fun icon(resource: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(inkColor)
        background = surface(Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description; tooltipText = description
        setOnClickListener { click() }
    }
    private val cancel = icon(R.drawable.ic_dictation_close, "Cancel dictation") { controller.cancel() }
    private val microphone = icon(R.drawable.ic_dictation_mic, "Start dictation. Drag to dock on either edge") {
        if (!controller.state.value.busy) onRecord()
    }
    private val listeningDot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(197, 30, 61)) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val listening = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        contentDescription = "Listening. Microphone is recording. Drag to move controls"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(listeningDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        addView(TextView(context).apply {
            text = "Listening"; textSize = 12f; setTextColor(inkColor)
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }
    private val listeningPulse = ObjectAnimator.ofFloat(listeningDot, View.ALPHA, 0.35f, 1f).apply {
        duration = 800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
    }
    private val polish = Button(context).apply {
        text = "Polish"; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { controller.setPolished(!controller.state.value.polished) }
    }
    private val finish = icon(R.drawable.ic_dictation_check, "Finish recording and transcribe") { controller.stop() }
    private val copy = icon(R.drawable.ic_dictation_copy, "Copy transcript. Check the field before pasting") {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Dictation", controller.state.value.result))
        Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
        controller.cancel()
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        indeterminateTintList = ColorStateList.valueOf(accentColor)
        setPadding(0, 0, 0, 0); minimumWidth = 0; minimumHeight = 0
        // A fixed circular track makes the center clear even when the moving arc is very short.
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.argb(70, 40, 75, 145))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val progressSlot = FrameLayout(context).apply {
        addView(progress, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }
    private val status = TextView(context).apply {
        setTextColor(inkColor); textSize = 12f; maxLines = 2
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        panel.addView(cancel, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(microphone, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(listening, LinearLayout.LayoutParams(dp(88), dp(48)))
        panel.addView(progressSlot, LinearLayout.LayoutParams(dp(32), dp(48)))
        panel.addView(status, LinearLayout.LayoutParams(dp(84), dp(48)))
        panel.addView(polish, LinearLayout.LayoutParams(dp(64), dp(48)))
        panel.addView(finish, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(copy, LinearLayout.LayoutParams(dp(48), dp(48)))
        installDrag(microphone); installDrag(listening); installDrag(status)
    }

    fun show(state: DictationState) {
        if (hiding) {
            hiding = false
            fadeTo(1f)
        }
        val opacity = controller.settings.overlayOpacity
        if (renderedOpacity != opacity) {
            panelSurface.setColor(Color.argb((255 * opacity / 100f).roundToInt(), 255, 255, 255))
            renderedOpacity = opacity
        }
        val oldWidth = layout.width
        if (rendered != state) {
            val recording = state.phase == DictationPhase.RECORDING
            val processing = state.busy && !recording
            val needsCopy = DictationOverlayPolicy.offerCopy(state)
            cancel.visibility = if (state.busy || needsCopy) View.VISIBLE else View.GONE
            cancel.contentDescription = if (state.busy) "Cancel dictation" else "Dismiss transcript controls"
            microphone.visibility = if (!state.busy) View.VISIBLE else View.GONE
            listening.visibility = if (recording) View.VISIBLE else View.GONE
            polish.visibility = if (recording) View.VISIBLE else View.GONE
            polish.isSelected = state.polished
            polish.setTextColor(if (state.polished) Color.WHITE else inkColor)
            polish.background = surface(if (state.polished) accentColor else Color.TRANSPARENT)
            polish.contentDescription = "Polish text ${if (state.polished) "on" else "off"}. Tap to toggle"
            if (Build.VERSION.SDK_INT >= 30) polish.stateDescription = if (state.polished) "On" else "Off"
            finish.visibility = if (recording) View.VISIBLE else View.GONE
            copy.visibility = if (needsCopy) View.VISIBLE else View.GONE
            progressSlot.visibility = if (processing) View.VISIBLE else View.GONE
            status.visibility = if (processing) View.VISIBLE else View.GONE
            status.text = when (state.phase) {
                DictationPhase.STARTING -> "Starting…"
                DictationPhase.TRANSCRIBING -> "Transcribing…"
                DictationPhase.CLEANING -> "Polishing…"
                else -> ""
            }
            layout.width = dp(when {
                recording -> 248
                processing -> 164
                needsCopy -> 144
                else -> 48
            })
            if ((state.phase == DictationPhase.ERROR || needsCopy) && rendered?.id == state.id &&
                (rendered?.phase != state.phase || rendered?.message != state.message)) {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            rendered = state
        }
        val oldX = layout.x; val oldY = layout.y
        if (!touching) {
            val destination = savedPosition()
            if (!attached || oldWidth != layout.width) {
                cancelDockAnimation()
                layout.x = destination.x; layout.y = destination.y
            } else animateDock(destination)
        }
        if (!attached) {
            layout.alpha = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
            try {
                manager.addView(panel, layout); attached = true
                if (ValueAnimator.areAnimatorsEnabled()) fadeTo(1f)
            }
            catch (_: RuntimeException) {
                controller.settings.overlayEnabled = false
                Toast.makeText(context, "Cannot display dictation controls. Re-enable the accessibility service.", Toast.LENGTH_LONG).show()
            }
        } else if (oldWidth != layout.width || oldX != layout.x || oldY != layout.y) {
            runCatching { manager.updateViewLayout(panel, layout) }
        }
        if (attached && state.phase == DictationPhase.RECORDING && ValueAnimator.areAnimatorsEnabled()) {
            if (!listeningPulse.isStarted) listeningPulse.start()
        } else stopListeningPulse()
    }

    private fun stopListeningPulse() {
        listeningPulse.cancel(); listeningDot.alpha = 1f
    }

    fun hide(immediate: Boolean = false) {
        stopListeningPulse()
        cancelDockAnimation()
        if (!attached) return
        if (immediate || !ValueAnimator.areAnimatorsEnabled()) detach()
        else if (!hiding) {
            hiding = true
            fadeTo(0f)
        }
    }

    private fun cancelFade() {
        val animator = fadeAnimator
        fadeAnimator = null
        animator?.cancel()
    }

    private fun fadeTo(target: Float) {
        cancelFade()
        if (!attached) return
        if (!ValueAnimator.areAnimatorsEnabled() || layout.alpha == target) {
            layout.alpha = target
            runCatching { manager.updateViewLayout(panel, layout) }
            if (target == 0f && hiding) detach()
            return
        }
        // Window alpha fades the icon, surface, border and shadow together. A root-view
        // alpha property alone cannot verify what the compositor actually displays.
        val animator = ValueAnimator.ofFloat(layout.alpha, target)
        fadeAnimator = animator
        animator.duration = (140 * abs(layout.alpha - target)).toLong().coerceAtLeast(1)
        animator.addUpdateListener {
            layout.alpha = it.animatedValue as Float
            if (attached) runCatching { manager.updateViewLayout(panel, layout) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (fadeAnimator !== animator) return
                fadeAnimator = null
                if (target == 0f && hiding) detach()
            }
        })
        animator.start()
    }

    private fun detach() {
        cancelFade()
        if (attached) runCatching { manager.removeView(panel) }
        attached = false; hiding = false; dragging = false; touching = false
    }

    private fun screenBounds() = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
        else android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)

    private fun placement(preferredTop: Int): OverlayPlacement {
        val bounds = screenBounds()
        var edgeGap = dp(12); var safeTop = dp(32); var safeBottom = dp(32)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = manager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            edgeGap += maxOf(insets.left, insets.right)
            safeTop = maxOf(safeTop, insets.top + dp(8))
            safeBottom = insets.bottom + dp(8)
        }
        return overlayPlacement(bounds.width(), bounds.height(), layout.width, dp(48), edgeGap, safeTop, safeBottom,
            preferredTop, controller.settings.overlaySide == "right")
    }

    private fun savedPosition(): OverlayPlacement {
        var top = controller.settings.overlayTopDp
        if (top < 0) {
            top = (screenBounds().height() * 0.42f / context.resources.displayMetrics.density).roundToInt()
            controller.settings.overlayTopDp = top
        }
        return placement(dp(top))
    }

    private fun cancelDockAnimation() {
        val animator = dockAnimator
        dockAnimator = null; dockTarget = null
        animator?.cancel()
    }

    private fun animateDock(destination: OverlayPlacement) {
        if (dockTarget == destination && dockAnimator?.isStarted == true) return
        if (layout.x == destination.x && layout.y == destination.y) {
            cancelDockAnimation()
            return
        }
        cancelDockAnimation()
        if (!attached || !ValueAnimator.areAnimatorsEnabled()) {
            layout.x = destination.x; layout.y = destination.y
            if (attached) runCatching { manager.updateViewLayout(panel, layout) }
            return
        }
        val startX = layout.x; val startY = layout.y
        val animator = ValueAnimator.ofFloat(0f, 1f)
        dockAnimator = animator; dockTarget = destination
        animator.duration = 220
        animator.interpolator = DecelerateInterpolator(1.5f)
        animator.addUpdateListener {
            val fraction = it.animatedValue as Float
            layout.x = (startX + (destination.x - startX) * fraction).roundToInt()
            layout.y = (startY + (destination.y - startY) * fraction).roundToInt()
            if (attached) runCatching { manager.updateViewLayout(panel, layout) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (dockAnimator === animator) { dockAnimator = null; dockTarget = null }
            }
        })
        animator.start()
    }

    internal fun placementMetadata() = "x=${layout.x} y=${layout.y} width=${layout.width} height=${layout.height} opacity=$renderedOpacity windowAlpha=${layout.alpha}"

    /** Debug fixture checks geometry without screen text, microphone capture or network access. */
    internal fun geometryValid(): Boolean {
        if (!attached) return false
        val rowCenter = panel.height / 2
        val spinnerCenter = progressSlot.top + progress.top + progress.height / 2
        return screenPosition == OverlayPlacement(layout.x, layout.y) &&
            layout.x == placement(layout.y).x && layout.y == placement(layout.y).y &&
            (progressSlot.visibility != View.VISIBLE || abs(spinnerCenter - rowCenter) <= 1)
    }

    /** Local synthetic touch sequence for the debug fixture; never dispatches into another app. */
    internal fun dragForCheck(dx: Int) {
        val down = android.os.SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                (dp(24) + if (action == MotionEvent.ACTION_DOWN) 0 else dx).toFloat(), dp(24).toFloat(), 0)
            try { microphone.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(handle: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelDockAnimation()
                    touching = true
                    startX = layout.x; startY = layout.y; touchX = event.rawX; touchY = event.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val position = placement(startY + dy.toInt())
                        layout.x = (startX + dx.toInt()).coerceIn(dp(12), maxOf(dp(12), screenBounds().width() - layout.width - dp(12)))
                        layout.y = position.y
                        if (attached) runCatching { manager.updateViewLayout(panel, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val side = if (layout.x + layout.width / 2 < screenBounds().width() / 2) "left" else "right"
                        // Preference callbacks may render synchronously. Retain touch ownership
                        // until both saved coordinates are written so they cannot teleport the view.
                        controller.settings.saveOverlayPosition(side, (layout.y / context.resources.displayMetrics.density).roundToInt())
                        dragging = false; touching = false
                        animateDock(savedPosition())
                    } else { touching = false; handle.performClick() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false; touching = false
                    animateDock(savedPosition())
                    true
                }
                else -> false
            }
        }
    }
}
