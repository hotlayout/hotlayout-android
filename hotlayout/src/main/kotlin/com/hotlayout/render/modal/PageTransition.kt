package com.hotlayout.render.modal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.hotlayout.render.motion.AppearWindow
import com.hotlayout.render.motion.LocalAppearWindow

/** The window handed to the copy of the page that goes: nothing in it appears again. */
private val closedWindow = AppearWindow().also { it.close() }

private class PageHolder<T> {
    var key: Any? = null
    var value: T? = null
}

/**
 * Draws the page of [key] from [value]; when [key] changes with a [plan] that moves a page, the page it replaces stays drawn, live, until the
 * motion is over. The plan is the contract's (`ScreenTransition`): a clock of its length runs linearly and the frame function
 * of the plan says where both pages stand, so the numbers are the contract's and not an approximation by the platform's own
 * transitions. A new step finishes the one before it at once (the page that was coming is the one that goes).
 *
 * The page that goes takes no touch, no focus and is not heard from the first frame; the one that comes is a control from it.
 */
@Composable
internal fun <T : Any> PageTransition(key: Any, plan: ScreenTransitionPlan, value: T, content: @Composable (T) -> Unit) {
    // The page that goes is kept as the VALUE it was drawn from, not as a lambda: a composable lambda of a call site is
    // updated in place when the site recomposes, and the old reference would then draw the new page.
    val holder = remember { PageHolder<T>() }
    val outgoing = remember(key) { if (holder.key != null && plan.motion.isPage) holder.value else null }
    val run = remember(key) { Animatable(if (outgoing != null) 0f else 1f) }
    var finished by remember(key) { mutableStateOf(outgoing == null) }
    SideEffect {
        holder.key = key
        holder.value = value
    }
    LaunchedEffect(key) {
        if (outgoing == null) return@LaunchedEffect
        // A linear clock: the curves are the plan's.
        run.animateTo(1f, tween(plan.durationMs.toInt(), easing = LinearEasing))
        finished = true
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val playing = outgoing != null && !finished
    Box(Modifier.fillMaxSize()) {
        if (playing) {
            // The page that comes lies over the one under it on a push; the one that goes lies over it on a step back and a dissolve.
            Box(
                Modifier.fillMaxSize().zIndex(if (plan.motion == TransitionMotion.Push) 0f else 1f)
                    .focusProperties { canFocus = false }
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                    .graphicsLayer {
                        val layer = ScreenTransition.frame(plan, run.value * plan.durationMs).outgoing
                        alpha = layer.opacity.toFloat()
                        translationX = (if (rtl) -layer.x else layer.x).toFloat() * size.width
                    },
            ) { CompositionLocalProvider(LocalScreenInFront provides false, LocalAppearWindow provides closedWindow) { outgoing?.let { content(it) } } }
        }
        androidx.compose.runtime.key(key) {
            Box(
                Modifier.fillMaxSize().zIndex(if (plan.motion == TransitionMotion.Push) 1f else 0f)
                    .graphicsLayer {
                        if (finished || outgoing == null) return@graphicsLayer
                        val layer = ScreenTransition.frame(plan, run.value * plan.durationMs).incoming
                        alpha = layer.opacity.toFloat()
                        translationX = (if (rtl) -layer.x else layer.x).toFloat() * size.width
                    },
            ) { content(value) }
        }
    }
}
