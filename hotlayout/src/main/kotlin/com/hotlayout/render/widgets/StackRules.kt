package com.hotlayout.render.widgets

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue

/** The alignment words of a stack and of a layer; anything else is "no word". */
internal enum class LayerAlignWord(val word: String) {
    Start("start"), Center("center"), End("end");

    companion object {
        fun of(value: JsonValue?): LayerAlignWord? = entries.firstOrNull { it.word == value?.string }
    }
}

/** A spacing as the document writes it: a token name or a whole number of logical pixels 0…128. */
internal sealed class SpacingReference {
    data class Token(val name: String) : SpacingReference()
    data class Pixels(val value: Int) : SpacingReference()

    val json: JsonValue get() = when (this) {
        is Token -> JsonValue.Str(name)
        is Pixels -> JsonValue.Num(value.toDouble())
    }

    companion object {
        const val MAX_PIXELS = 128
        private val tokenName = Regex("[a-z][a-zA-Z0-9]{0,39}")

        /** `null` for anything not understood: a CSS length, a fraction, a negative number, a number above 128. */
        fun of(value: JsonValue?): SpacingReference? = when (value) {
            is JsonValue.Str -> if (tokenName.matches(value.value)) Token(value.value) else null
            is JsonValue.Num -> value.number?.takeIf { it == Math.rint(it) && it >= 0 && it <= MAX_PIXELS }?.let { Pixels(it.toInt()) }
            else -> null
        }
    }
}

/** One axis of an offset: a direction word (`start` / `end`, or `top` / `bottom`) and the amount. */
internal data class LayerAxisOffset(val toward: String, val by: SpacingReference)

/**
 * The place of a layer in a stack as the reader reads it: everything unreadable is dropped, and a
 * half does not take the other half with it.
 */
internal data class LayerRead(
    val alignHorizontal: LayerAlignWord? = null,
    val alignVertical: LayerAlignWord? = null,
    val offsetX: LayerAxisOffset? = null,
    val offsetY: LayerAxisOffset? = null,
    /** Whole degrees clockwise in left-to-right text; 0 is no rotation. */
    val rotate: Int = 0,
) {
    companion object {
        private val horizontal = setOf("start", "end")
        private val vertical = setOf("top", "bottom")

        /** `null` when the value is not an object: then the node is an ordinary layer. */
        fun read(value: JsonValue?): LayerRead? {
            val obj = value?.obj ?: return null
            val offset = obj["offset"]?.obj ?: JsonObject()
            val rotate = obj["rotate"]?.number?.takeIf { it == Math.rint(it) && it >= -180 && it <= 180 }?.toInt() ?: 0
            return LayerRead(
                LayerAlignWord.of(obj["alignHorizontal"]), LayerAlignWord.of(obj["alignVertical"]),
                axis(offset["x"], horizontal), axis(offset["y"], vertical), rotate,
            )
        }

        private fun axis(value: JsonValue?, directions: Set<String>): LayerAxisOffset? {
            val obj = value?.obj ?: return null
            val toward = obj["toward"]?.string?.takeIf { it in directions } ?: return null
            return LayerAxisOffset(toward, SpacingReference.of(obj["by"]) ?: return null)
        }
    }
}

/**
 * What each layer of a `Stack` is, decided by the DOCUMENT and never by visibility
 * (`x-style.layout.layer`, `x-style.fillScope`).
 */
internal sealed class LayerKind {
    /** An ordinary layer: it takes its own size and measures the stack. */
    data object Sizing : LayerKind()

    /** A layer on the whole stack (`fill: true`): it takes the size of the stack. */
    data object Fill : LayerKind()

    /** An overlay (`layer`): it stands against the box of the stack and never changes it. */
    data class Overlay(val read: LayerRead) : LayerKind()

    val sizesStack: Boolean get() = this == Sizing
    val isOverlay: Boolean get() = this is Overlay
}

internal object LayerRules {
    /**
     * The kind of every child of a parent. Outside a `Stack` neither field is read; `fill` wins over
     * `layer`. An overlay is read only when the stack has a layer that is neither stretched nor an
     * overlay: otherwise there is no box to stand against and the field is read on none of them.
     */
    fun kinds(parentType: String, children: List<JsonObject>): List<LayerKind> {
        if (parentType != "Stack") return children.map { LayerKind.Sizing }
        val fills = children.map { it["fill"]?.bool == true }
        val reads = children.mapIndexed { i, fields -> if (fills[i]) null else LayerRead.read(fields["layer"]) }
        val sized = children.indices.any { !fills[it] && reads[it] == null }
        return children.indices.map { i ->
            val read = reads[i]
            when {
                fills[i] -> LayerKind.Fill
                sized && read != null -> LayerKind.Overlay(read)
                else -> LayerKind.Sizing
            }
        }
    }

