package com.hotlayout.render.modal

import com.hotlayout.JsonValue
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.motion.VisibilityFadeRules

/** What happened to the stack of screens in one step. */
internal enum class StackStep(val word: String) {
    /** The first show, a new entry, a stack the host built anew: always at once. */
    Start("start"),

    /** The stack grew by one (`navigate`). */
    Push("push"),

    /** The stack became shorter by one (`navigate.back`, the scrim, Escape, a drag that closes a sheet). */
    Pop("pop"),

    /** A section was switched (`navigate.section`, an item of a navigation bar). */
    Switch("switch"),
}

/** How a screen asks to come and go (`transition` of a screen entry). Only a word of the list counts: anything else is no field. */
internal enum class ScreenTransitionWord(val word: String) {
    /** A cross-dissolve: a page fades out over the new one, a layer changes its opacity only. */
    Fade("fade"),

    /** At once. */
    None("none");

    companion object {
        fun of(value: JsonValue?): ScreenTransitionWord? = entries.firstOrNull { it.word == value?.string }
    }
}

/** A screen as the transition sees it: three fields of its entry. */
internal data class TransitionScreen(
    /** The screen is laid over another one (the EFFECTIVE presentation: the first screen of a stack is a page). */
    val modal: Boolean = false,
    /** The `modalPlacement` word, whatever it is (`WideChrome.modalShape` knows what it means). */
    val placement: JsonValue? = null,
    val transition: ScreenTransitionWord? = null,
)

/** What the reader plays. */
internal enum class TransitionMotion(val word: String) {
    None("none"),
    Push("push"),
    Pop("pop"),
    Crossfade("crossfade"),
    SheetIn("sheet-in"),
    SheetOut("sheet-out"),
    WindowIn("window-in"),
    WindowOut("window-out"),
    LayerFadeIn("layer-fade-in"),
    LayerFadeOut("layer-fade-out");

    /** A layer (a sheet, a window, or only its opacity) comes or goes. */
    val isLayer: Boolean get() = this == SheetIn || this == SheetOut || this == WindowIn || this == WindowOut || this == LayerFadeIn || this == LayerFadeOut

    val isLayerIn: Boolean get() = this == SheetIn || this == WindowIn || this == LayerFadeIn

    /** Two whole pages change places: the one that goes is kept beside the one that comes. */
    val isPage: Boolean get() = this == Push || this == Pop || this == Crossfade
}

/** Why nothing moves. */
internal enum class StillReason(val word: String) {
    Start("start"), ReducedMotion("reduced-motion"), Covered("covered"), Dragged("dragged"), Section("section"), FieldNone("field-none"),
}

/** What happens to the scrim under a layer. */
internal enum class ScrimMotion(val word: String) {
    /** It comes and goes with the layer: a page is on the other side. */
    Fade("fade"),

    /** It stays: a layer is on the other side. */
    Still("still"),
}

/** A planned motion of a step: the motion and its numbers, or nothing and the reason. */
internal data class ScreenTransitionPlan(
    val motion: TransitionMotion,
    /** Why nothing moves; `null` when something does. */
    val reason: StillReason? = null,
    val durationMs: Double = 0.0,
    val curve: DoubleArray = ScreenTransition.LINEAR,
    /** `push` and `pop`: the share of the width the page beneath moves. */
    val parallax: Double? = null,
    /** `window-in`: the scale the window grows from. */
    val scaleFrom: Double? = null,
    /** A layer's motion: what the scrim does. */
    val scrim: ScrimMotion? = null,
) {
    val moves: Boolean get() = motion != TransitionMotion.None

    companion object {
        fun still(reason: StillReason) = ScreenTransitionPlan(TransitionMotion.None, reason)
    }
}

/** One layer at a moment of a motion: a shift as a share of the width (`x`) or of its own height (`y`), an opacity and a scale. */
internal data class TransitionLayerFrame(val x: Double = 0.0, val y: Double = 0.0, val opacity: Double = 1.0, val scale: Double = 1.0)

