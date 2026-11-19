package com.hotlayout.render.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

/** A fixed time (ms since the node appeared) for every moving node below, instead of the clock of the screen; tests read a real node at a chosen moment. */
internal val LocalNodeMotionClockMs = compositionLocalOf<Double?> { null }

/** A fixed time (ms after the show) for every appearing node below, instead of the clock of the screen; tests read a real node at a chosen moment. */
internal val LocalNodeAppearClockMs = compositionLocalOf<Double?> { null }

/** The window of the screen the node is drawn in; a node drawn with no screen around it plays its appearance. */
internal val LocalAppearWindow = compositionLocalOf<AppearWindow?> { null }

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
internal fun NodeMotionBox(motion: NodeMotion?, appear: NodeAppear?, rightToLeft: Boolean, turnDegrees: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val fixed = LocalNodeMotionClockMs.current
    val fixedAppear = LocalNodeAppearClockMs.current
    // A node plays its appearance only if the screen's window was open when it was built; one that came later stands at once.
    val window = LocalAppearWindow.current
    val plays = remember { window?.isOpen ?: true }
    val appearing = if (plays) appear else null
    val density = LocalDensity.current.density
    val containerSize = LocalWindowInfo.current.containerSize
    var onScreen by remember { mutableStateOf(false) }
    val begin = remember { mutableLongStateOf(-1L) }
    val elapsed = remember { mutableDoubleStateOf(0.0) }
    // A glass node inside moves with this layer, which the page of the glass does not see: each frame redraws that page.
    val glassInside = remember { com.hotlayout.render.paint.GlassInside() }
    val glassSource = com.hotlayout.render.paint.LocalGlassSource.current
    val needsClock = (motion != null && fixed == null) || (appearing != null && fixedAppear == null)
    if (needsClock && !reduced) {
        LaunchedEffect(onScreen) {
            if (!onScreen) return@LaunchedEffect
            while (true) {
                withFrameMillis { now ->
                    NodeMotionFrames.requested.incrementAndGet()
                    if (begin.longValue < 0) begin.longValue = now
                    elapsed.doubleValue = (now - begin.longValue).toDouble()
                    if (glassInside.inside) glassSource?.invalidate()
                }
                // Nothing moves any more: the appearance is over and the motion has no clock of this loop (none, or a fixed one).
                if ((motion == null || fixed != null) && elapsed.doubleValue >= (appearing?.endsAt ?: 0.0)) break
            }
        }
    }
    // «Reduce motion», or a node that has nothing to play (its window was closed): plain, with no layer, no clock and no frames.
    if (reduced || (motion == null && appearing == null)) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    val poseNow = {
        val pose = motion?.pose(fixed ?: elapsed.doubleValue, reduced, rightToLeft) ?: NodeMotion.Pose.REST
        // The appearance lies over the pose of the motion: its shift adds, its opacity multiplies.
        val shown = appearing?.pose(fixedAppear ?: elapsed.doubleValue, reduced) ?: NodeAppear.Pose.REST
        val x = pose.x * density
        val y = (pose.y + shown.y) * density
        val angle = Math.toRadians(turnDegrees.toDouble())
        Layer(
            (x * cos(angle) + y * sin(angle)).toFloat(), (-x * sin(angle) + y * cos(angle)).toFloat(),
            pose.scale.toFloat(), (pose.opacity * shown.opacity).toFloat(), pose.rotate.toFloat(),
            when (pose.pivot) {
                NodeMotion.Pivot.Top -> 0f
                NodeMotion.Pivot.Center -> 0.5f
                NodeMotion.Pivot.Bottom -> 1f
            },
        )
    }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            onScreen = coordinates.isAttached && !bounds.isEmpty && bounds.right > 0f && bounds.bottom > 0f && bounds.left < containerSize.width && bounds.top < containerSize.height
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
                    // A turn about a point of the box (the middle of the top or the bottom edge, or the centre).
                    rotationZ = layer.rotate
                    transformOrigin = TransformOrigin(0.5f, layer.pivotY)
                }
                // The opacity of a pulse is the opacity of the whole box with the shadow it paints outside it: a layer of the node's
                // own size would cut the shadow, so the content goes through an offscreen layer much larger than the box.
                .drawWithContent {
                    val opacity = poseNow().opacity
                    if (opacity >= 1f) {
                        drawContent()
                    } else {
                        drawIntoCanvas { canvas ->
                            val reach = SHADOW_REACH_DP * density
                            canvas.saveLayer(Rect(-reach, -reach, size.width + reach, size.height + reach), Paint().apply { alpha = opacity })
                            drawContent()
                            canvas.restore()
                        }
                    }
                }
                .restOnly(poseNow),
            propagateMinConstraints = true,
        ) { CompositionLocalProvider(com.hotlayout.render.paint.LocalGlassInside provides glassInside) { content() } }
    }
}

/** How far outside its box a node may paint (its shadow), in logical pixels: the offscreen layer of a translucent node reaches that far. */
private const val SHADOW_REACH_DP = 400f

/** What the layer of a moving node is set to: a shift in pixels, a scale and an opacity. */
private class Layer(val x: Float, val y: Float, val scale: Float, val opacity: Float, val rotate: Float, val pivotY: Float)

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
                // About the pivot of the layer: scaled, turned, then shifted.
                val originY = h * now.pivotY
                val turn = Math.toRadians(now.rotate.toDouble())
                val dx = now.scale * (change.position.x - w / 2)
                val dy = now.scale * (change.position.y - originY)
                val x = w / 2 + (dx * cos(turn) - dy * sin(turn)).toFloat() + now.x
                val y = originY + (dx * sin(turn) + dy * cos(turn)).toFloat() + now.y
                if (!Rect(0f, 0f, w, h).contains(Offset(x, y))) change.consume()
            }
        }
    }
}