    /**
     * The kinds as they act NOW. A stack whose DRAWN layers are all stretched has nothing to measure it
     * by, so the stretch is not applied and the layers take their own size; in every other case the
     * kinds stand, so an overlay never turns into a sizing layer because the one under it is hidden.
     */
    fun acting(kinds: List<LayerKind>, visible: List<Boolean>): List<LayerKind> {
        val someDrawnNotStretched = kinds.indices.any { visible[it] && kinds[it] != LayerKind.Fill }
        if (someDrawnNotStretched) return kinds
        return kinds.map { if (it == LayerKind.Fill) LayerKind.Sizing else it }
    }

    /** How many layers that size the stack are drawn; zero is a stack of zero size with its overlays still overlays. */
    fun visibleSizers(kinds: List<LayerKind>, visible: List<Boolean>): Int =
        kinds.indices.count { kinds[it] == LayerKind.Sizing && visible[it] }
}

/** Where an overlay stands: the geometry of the contract on plain numbers. */
internal object LayerGeometry {
    data class Input(
        val stackWidth: Double, val stackHeight: Double,
        val layerWidth: Double, val layerHeight: Double,
        val alignHorizontal: LayerAlignWord, val alignVertical: LayerAlignWord,
        /** The offset in logical pixels; `null` is none on that axis. */
        val offsetX: Pair<String, Double>? = null, val offsetY: Pair<String, Double>? = null,
        val rotate: Int = 0, val rightToLeft: Boolean,
    )

    /** The UNROTATED box after alignment and offset in PHYSICAL coordinates of the stack, and the angle as drawn. */
    data class Result(val x: Double, val y: Double, val width: Double, val height: Double, val angle: Double, val protrudes: Boolean) {
        val centerX: Double get() = x + width / 2
        val centerY: Double get() = y + height / 2
    }

    fun along(room: Double, size: Double, word: LayerAlignWord): Double = when (word) {
        LayerAlignWord.Start -> 0.0
        LayerAlignWord.End -> room - size
        LayerAlignWord.Center -> (room - size) / 2
    }

    /** Right-to-left text mirrors the layout and the turn keeps its look against its corner, so the sign changes. */
    fun drawnAngle(rotate: Int, rightToLeft: Boolean): Double = (if (rightToLeft) -rotate.toDouble() else rotate.toDouble()) + 0.0

    /** The start of the layer along the line, logical: from the start edge of the stack. */
    fun logicalStart(input: Input): Double {
        var start = along(input.stackWidth, input.layerWidth, input.alignHorizontal)
        input.offsetX?.let { start += if (it.first == "end") it.second else -it.second }
        return start
    }

    fun top(input: Input): Double {
        var y = along(input.stackHeight, input.layerHeight, input.alignVertical)
        input.offsetY?.let { y += if (it.first == "bottom") it.second else -it.second }
        return y
    }

    fun place(input: Input): Result {
        val start = logicalStart(input)
        val x = if (input.rightToLeft) input.stackWidth - start - input.layerWidth else start
        val angle = drawnAngle(input.rotate, input.rightToLeft)
        val result = Result(x, top(input), input.layerWidth, input.layerHeight, angle, false)
        return result.copy(protrudes = protrudes(result, input.stackWidth, input.stackHeight))
    }

    private fun protrudes(r: Result, stackWidth: Double, stackHeight: Double): Boolean {
        val radians = Math.toRadians(r.angle)
        val eps = 1e-9
        for ((sx, sy) in listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)) {
            val dx = sx * r.width / 2
            val dy = sy * r.height / 2
            val px = r.centerX + dx * Math.cos(radians) - dy * Math.sin(radians)
            val py = r.centerY + dx * Math.sin(radians) + dy * Math.cos(radians)
            if (px < -eps || py < -eps || px > stackWidth + eps || py > stackHeight + eps) return true
        }
        return false
    }

    /** Whether a point of the stack lies in the drawn (rotated) layer. */
    fun contains(r: Result, x: Double, y: Double): Boolean {
        val radians = -Math.toRadians(r.angle)
        val dx = x - r.centerX
        val dy = y - r.centerY
        val localX = dx * Math.cos(radians) - dy * Math.sin(radians)
        val localY = dx * Math.sin(radians) + dy * Math.cos(radians)
        return Math.abs(localX) <= r.width / 2 && Math.abs(localY) <= r.height / 2
    }
}
