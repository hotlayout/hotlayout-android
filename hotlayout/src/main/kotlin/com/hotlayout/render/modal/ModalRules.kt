package com.hotlayout.render.modal

import com.hotlayout.JsonValue
import kotlin.math.sqrt

/**
 * The rules of a modal layer as numbers and words the shared tables check: the box of a sheet or a
 * window, the blur radius, the drag that closes a sheet, the Escape key. The views only call them.
 */
internal object ModalRules {
    /** Widest window in the middle (`modalLayer.center.maxWidth`). */
    const val WINDOW_MAX_WIDTH: Double = 400.0

    /** Opacity of the black dim (`modalLayer.scrim.opacity`). */
    const val SCRIM_OPACITY: Double = 0.45

    /** The scale a window grows from while it fades in. */
    const val WINDOW_START_SCALE: Double = 0.96

    /** The margin of a window when the theme has no `xl` step. */
    const val DEFAULT_MARGIN: Double = 24.0

    // Placement and box

    /** `center` is a window; every other word, and no field, is a sheet. */
    fun isWindow(placement: JsonValue?): Boolean = placement?.string == "center"

    /** The width of the layer: it is known before the content is measured. */
    fun width(window: Boolean, viewport: Double, margin: Double): Double =
        if (window) maxOf(0.0, minOf(viewport - 2 * margin, WINDOW_MAX_WIDTH)) else viewport

    data class Box(val x: Double, val y: Double, val width: Double, val height: Double, val scrolls: Boolean)

    /**
     * The rectangle of the layer in the area: a sheet is as wide as the area and pressed to its
     * bottom; a window is centred. The height follows the content, no taller than the room.
     */
    fun box(window: Boolean, viewportWidth: Double, viewportHeight: Double, contentHeight: Double, margin: Double): Box {
        val layerWidth = width(window, viewportWidth, margin)
        val available = if (window) maxOf(0.0, viewportHeight - 2 * margin) else viewportHeight
        val height = minOf(maxOf(0.0, contentHeight), available)
        val scrolls = contentHeight > available
        return if (window) {
            Box((viewportWidth - layerWidth) / 2, (viewportHeight - height) / 2, layerWidth, height, scrolls)
        } else {
            Box(0.0, viewportHeight - height, layerWidth, height, scrolls)
        }
    }

    // Blur

    /** The blur of one layer of the stack: the blurs above it add up as the variances of Gaussian blurs do. */
    fun combinedBlur(radii: List<Int>): Double = sqrt(radii.sumOf { it.toDouble() * it })

    // Drag down

    const val DISMISS_SHARE: Double = 0.3
    const val FLING_VELOCITY: Double = 700.0
    const val VELOCITY_WINDOW_MS: Double = 100.0
    const val TOUCH_SLOP: Double = 10.0

    data class Motion(val durationMs: Int, val curve: List<Double>)

    val returnMotion = Motion(250, listOf(0.2, 0.0, 0.0, 1.0))
    val exitMotion = Motion(200, listOf(0.4, 0.0, 1.0, 1.0))

    enum class DragOutcome(val word: String) { Dismiss("dismiss"), Return("return") }

    /** What the released sheet does: a fling decides, otherwise the share of its height. */
    fun dragOutcome(sheetHeight: Double, dragged: Double, velocity: Double): DragOutcome = when {
        velocity <= -FLING_VELOCITY -> DragOutcome.Return
        velocity >= FLING_VELOCITY -> DragOutcome.Dismiss
        sheetHeight <= 0 -> DragOutcome.Return
        dragged >= sheetHeight * DISMISS_SHARE -> DragOutcome.Dismiss
        else -> DragOutcome.Return
    }

    /** Whether a finger's move drags the sheet instead of scrolling its body. */
    fun dragStarts(sheet: Boolean, dismissible: Boolean, scrolled: Double, dy: Double, dx: Double, onHandle: Boolean): Boolean {
        if (!sheet || !dismissible) return false
        if (dy <= TOUCH_SLOP || Math.abs(dx) >= dy) return false
        return onHandle || scrolled <= 0
    }

    /** One sample of the finger: the time in milliseconds and the position, down positive. */
    data class Sample(val timeMs: Double, val y: Double)

    /** The speed at release, down positive, logical pixels per second, from the samples of the last [VELOCITY_WINDOW_MS]. */
    fun velocity(samples: List<Sample>): Double {
        val last = samples.lastOrNull() ?: return 0.0
        val first = samples.firstOrNull { last.timeMs - it.timeMs <= VELOCITY_WINDOW_MS } ?: return 0.0
        if (last.timeMs <= first.timeMs) return 0.0
        return (last.y - first.y) / ((last.timeMs - first.timeMs) / 1000)
    }

    // Keys

    /** Steps back made by a key: one for Escape pressed on a layer that may be closed. */
    fun escapeSteps(key: String, repeating: Boolean, modalOnTop: Boolean, topDismissible: Boolean): Int =
        if (key == "Escape" && !repeating && modalOnTop && topDismissible) 1 else 0
}
