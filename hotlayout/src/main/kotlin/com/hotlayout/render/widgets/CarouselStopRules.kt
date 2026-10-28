package com.hotlayout.render.widgets

import com.hotlayout.JsonValue
import com.hotlayout.actions.WriteActions
import com.hotlayout.actions.WriteNumber
import com.hotlayout.model.DocumentNode

/**
 * The carousel tells the active stop (`carousel.stopPath`, the `x-carouselStop` block): the path of a number in
 * the data. The value is the NUMBER of the snap stop from 0, not of a card. A person's swipe, once the scroll
 * has settled on a stop, writes the number there (the write of an input field); the carousel stands on the stop
 * the value names when it is shown and moves to it when the number changes. The gestures and the scrolling are
 * done by [CarouselRow]; these are the rules.
 */
internal object CarouselStopRules {
    const val KEY = "stopPath"
    const val PATH_MAX = 128

    /** The path as the reader sees it: the node is a carousel and the field is a non-empty string within the bound. */
    fun path(node: DocumentNode): String? {
        if (CarouselRules.of(node) == null) return null
        val path = node.props["carousel"]?.obj?.get(KEY)?.string ?: return null
        return path.takeIf { it.isNotEmpty() && it.length <= PATH_MAX }
    }

    /** The stop number a value names: a number with an integer value (`1` and `1.0` alike); otherwise none. */
    fun number(value: JsonValue?): Int? {
        val number = WriteNumber.read(value) ?: return null
        return if (number == Math.rint(number) && Math.abs(number) <= Int.MAX_VALUE) number.toInt() else null
    }

    /** The stop to show for a value: its number clamped to `[0, stops - 1]`; no number means the first. */
    fun shown(value: JsonValue?, stops: Int): Int {
        val last = maxOf(0, stops - 1)
        return number(value)?.coerceIn(0, last) ?: 0
    }

    /**
     * Where to go when the value changed, or `null` for nowhere. The event is a change of the NUMBER, not a
     * comparison of what is shown with the data at draw time: a write the host refused leaves the carousel where
     * the person put it.
     */
    fun follow(previous: JsonValue?, next: JsonValue?, stops: Int, active: Int): Int? {
        if (number(previous) == number(next)) return null
        return shown(next, stops).takeIf { it != active }
    }

    /** What to write when the scroll settled: the stop number, only after a person's move and only if it is not already there. */
    fun write(settled: Int, byPerson: Boolean, current: JsonValue?): Int? {
        if (!byPerson || settled < 0) return null
        return settled.takeIf { !WriteActions.holdsWritten(current, JsonValue.Num(it.toDouble())) }
    }
}
