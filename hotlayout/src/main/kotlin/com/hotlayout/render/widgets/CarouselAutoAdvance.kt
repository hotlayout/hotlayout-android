package com.hotlayout.render.widgets

import com.hotlayout.JsonValue
import com.hotlayout.actions.WriteActions
import com.hotlayout.model.DocumentNode

/**
 * A carousel that advances by time (`carousel.autoAdvance`). The optional field is an object `{intervalMs, loop?}`: its
 * presence turns the timer on. The timer counts the time the carousel stood on the current stop WITHOUT a pause; when the
 * count reaches the interval it takes the carousel to the next stop (a stop of the snap list, not the next card), as the
 * reader's own move, and writes the new stop number to `stopPath` when the carousel has one.
 */
internal data class CarouselAutoAdvance(
    /** The interval in milliseconds, already within its bounds. */
    val intervalMs: Double,
    /** After the last stop, back to the first. */
    val loop: Boolean = false,
) {
    /** What pauses the timer, in the order of the artifact's list. */
    enum class Pause(val word: String) {
        /** A finger or a pointer button is down on the carousel. */
        Held("held"),

        /** Keyboard focus is inside the carousel. */
        KeyboardFocus("keyboardFocus"),

        /** The scroll is still moving: a gesture, a fling or the animation of a step. */
        Moving("moving"),

        /** The screen is not the top of the stack, a layer covers it, the app is in the background. */
        NotShown("notShown"),

        /** A screen reader is on: no timer while it runs. */
        ScreenReader("screenReader"),
    }

    /** The state of the timer: the stop the count runs from and the time the carousel has stood on it without a pause. */
    data class State(val active: Int, val elapsed: Double)

    /** One tick: the new state and where the timer leads the carousel (`null`: nowhere). */
    data class Tick(val state: State, val step: Int?)

    companion object {
        const val FIELD = "autoAdvance"
        const val MIN_MS = 2000.0
        const val MAX_MS = 15000.0

        /** How often the clock ticks. A tick counts exactly this much time: a timer that is late loses time rather than catching up. */
        const val TICK_MS = 100L

        /** How long a scroll stays quiet before it counts as no longer moving. */
        const val QUIET_MS = 150L

        /** The interval a value names: clamped to its bounds; not a number is the lower bound. */
        fun interval(value: JsonValue?): Double {
            val number = (value as? JsonValue.Num)?.value?.takeIf { it.isFinite() } ?: return MIN_MS
            return number.coerceIn(MIN_MS, MAX_MS)
        }

        /** The timer a `carousel` object names, or `null` when it names none: the field must be an object. */
        fun of(carousel: JsonValue?): CarouselAutoAdvance? {
            val timer = carousel?.obj?.get(FIELD)?.obj ?: return null
            return CarouselAutoAdvance(interval(timer["intervalMs"]), timer["loop"]?.bool == true)
        }

        /** The timer of a node: it must be a carousel host. */
        fun of(node: DocumentNode): CarouselAutoAdvance? = if (CarouselRules.of(node) == null) null else of(node.props["carousel"])

        /**
         * The stop the timer leads to from [active]: the next one; from the last, nowhere or (with [loop]) the first. With fewer
         * than two stops there is no timer. An active stop out of range is clamped first.
         */
        fun next(active: Int, stopCount: Int, loop: Boolean): Int? {
            if (stopCount < 2) return null
            val from = active.coerceIn(0, stopCount - 1)
            if (from + 1 < stopCount) return from + 1
            return if (loop) 0 else null
        }

        /**
         * ONE TICK. A pause changes nothing (the count is frozen and resumes with what was left). Then the stop: when it is not
         * the one the count runs from, somebody else moved it (a person, the value of `stopPath`, fewer stops) and the count
         * restarts from 0 there, with no step. Otherwise the count grows by the time since the last tick (a negative one is
         * nothing) and is held at the interval; on the interval comes ONE step to the next stop (a long gap never catches up)
         * and the count restarts from 0; with nowhere to go the count stands at the interval.
         */
        fun tick(state: State, dtMs: Double, active: Int, stopCount: Int, paused: Boolean, intervalMs: Double, loop: Boolean): Tick {
            if (paused) return Tick(state, null)
            if (active != state.active) return Tick(State(active, 0.0), null)
            val dt = if (dtMs.isFinite() && dtMs > 0) dtMs else 0.0
            val elapsed = (state.elapsed + dt).coerceIn(0.0, intervalMs)
            if (elapsed < intervalMs) return Tick(State(state.active, elapsed), null)
            val next = next(state.active, stopCount, loop) ?: return Tick(State(state.active, elapsed), null)
            return Tick(State(next, 0.0), next)
        }

        /** What the step writes to `stopPath`: the number of the new stop, unless the value already is it. */
        fun write(step: Int, current: JsonValue?): Int? {
            if (step < 0) return null
            return if (WriteActions.holdsWritten(current, JsonValue.Num(step.toDouble()))) null else step
        }
    }
}

/** The stop a carousel stands on and the number of its stops. */
internal data class CarouselStopView(val active: Int, val stopCount: Int)

/**
 * The clock of one carousel that advances by time. It counts in [CarouselAutoAdvance.TICK_MS] steps and asks the carousel for
 * the stop it stands on; the carousel tells it what holds the count and carries out the steps. The count starts at the show:
 * the first tick after the stops are laid out only notes the stop. A pause freezes the count and it resumes with what was
 * left. [tick] is all that moves it, so a table drives it without a timer.
 */
internal class CarouselAutoAdvanceDriver {
    /** The stop the carousel stands on and the number of stops; `null` while the stops are not laid out. */
    var view: () -> CarouselStopView? = { null }

    /** The timer takes the carousel to this stop. */
    var onStep: (Int) -> Unit = {}

    var config: CarouselAutoAdvance? = null
        private set
    private var state: CarouselAutoAdvance.State? = null
    private val paused = HashSet<CarouselAutoAdvance.Pause>()
    private var quietLeftMs = 0L

    /** Sets the timer the carousel is configured with; `null` stops the clock. A new timer counts from the next show of the stops. */
    fun configure(new: CarouselAutoAdvance?) {
        if (new == config) return
        config = new
        state = null
    }

    val isPaused: Boolean get() = paused.isNotEmpty() || quietLeftMs > 0

    /** Sets or clears one pause. */
    fun set(pause: CarouselAutoAdvance.Pause, on: Boolean) {
        if (on) paused.add(pause) else paused.remove(pause)
    }

    /** The scroll moved (a gesture, a fling, the animation of a step): the count waits until it has been quiet for a while. */
    fun scrollMoved() {
        quietLeftMs = CarouselAutoAdvance.QUIET_MS
    }

    /** One tick of the clock. */
    fun tick() {
        val config = config ?: return
        val seen = view() ?: return
        val current = state
        if (current == null) {
            // The show: the count starts here.
            state = CarouselAutoAdvance.State(seen.active, 0.0)
            return
        }
        val pausedNow = isPaused
        quietLeftMs = maxOf(0L, quietLeftMs - CarouselAutoAdvance.TICK_MS)
        val result = CarouselAutoAdvance.tick(
            current, CarouselAutoAdvance.TICK_MS.toDouble(), seen.active, seen.stopCount, pausedNow, config.intervalMs, config.loop,
        )
        state = result.state
        result.step?.let(onStep)
    }
}
