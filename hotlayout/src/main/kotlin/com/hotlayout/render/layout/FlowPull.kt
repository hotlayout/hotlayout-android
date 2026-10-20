package com.hotlayout.render.layout

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode

/**
 * `node.pull`: a child of a flow is drawn earlier by this much along the main axis of its parent and
 * everything after it follows (`x-style.layout.pull`).
 */
internal object FlowPull {
    enum class Axis { Column, Row }

    /** The axis of a flow, or `null` where the field is not read (a wrapping or scrolling row, a grid, a stack…). */
    fun axis(parentType: String, parentProps: JsonObject): Axis? = when (parentType) {
        "Screen", "Column" -> Axis.Column
        "Container" -> if (parentProps["direction"]?.string == "row") Axis.Row else Axis.Column
        "Row" -> if (parentProps["wrap"]?.bool != true && parentProps["scrollable"]?.bool != true) Axis.Row else null
        else -> null
    }

    /**
     * The reference of the pull of a child and its axis, or `null` when the field is not read: outside
     * a flow, on a zone of the screen, beside a weight that acts.
     */
    fun read(child: DocumentNode, parentType: String, parentProps: JsonObject): Pair<JsonValue, Axis>? {
        val axis = axis(parentType, parentProps) ?: return null
        if (child.type == "AppBar" || child.type == "NavBar") return null
        // The pinned block of the bottom has no pull; a node of the top zone keeps its own (the first of the zone is dropped by the zone).
        if (parentType == "Screen" && child.fields["pin"] != null && child.fields["pin"]?.string != "top") return null
        val by = child.fields["pull"]?.takeIf { isSpacingReference(it) } ?: return null
        if (axis == Axis.Row && validWeight(child) != null) return null
        return by to axis
    }

    /** A spacing token (`^[a-z][a-zA-Z0-9]{0,39}$`) or a whole number of logical pixels 0…128; anything else is no pull. */
    fun isSpacingReference(value: JsonValue): Boolean = when (value) {
        is JsonValue.Num -> value.number?.let { it >= 0 && it <= 128 && it == Math.rint(it) } == true
        is JsonValue.Str -> Regex("[a-z][a-zA-Z0-9]{0,39}").matches(value.value)
        else -> false
    }

    /**
     * How a column advances: each child begins where the previous one ended plus the gap, minus its own
     * pull (the first one — from zero minus its pull); the content is never shorter than nothing.
     */
    fun flow(sizes: List<Double>, pulls: List<Double>, gap: Double): Pair<List<Double>, Double> {
        val starts = ArrayList<Double>()
        var cursor = 0.0
        for (index in sizes.indices) {
            val start = cursor + (if (index == 0) 0.0 else gap) - pulls[index]
            starts.add(start)
            cursor = start + sizes[index]
        }
        return starts to maxOf(0.0, cursor)
    }
}

/** A weight that acts: a whole number 1…4, the bounds of the schema (`invalidWeight: 'no-weight'`). */
internal fun validWeight(child: DocumentNode): Double? {
    val weight = child.fields["weight"]?.number
    return weight?.takeIf { it >= 1 && it <= 4 && it == Math.rint(it) }
}