/** Both screens and the scrim at a moment of a motion. */
internal data class ScreenTransitionFrame(
    /** The screen that comes (the new top of the stack). */
    val incoming: TransitionLayerFrame,
    /** The screen that goes (the old top). */
    val outgoing: TransitionLayerFrame,
    /** The share of the resting opacity of the scrim under a layer: 1 at rest, 0 not seen. */
    val scrim: Double,
)

/**
 * Default transitions between screens (`x-screenTransition`): which motion a step of the stack plays and where both screens
 * stand at a moment of it. The numbers are the contract's, the same on every platform; the views that play it are
 * [PageTransition] and the layers of [ModalStack].
 */
internal object ScreenTransition {
    const val FIELD = "transition"

    val DECELERATE = doubleArrayOf(0.2, 0.0, 0.0, 1.0)
    val ACCELERATE = doubleArrayOf(0.4, 0.0, 1.0, 1.0)
    val STANDARD_FADE = doubleArrayOf(0.4, 0.0, 0.2, 1.0)
    val LINEAR = doubleArrayOf(0.0, 0.0, 1.0, 1.0)

    const val PAGE_MS = 300.0
    const val PAGE_PARALLAX = 0.3
    const val SHEET_OPEN_MS = 280.0
    const val SHEET_CLOSE_MS = 220.0
    const val WINDOW_OPEN_MS = 200.0
    const val WINDOW_CLOSE_MS = 150.0
    const val WINDOW_SCALE_FROM = 0.96
    const val FADE_MS = 200.0

    private fun plan(motion: TransitionMotion, ms: Double, curve: DoubleArray, parallax: Double? = null, scaleFrom: Double? = null, scrim: ScrimMotion? = null) =
        ScreenTransitionPlan(motion, null, ms, curve, parallax, scaleFrom, scrim)

    private fun layerIn(from: TransitionScreen, to: TransitionScreen, word: ScreenTransitionWord?, areaWidth: Double): ScreenTransitionPlan {
        val scrim = if (from.modal) ScrimMotion.Still else ScrimMotion.Fade
        if (word == ScreenTransitionWord.Fade) return plan(TransitionMotion.LayerFadeIn, FADE_MS, STANDARD_FADE, scrim = scrim)
        if (WideChrome.modalShape(to.placement, areaWidth) == WideChrome.ModalShape.Sheet) return plan(TransitionMotion.SheetIn, SHEET_OPEN_MS, DECELERATE, scrim = scrim)
        return plan(TransitionMotion.WindowIn, WINDOW_OPEN_MS, DECELERATE, scaleFrom = WINDOW_SCALE_FROM, scrim = scrim)
    }

    private fun layerOut(leaving: TransitionScreen, to: TransitionScreen, word: ScreenTransitionWord?, areaWidth: Double, dragged: Boolean): ScreenTransitionPlan {
        val scrim = if (to.modal) ScrimMotion.Still else ScrimMotion.Fade
        if (word == ScreenTransitionWord.Fade) return plan(TransitionMotion.LayerFadeOut, FADE_MS, STANDARD_FADE, scrim = scrim)
        if (WideChrome.modalShape(leaving.placement, areaWidth) == WideChrome.ModalShape.Sheet) {
            if (dragged) return ScreenTransitionPlan.still(StillReason.Dragged)
            return plan(TransitionMotion.SheetOut, SHEET_CLOSE_MS, ACCELERATE, scrim = scrim)
        }
        return plan(TransitionMotion.WindowOut, WINDOW_CLOSE_MS, ACCELERATE, scrim = scrim)
    }

    private fun page(motion: TransitionMotion) = plan(motion, PAGE_MS, DECELERATE, parallax = PAGE_PARALLAX)

    private val crossfade = plan(TransitionMotion.Crossfade, FADE_MS, STANDARD_FADE)

