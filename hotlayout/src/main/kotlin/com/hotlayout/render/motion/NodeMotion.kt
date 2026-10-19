package com.hotlayout.render.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

/** A fixed time (ms since the node appeared) for every moving node below, instead of the clock of the screen; tests read a real node at a chosen moment. */
internal val LocalNodeMotionClockMs = compositionLocalOf<Double?> { null }

/**
 * The turn (degrees, clockwise) of the layer a `Stack` places the node in. The node's motion is
 * painted along the axes of the screen, outside that turn, so its shift is turned back by it. Only a
 * node whose parent is a stack reads it.
 */
internal val LocalLayerTurn = compositionLocalOf { 0f }

/** The frames the clocks of moving nodes asked for; a test proves that a node outside the window asks for none. */
internal object NodeMotionFrames {
    val requested = AtomicInteger()
}

/**
 * Paints the motion of a node over the box it has at rest: a shift along the axes of the screen, a
 * scale about the centre of the box and an opacity that multiplies the node's own. Nothing here
 * touches layout, so the neighbours stay where they are.
 *
 * The outer box stands at rest and the drawn one moves inside it, so a touch lands only where the
 * rest box and the drawn box overlap. The clock asks for frames only while the box is inside the
 * window; «reduce motion» (the system animator scale 0) is rest and asks for none.
 */
@Composable
internal fun NodeMotionBox(motion: NodeMotion, rightToLeft: Boolean, turnDegrees: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val fixed = LocalNodeMotionClockMs.current
    val density = LocalDensity.current.density
    val window = LocalWindowInfo.current.containerSize
    var onScreen by remember { mutableStateOf(false) }
    val begin = remember { mutableLongStateOf(-1L) }
    val elapsed = remember { mutableDoubleStateOf(0.0) }
    if (fixed == null && !reduced) {
        LaunchedEffect(onScreen) {
            if (!onScreen) return@LaunchedEffect
            while (true) {
                withFrameMillis { now ->
                    NodeMotionFrames.requested.incrementAndGet()
                    if (begin.longValue < 0) begin.longValue = now
                    elapsed.doubleValue = (now - begin.longValue).toDouble()
                }
            }
        }
    }
    val poseNow = {
        val pose = motion.pose(fixed ?: elapsed.doubleValue, reduced, rightToLeft)
        val x = pose.x * density
        val y = pose.y * density
        val angle = Math.toRadians(turnDegrees.toDouble())
        Layer(
            (x * cos(angle) + y * sin(angle)).toFloat(), (-x * sin(angle) + y * cos(angle)).toFloat(),
            pose.scale.toFloat(), pose.opacity.toFloat(),
        )
    }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            onScreen = coordinates.isAttached && !bounds.isEmpty && bounds.right > 0f && bounds.bottom > 0f && bounds.left < window.width && bounds.top < window.height
        },
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val layer = poseNow()
                    translationX = layer.x
                    translationY = layer.y
                    scaleX = layer.scale
                    scaleY = layer.scale
                    alpha = layer.opacity
                }
                .restOnly(poseNow),
            propagateMinConstraints = true,
        ) { content() }
    }
}

/** What the layer of a moving node is set to: a shift in pixels, a scale and an opacity. */
private class Layer(val x: Float, val y: Float, val scale: Float, val opacity: Float)

/**
 * A touch that starts outside the box the node has at rest does not press it, whatever the drawn
 * box covers: the target is where the two overlap. This stands inside the layer, so a position is
 * one of the drawn box; the layer's own transform takes it back to the box at rest.
 */
private fun Modifier.restOnly(layer: () -> Layer): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val now = layer()
            for (change in event.changes) {
                if (!change.changedToDown()) continue
                val x = w / 2 + now.scale * (change.position.x - w / 2) + now.x
                val y = h / 2 + now.scale * (change.position.y - h / 2) + now.y
                if (!Rect(0f, 0f, w, h).contains(Offset(x, y))) change.consume()
            }
        }
    }
}
