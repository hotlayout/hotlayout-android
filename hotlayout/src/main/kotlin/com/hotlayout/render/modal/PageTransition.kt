package com.hotlayout.render.modal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.hotlayout.ScreenShownCause
import com.hotlayout.render.motion.rememberReducedMotion

/**
 * How a page replaces another one: the motion of a native app, not a field of the document.
 * A page opened forward slides in from the end of the line over the previous one while it fades in
 * (the previous one drifts back and fades out); a step back plays it in reverse; a section switch
 * of the navigation bar cross-fades. The entry, a new document and the first screen appear without
 * motion, and so does everything when the system asks for no motion.
 *
 * The motion only follows the stack: the events, the stack and the focus rules were already
 * decided when the key changed. The page that leaves takes no touch and no focus.
 */
internal object PageMotionRules {
    const val DURATION_MS = 300
    const val SHIFT = 0.25

    enum class Kind { Forward, Back, Switch }

    fun kindOf(cause: ScreenShownCause?): Kind? = when (cause) {
        ScreenShownCause.Forward -> Kind.Forward
        ScreenShownCause.Back -> Kind.Back
        ScreenShownCause.Switch -> Kind.Switch
        else -> null
    }

    /** Opacity and shift (a fraction of the width toward the end of the line) of one page, [run] 0…1 being how far the motion has gone. */
    data class Look(val opacity: Float, val shift: Float)

    private fun interval(run: Float, end: Float) = (run / end).coerceIn(0f, 1f)

    fun incoming(kind: Kind, run: Float): Look = when (kind) {
        Kind.Forward -> Look(interval(run, 0.75f), (SHIFT * (1 - run)).toFloat())
        Kind.Switch -> Look(interval(run, 0.6f), 0f)
        // The page revealed by a step back: it is the page beneath.
        Kind.Back -> Look(interval(run, 0.75f), (-SHIFT * (1 - run)).toFloat())
    }

    fun outgoing(kind: Kind, run: Float): Look = when (kind) {
        Kind.Forward -> Look(1 - interval(run, 0.25f), (-SHIFT * run).toFloat())
        Kind.Switch -> Look(1 - interval((run - 0.6f) / 0.4f, 1f), 0f)
        Kind.Back -> Look(1 - interval(run, 0.25f), (SHIFT * run).toFloat())
    }
}

private class PageHolder {
    var key: Any? = null
    var content: (@Composable () -> Unit)? = null
}

/** Draws the page of [key]; when [key] changes with a [cause] that moves, the page it replaces stays drawn until the motion is over. */
@Composable
internal fun PageTransition(key: Any, cause: ScreenShownCause?, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val holder = remember { PageHolder() }
    val kind = PageMotionRules.kindOf(cause)
    val outgoing = remember(key) { if (holder.key != null && kind != null && !reduced) holder.content else null }
    val run = remember(key) { Animatable(if (outgoing != null) 0f else 1f) }
    var finished by remember(key) { mutableStateOf(outgoing == null) }
    SideEffect {
        holder.key = key
        holder.content = content
    }
    LaunchedEffect(key) {
        if (outgoing == null) return@LaunchedEffect
        run.animateTo(1f, tween(PageMotionRules.DURATION_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        finished = true
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize()) {
        if (outgoing != null && !finished && kind != null) {
            // A step back shows the page it returns to beneath the one that leaves.
            Box(
                Modifier.fillMaxSize().zIndex(if (kind == PageMotionRules.Kind.Back) 1f else 0f)
                    .focusProperties { canFocus = false }
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                    .graphicsLayer {
                        val look = PageMotionRules.outgoing(kind, run.value)
                        alpha = look.opacity
                        translationX = (if (rtl) -look.shift else look.shift) * size.width
                    },
            ) { outgoing() }
        }
        androidx.compose.runtime.key(key) {
            Box(
                Modifier.fillMaxSize().zIndex(if (kind == PageMotionRules.Kind.Back) 0f else 1f)
                    .graphicsLayer {
                        if (finished || kind == null) return@graphicsLayer
                        val look = PageMotionRules.incoming(kind, run.value)
                        alpha = look.opacity
                        translationX = (if (rtl) -look.shift else look.shift) * size.width
                    },
            ) { content() }
        }
    }
}
