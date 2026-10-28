package com.hotlayout.render.motion

import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidgetType

/** Where a fade is in its timeline. */
internal enum class FadePhase(val word: String) {
    /** In the layout, opacity 1. */
    Shown("shown"),

    /** In the layout, fading out; catches no press and is not heard. */
    Leaving("leaving"),

    /** Out of the layout. */
    Hidden("hidden"),

    /** Out of the layout, waiting for the neighbours to fade. */
    Waiting("waiting"),

    /** In the layout, fading in; a control. */
    Entering("entering"),
}

/** The state of one node: the phase, the moment it began (milliseconds of the caller's clock) and the opacity then. */
internal data class FadeState(val phase: FadePhase, val since: Double, val from: Double)

/** The frame at a moment: the phase, whether the node is in the layout, its opacity and whether it is a control. */
internal data class FadeFrame(val phase: FadePhase, val inLayout: Boolean, val opacity: Double, val interactive: Boolean)

/**
 * A node that fades in and out when its `visibleIf` changes (`visibilityTransition: 'fade'`, the `x-visibilityFade`
 * block). Every node follows its own timeline, with no pairing: a leaving node stays in the layout and fades for
 * `out`, then leaves it; an appearing node waits `delayMs` out of the layout, then enters it transparent and fades in
 * for `in`. A change during a transition goes to the new goal from the current opacity, the time scaled by the
 * distance. The clock comes from the caller; the drawing and the timers are done by [VisibilityFadeBoard].
 */
internal object VisibilityFadeRules {
    const val KEY = "visibilityTransition"
    const val WORD = "fade"

    const val OUT_MS = 120.0
    val outCurve = doubleArrayOf(0.4, 0.0, 1.0, 1.0)
    const val IN_MS = 160.0
    val inCurve = doubleArrayOf(0.0, 0.0, 0.2, 1.0)
    const val IN_DELAY_MS = 120.0

    /** The field as the reader reads it: only the word `fade`; anything else is no field. */
    fun fieldOf(node: DocumentNode): Boolean = node.fields[KEY]?.string == WORD

    /**
     * Screen zones follow the condition at once: any node with `pin` (bottom or top), a `NavBar`, a `NavItem` and an
     * `AppBar` that is the FIRST child of the screen root. An `AppBar` that is not the first child fades like any node.
     * The panels of `Tabs` show the open tab only and do not fade.
     */
    fun isScreenZoneNode(node: DocumentNode, root: DocumentNode?): Boolean {
        if (node.widget == WidgetType.NavBar || node.widget == WidgetType.NavItem) return true
        if (node.fields["pin"] != null) return true
        return node.widget == WidgetType.AppBar && root != null && root.widget == WidgetType.Screen && root.children.firstOrNull()?.id == node.id
    }

    /** Whether the reader fades this node: the field, a condition to change and not a zone of the screen. */
    fun reads(node: DocumentNode, root: DocumentNode?): Boolean =
        fieldOf(node) && node.visibleIf != null && node.widget != WidgetType.Tab && !isScreenZoneNode(node, root)

    fun rest(shown: Boolean, now: Double = 0.0): FadeState = FadeState(if (shown) FadePhase.Shown else FadePhase.Hidden, now, if (shown) 1.0 else 0.0)

    /** The cubic Bezier through (0,0) and (1,1): y for x, by bisection on the parameter of the curve. */
    fun cubicBezierAt(curve: DoubleArray, x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        fun coordinate(t: Double, a: Double, b: Double) = 3 * a * t * (1 - t) * (1 - t) + 3 * b * t * t * (1 - t) + t * t * t
        var low = 0.0
        var high = 1.0
        var t = x
        repeat(40) {
            val value = coordinate(t, curve[0], curve[2]) - x
            if (Math.abs(value) < 1e-9) return@repeat
            if (value > 0) high = t else low = t
            t = (low + high) / 2
        }
        return coordinate(t, curve[1], curve[3])
    }

    private fun leavingSpan(state: FadeState): Double = OUT_MS * state.from
    private fun enteringSpan(state: FadeState): Double = IN_MS * (1 - state.from)

    /** The moment the current phase ends by itself; `null` for a phase of rest. */
    fun endsAt(state: FadeState): Double? = when (state.phase) {
        FadePhase.Leaving -> state.since + leavingSpan(state)
        FadePhase.Waiting -> state.since + IN_DELAY_MS
        FadePhase.Entering -> state.since + enteringSpan(state)
        else -> null
    }

    /** The state at [now]: phases that ended pass on (`leaving → hidden`, `waiting → entering → shown`). */
    fun settle(state: FadeState, now: Double): FadeState {
        var current = state
        while (true) {
            val end = endsAt(current) ?: return current
            if (now < end) return current
            current = when (current.phase) {
                FadePhase.Leaving -> FadeState(FadePhase.Hidden, end, 0.0)
                FadePhase.Waiting -> FadeState(FadePhase.Entering, end, 0.0)
                else -> FadeState(FadePhase.Shown, end, 1.0)
            }
        }
    }

    /** The frame at [now]: the phase, whether the node is in the layout, its opacity and whether it is a control. */
    fun at(state: FadeState, now: Double): FadeFrame {
        val settled = settle(state, now)
        return when (settled.phase) {
            FadePhase.Shown -> FadeFrame(FadePhase.Shown, true, 1.0, true)
            FadePhase.Hidden -> FadeFrame(FadePhase.Hidden, false, 0.0, false)
            FadePhase.Waiting -> FadeFrame(FadePhase.Waiting, false, 0.0, false)
            FadePhase.Leaving -> {
                val span = leavingSpan(settled)
                val share = if (span <= 0) 1.0 else (now - settled.since) / span
                FadeFrame(FadePhase.Leaving, true, settled.from * (1 - cubicBezierAt(outCurve, share)), false)
            }
            FadePhase.Entering -> {
                val span = enteringSpan(settled)
                val share = if (span <= 0) 1.0 else (now - settled.since) / span
                FadeFrame(FadePhase.Entering, true, settled.from + (1 - settled.from) * cubicBezierAt(inCurve, share), true)
            }
        }
    }

    /** The condition of the node became [shown] at [now]; with [animate] false the change is «at once»: the state of rest of the new goal. */
    fun change(state: FadeState, shown: Boolean, now: Double, animate: Boolean): FadeState {
        if (!animate) return rest(shown, now)
        val settled = settle(state, now)
        val opacity = at(settled, now).opacity
        if (shown) {
            return when (settled.phase) {
                FadePhase.Hidden -> FadeState(FadePhase.Waiting, now, 0.0)
                FadePhase.Leaving -> FadeState(FadePhase.Entering, now, opacity)
                else -> settled
            }
        }
        return when (settled.phase) {
            FadePhase.Shown, FadePhase.Entering -> FadeState(FadePhase.Leaving, now, opacity)
            FadePhase.Waiting -> rest(false, now)
            else -> settled
        }
    }
}
