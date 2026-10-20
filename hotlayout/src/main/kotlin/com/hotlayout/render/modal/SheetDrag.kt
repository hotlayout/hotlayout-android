package com.hotlayout.render.modal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * How far the body of the layer is scrolled from its top, in logical pixels. The reader does not
 * own the scroll state of the body; it learns the position from the scrolling that passes through
 * the layer (a drag and a fling both do), starting at the top.
 */
internal class ModalBodyScroll {
    var scrolled: Double = 0.0
}

/**
 * Drags a sheet down by the finger and tells how it was released. The gesture is watched in the
 * capture pass: nothing is taken until the move passes the touch slop, more down than sideways,
 * with the body at its top ([ModalRules.dragStarts]); then the move is consumed, and a button or a
 * scroll under the finger gets a cancelled gesture. The position of the body is read when the
 * finger goes down, as for every other reader: a body scrolled then does not drag the sheet in
 * the same gesture.
 */
internal fun Modifier.sheetDrag(
    enabled: Boolean,
    sink: ModalBodyScroll,
    offsetPx: () -> Float,
    density: Float,
    onDrag: (Float) -> Unit,
    onRelease: (draggedDp: Double, velocityDpPerSecond: Double) -> Unit,
): Modifier {
    val tracker = nestedScroll(object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            sink.scrolled = maxOf(0.0, sink.scrolled - consumed.y / density)
            return Offset.Zero
        }
    })
    if (!enabled) return tracker
    return tracker.pointerInput(density) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val scrolledAtDown = sink.scrolled
            var started = false
            val samples = ArrayList<ModalRules.Sample>()
            while (true) {
                val event = awaitPointerEventInitial()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (started) onRelease(offsetPx() / density.toDouble(), ModalRules.velocity(samples))
                    break
                }
                val dy = (change.position.y - down.position.y) / density.toDouble()
                val dx = (change.position.x - down.position.x) / density.toDouble()
                if (!started) {
                    if (!ModalRules.dragStarts(sheet = true, dismissible = true, scrolled = scrolledAtDown, dy = dy, dx = dx, onHandle = false)) continue
                    started = true
                }
                event.changes.forEach { it.consume() }
                onDrag(maxOf(0.0, dy).toFloat() * density)
                samples.add(ModalRules.Sample(change.uptimeMillis.toDouble(), dy))
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPointerEventInitial() =
    awaitPointerEvent(PointerEventPass.Initial)