    /**
     * How the reader shows a step of the stack. The order of the checks is part of the rule: `start`, reduced motion, the fill
     * of a swipe button, the word of the screen that decides, then the shape. [from] is the top screen before the step (`null`
     * at `start`), [to] after it; [areaWidth] is the width of the area the host gave the stack, which decides between a sheet
     * and a window.
     */
    fun plan(
        step: StackStep, from: TransitionScreen?, to: TransitionScreen, areaWidth: Double,
        reducedMotion: Boolean = false, covered: Boolean = false, dragged: Boolean = false,
    ): ScreenTransitionPlan {
        if (step == StackStep.Start || from == null) return ScreenTransitionPlan.still(StillReason.Start)
        if (reducedMotion) return ScreenTransitionPlan.still(StillReason.ReducedMotion)
        if (covered) return ScreenTransitionPlan.still(StillReason.Covered)
        if (step == StackStep.Pop) {
            if (from.transition == ScreenTransitionWord.None) return ScreenTransitionPlan.still(StillReason.FieldNone)
            if (from.modal) return layerOut(from, to, from.transition, areaWidth, dragged)
            return if (from.transition == ScreenTransitionWord.Fade) crossfade else page(TransitionMotion.Pop)
        }
        val word = to.transition
        if (word == ScreenTransitionWord.None) return ScreenTransitionPlan.still(StillReason.FieldNone)
        if (step == StackStep.Switch) {
            if (word != ScreenTransitionWord.Fade) return ScreenTransitionPlan.still(StillReason.Section)
            return if (to.modal) layerIn(from, to, word, areaWidth) else crossfade
        }
        if (to.modal) return layerIn(from, to, word, areaWidth)
        return if (word == ScreenTransitionWord.Fade) crossfade else page(TransitionMotion.Push)
    }

    private val rest = TransitionLayerFrame()

    /**
     * Where both screens stand [elapsedMs] after the motion began. `x` is positive toward the end edge (the view mirrors the sign
     * in a right-to-left language). Past the duration it is the final state.
     */
    fun frame(plan: ScreenTransitionPlan, elapsedMs: Double): ScreenTransitionFrame {
        if (!plan.moves) return ScreenTransitionFrame(rest, TransitionLayerFrame(opacity = 0.0), 1.0)
        val share = if (plan.durationMs <= 0) 1.0 else elapsedMs / plan.durationMs
        val e = VisibilityFadeRules.cubicBezierAt(plan.curve, share)
        val fades = plan.scrim == ScrimMotion.Fade
        val scrimIn = if (fades) e else 1.0
        val scrimOut = if (fades) 1 - e else 1.0
        val parallax = plan.parallax ?: 0.0
        return when (plan.motion) {
            TransitionMotion.None -> ScreenTransitionFrame(rest, rest, 1.0)
            TransitionMotion.Push -> ScreenTransitionFrame(TransitionLayerFrame(x = 1 - e), TransitionLayerFrame(x = -parallax * e), 1.0)
            TransitionMotion.Pop -> ScreenTransitionFrame(TransitionLayerFrame(x = -parallax * (1 - e)), TransitionLayerFrame(x = e), 1.0)
            TransitionMotion.Crossfade -> ScreenTransitionFrame(rest, TransitionLayerFrame(opacity = 1 - e), 1.0)
            TransitionMotion.SheetIn -> ScreenTransitionFrame(TransitionLayerFrame(y = 1 - e), rest, scrimIn)
            TransitionMotion.SheetOut -> ScreenTransitionFrame(rest, TransitionLayerFrame(y = e), scrimOut)
            TransitionMotion.WindowIn -> {
                val from = plan.scaleFrom ?: 1.0
                ScreenTransitionFrame(TransitionLayerFrame(opacity = e, scale = from + (1 - from) * e), rest, scrimIn)
            }
            TransitionMotion.LayerFadeIn -> ScreenTransitionFrame(TransitionLayerFrame(opacity = e), rest, scrimIn)
            TransitionMotion.WindowOut, TransitionMotion.LayerFadeOut -> ScreenTransitionFrame(rest, TransitionLayerFrame(opacity = 1 - e), scrimOut)
        }
    }
}
