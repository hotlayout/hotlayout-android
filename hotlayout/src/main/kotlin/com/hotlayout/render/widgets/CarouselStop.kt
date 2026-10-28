package com.hotlayout.render.widgets

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.hotlayout.JsonValue

/** How long a move of the reader takes to reach a stop. */
private const val MOVE_MS = 260

/** What [CarouselStopEffects] needs of the row at this composition. */
internal class CarouselStopInput(
    val path: String?,
    /** The value under the path now. */
    val value: JsonValue?,
    /** The snap stops in pixels. */
    val stopsPx: List<Float>,
    val density: Double,
    /** The stops were laid out and the carousel stands on its first stop. */
    val shown: Boolean,
    val reducedMotion: Boolean,
    val write: ((String, JsonValue) -> Unit)?,
)

/**
 * What the carousel knows about who moves it. A person is a finger (or a wheel, or a key) on the row; the
 * reader moves it only to show a stop and to follow the value, and such a move never writes. [input] is the
 * latest composition, read by the listeners at the moment of the event.
 */
internal class CarouselStopTracker {
    var input: CarouselStopInput = CarouselStopInput(null, null, emptyList(), 1.0, false, false, null)
    var previous: JsonValue? = null
    private var person = false
    private var holding = false
    private var pending = false

    /**
     * The stop the reader is scrolling towards (the target of its last own move) until that move has settled.
     * While it is set, "where the carousel is" for following is this stop, not the one the animation passes.
     */
    var heading: Int? = null
        private set

    /** Listens for a person on the row without taking any event from the scroll. */
    fun listen(): Modifier = Modifier
        .pointerInput(this) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) { person = true; heading = null }
                    val wasHolding = holding
                    holding = event.changes.any { it.pressed }
                    if (wasHolding && !holding) settleIfPending()
                }
            }
        }
        .onPreviewKeyEvent {
            person = true
            heading = null
            false
        }

    /** A move by the READER: it clears the mark of a person and never writes. */
    suspend fun byReader(block: suspend () -> Unit) {
        person = false
        pending = false
        block()
    }

    /** The scroll stopped moving. */
    fun scrolled(moving: Boolean, scroll: ScrollState) {
        if (moving) return
        if (!person) return
        if (holding) {
            pending = true
            return
        }
        settle(scroll)
    }

    private var settleScroll: ScrollState? = null

    private fun settleIfPending() {
        val scroll = settleScroll ?: return
        if (pending && !scroll.isScrollInProgress) settle(scroll)
    }

    private fun settle(scroll: ScrollState) {
        pending = false
        person = false
        val now = input
        val path = now.path ?: return
        val stops = now.stopsPx
        if (stops.isEmpty()) return
        val active = CarouselRules.activeStop(stops.map { it / now.density }, scroll.value / now.density)
        val value = CarouselStopRules.write(active, true, now.value) ?: return
        now.write?.invoke(path, JsonValue.Num(value.toDouble()))
    }

    fun bind(scroll: ScrollState) { settleScroll = scroll }

    val isHolding: Boolean get() = holding

    /** Moves to [stop] as the reader. */
    suspend fun go(scroll: ScrollState, stop: Int) {
        val stops = input.stopsPx
        if (stop !in stops.indices) return
        val to = stops[stop].coerceAtMost(scroll.maxValue.toFloat()).toInt()
        if (heading == null && kotlin.math.abs(scroll.value - to) < 1) return
        heading = stop
        byReader {
            if (input.reducedMotion) scroll.scrollTo(to) else scroll.animateScrollTo(to, tween(MOVE_MS, easing = FastOutSlowInEasing))
        }
        // Reached only when the move was not cancelled by a newer one: a cancelled move leaves the heading to the next.
        if (heading == stop) heading = null
    }
}

/**
 * Writes the stop after a person's swipe has settled and moves the carousel when the NUMBER the value names
 * changes (not while a finger holds the row). Nothing happens when the node names no path.
 */
@Composable
internal fun CarouselStopEffects(tracker: CarouselStopTracker, scroll: ScrollState, input: CarouselStopInput) {
    SideEffect {
        tracker.input = input
        tracker.bind(scroll)
    }
    if (input.path == null) return
    LaunchedEffect(scroll, tracker) {
        snapshotFlow { scroll.isScrollInProgress }.collect { tracker.scrolled(it, scroll) }
    }
    val number = CarouselStopRules.number(input.value)
    LaunchedEffect(number, input.shown) {
        if (!input.shown) return@LaunchedEffect
        val before = tracker.previous
        tracker.previous = input.value
        if (tracker.isHolding) return@LaunchedEffect
        val active = tracker.heading ?: CarouselRules.activeStop(input.stopsPx.map { it / input.density }, scroll.value / input.density)
        CarouselStopRules.follow(before, input.value, input.stopsPx.size, active)?.let { tracker.go(scroll, it) }
    }
}
