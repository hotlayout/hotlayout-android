package com.hotlayout.render.widgets

import com.hotlayout.JsonObject
import com.hotlayout.render.motion.VisibilityFadeRules

/**
 * The motion of «swipe to confirm» (`Button.confirmTransition: 'ripple'`, block `x-swipeConfirmMotion`) as pure
 * functions: nothing here draws.
 *
 * While the thumb is dragged a fill of the thumb's colour follows it and the label fades with the travel. Released
 * past the threshold, the button collapses into a circle holding the busy indicator; with `ripple` that circle then
 * grows over the whole screen, the action runs under the fill and the next screen shows from under it.
 */
internal object SwipeMotion {
    /** The field of the button and its only word; anything else is no field. */
    const val FIELD = "confirmTransition"
    const val RIPPLE_WORD = "ripple"

    /** The actions whose change of screen the reader performs itself: only they can be led by the fill. */
    val rippleActionTypes: List<String> = listOf("navigate", "navigate.back")

    const val COLLAPSE_MS = 600.0
    val collapseCurve: DoubleArray = doubleArrayOf(0.4, 0.0, 0.2, 1.0)
    const val RIPPLE_MS = 800.0
    const val REVEAL_MS = 200.0
    const val HALO_OPACITY = 0.4

    fun readsRipple(props: JsonObject): Boolean = props[FIELD]?.string == RIPPLE_WORD

    private fun clamp01(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

    /** The opacity of the label for a share of the travel. */
    fun labelOpacity(progress: Double): Double = 1 - clamp01(progress)

    /** A strip from the inline start of the button. */
    data class Strip(val start: Double, val width: Double)

    /** The fill behind the thumb: from the place of rest of the thumb to its far edge. At rest it is exactly the thumb. */
    fun trail(track: SwipeConfirmRules.Track, progress: Double): Strip {
        val geometry = SwipeConfirmRules.geometry(track)
        return Strip(maxOf(0.0, track.border) + maxOf(0.0, track.inset), clamp01(progress) * geometry.travel + geometry.diameter)
    }

    /**
     * The radius of the thumb and of the fill behind it, concentric with the track: the radius of the track minus the
     * inset, never below zero. [radius] is the real radius of the track; it is capped at half the height first, so a
     * pill track keeps the thumb a circle. A radius that is not known gives the circle.
     */
    fun thumbRadius(track: SwipeConfirmRules.Track, radius: Double?): Double {
        val circle = SwipeConfirmRules.geometry(track).diameter / 2
        if (radius == null || radius.isNaN()) return circle
        val actual = minOf(maxOf(0.0, radius), maxOf(0.0, track.height) / 2)
        return minOf(circle, maxOf(0.0, actual - maxOf(0.0, track.inset)))
    }

    /** One frame of the collapse: the pill, its ring, and the opacity of the surface of the button and of the halo. */
    data class CollapseFrame(val start: Double, val width: Double, val height: Double, val ring: Double, val surfaceOpacity: Double, val haloOpacity: Double)

    /** The collapse [elapsed] milliseconds after its start: the pill narrows from the width of the button to a circle, towards the CENTRE of the box. */
    fun collapse(track: SwipeConfirmRules.Track, elapsed: Double): CollapseFrame {
        val eased = VisibilityFadeRules.cubicBezierAt(collapseCurve, clamp01(elapsed / COLLAPSE_MS))
        val height = maxOf(0.0, track.height)
        val full = maxOf(height, track.width)
        val width = full - (full - height) * eased
        return CollapseFrame((track.width - width) / 2, width, height, maxOf(0.0, track.border) + maxOf(0.0, track.inset), 1 - eased, HALO_OPACITY * eased)
    }

    /** The final scale of the circle: its core covers the farthest corner of the screen. */
    fun rippleScale(centerX: Double, centerY: Double, screenWidth: Double, screenHeight: Double, core: Double): Double {
        val dx = maxOf(centerX, screenWidth - centerX)
        val dy = maxOf(centerY, screenHeight - centerY)
        val radius = core / 2
        if (radius <= 0) return 1.0
        return maxOf(1.0, Math.sqrt(dx * dx + dy * dy) / radius)
    }

    /** The scale of the circle [elapsed] milliseconds after the fill started: linear. */
    fun rippleAt(scale: Double, elapsed: Double): Double = 1 + (maxOf(1.0, scale) - 1) * clamp01(elapsed / RIPPLE_MS)

    /** The opacity of the fill [elapsed] milliseconds after the next screen began to show. */
    fun revealAt(elapsed: Double): Double = 1 - clamp01(elapsed / REVEAL_MS)

    enum class Step(val word: String) { Act("act"), Glide("glide"), Collapse("collapse"), Busy("busy"), Ripple("ripple"), Reveal("reveal"), Expand("expand"), GiveBack("return") }

    enum class Way(val word: String) { Swipe("swipe"), Keyboard("keyboard") }

    /** Whether the fill plays: after a swipe, on an action the reader performs itself, without «reduce motion». */
    fun ripplePlays(runner: SwipeConfirmRules.Runner, actionType: String, ripple: Boolean, way: Way, reducedMotion: Boolean): Boolean =
        ripple && way == Way.Swipe && !reducedMotion && runner == SwipeConfirmRules.Runner.Reader && actionType in rippleActionTypes

    /** The steps after a release. With the fill the action goes AFTER it, so the next screen stands under the fill. */
    fun sequence(runner: SwipeConfirmRules.Runner, actionType: String, ripple: Boolean, way: Way, reducedMotion: Boolean): List<Step> {
        if (runner == SwipeConfirmRules.Runner.None || runner == SwipeConfirmRules.Runner.Invalid) return listOf(Step.Act, Step.GiveBack)
        if (ripplePlays(runner, actionType, ripple, way, reducedMotion)) return listOf(Step.Glide, Step.Collapse, Step.Ripple, Step.Act, Step.Reveal)
        return if (reducedMotion) listOf(Step.Act, Step.Busy) else listOf(Step.Act, Step.Glide, Step.Collapse, Step.Busy)
    }

    /** How «busy» ends: the circle expands back, then the thumb returns; under «reduce motion» it only returns. */
    fun busyReturn(reducedMotion: Boolean): List<Step> = if (reducedMotion) listOf(Step.GiveBack) else listOf(Step.Expand, Step.GiveBack)
}
