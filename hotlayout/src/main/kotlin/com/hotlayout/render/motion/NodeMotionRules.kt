package com.hotlayout.render.motion

import com.hotlayout.JsonValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The endless motion of a node (`motion`): `float` drifts, `pulse` breathes, `spin` turns at a constant speed, `sway`
 * swings about a point of its box. A motion is a paint transform over the box the node has at rest; it changes no layout. The curve is not a field: one
 * formula with a sine gives the pose at every moment.
 */
internal data class NodeMotion(val kind: Kind, val duration: Int, val delay: Int) {
    enum class Axis(val word: String) { Vertical("vertical"), Horizontal("horizontal"), Both("both") }

    /** The point of the box a swaying node turns about. */
    enum class Pivot(val word: String) { Center("center"), Top("top"), Bottom("bottom") }

    sealed class Kind {
        data class Float(val axis: Axis, val amplitude: Int) : Kind()
        data class Pulse(val scale: Double, val opacity: Double?) : Kind()
        data class Spin(val clockwise: Boolean) : Kind()
        data class Sway(val angle: Int, val pivot: Pivot) : Kind()
    }

    /** The kinds that turn the node: a turn lies inside the turn of the layer of a `Stack`, the other kinds shift along the axes of the screen. */
    val turns: Boolean get() = kind is Kind.Spin || kind is Kind.Sway

    /**
     * A pose: a shift in logical pixels (x to the right, y down), a scale, an opacity factor and a turn in degrees, clockwise,
     * about [pivot].
     */
    data class Pose(
        val x: Double = 0.0, val y: Double = 0.0, val scale: Double = 1.0, val opacity: Double = 1.0,
        val rotate: Double = 0.0, val pivot: Pivot = Pivot.Center,
    ) {
        val isRest: Boolean get() = x == 0.0 && y == 0.0 && scale == 1.0 && opacity == 1.0 && rotate == 0.0

        companion object {
            val REST = Pose()
        }
    }

    /**
     * Where the node is painted [elapsedMs] after it appeared: `theta = 2π·((t − delay) mod duration) /
     * duration`; rest before the delay ends; «reduce motion» is rest. Toward the end of the line is
     * to the left in right-to-left text.
     */
    fun pose(elapsedMs: Double, reducedMotion: Boolean = false, rightToLeft: Boolean = false): Pose {
        if (reducedMotion) return Pose.REST
        val elapsed = elapsedMs - delay
        if (!elapsed.isFinite() || elapsed < 0) return Pose.REST
        val cycle = duration.toDouble()
        val phase = elapsed - cycle * floor(elapsed / cycle)
        val theta = 2 * PI * phase / cycle
        return when (kind) {
            is Kind.Float -> {
                val a = kind.amplitude.toDouble()
                val mirror = if (rightToLeft) -1.0 else 1.0
                val x = if (kind.axis == Axis.Vertical) 0.0 else mirror * a * sin(theta)
                val y = when (kind.axis) {
                    Axis.Horizontal -> 0.0
                    Axis.Vertical -> -a * sin(theta)
                    Axis.Both -> -a * sin(2 * theta)
                }
                // Negative zero is not a pose anybody asked for.
                Pose(x + 0.0, y + 0.0)
            }
            is Kind.Pulse -> {
                val p = (1 - cos(theta)) / 2
                Pose(scale = 1 + (kind.scale - 1) * p, opacity = 1 + ((kind.opacity ?: 1.0) - 1) * p)
            }
            // A constant speed, no curve and no direction of the line: an even turn is the point of spinning.
            is Kind.Spin -> Pose(rotate = (if (kind.clockwise) 1 else -1) * 360 * phase / cycle + 0.0)
            is Kind.Sway -> Pose(rotate = (if (rightToLeft) -1 else 1) * kind.angle * sin(theta) + 0.0, pivot = kind.pivot)
        }
    }

    companion object {
        val DURATION = 400..60000
        val DELAY = 1..60000
        val AMPLITUDE = 1..48
        val ANGLE = 1..45
        val SCALE = 0.5..1.5
        val OPACITY = 0.0..1.0

        /**
         * The motion of a value, or `null` when it is not a motion this reader can draw: a form it
         * does not know, a missing field, a number out of bounds or an extra field means NO motion.
         */
        fun read(value: JsonValue?): NodeMotion? {
            val obj = value?.obj ?: return null
            val duration = whole(obj["duration"])?.takeIf { it in DURATION } ?: return null
            var delay = 0
            if (obj["delay"] != null) delay = whole(obj["delay"])?.takeIf { it in DELAY } ?: return null
            fun only(own: Set<String>) = obj.all { (key, _) -> key == "kind" || key == "duration" || key == "delay" || key in own }
            return when (obj["kind"]?.string) {
                "float" -> {
                    if (!only(setOf("axis", "amplitude"))) return null
                    val axis = Axis.entries.firstOrNull { it.word == obj["axis"]?.string } ?: return null
                    val amplitude = whole(obj["amplitude"])?.takeIf { it in AMPLITUDE } ?: return null
                    NodeMotion(Kind.Float(axis, amplitude), duration, delay)
                }
                "pulse" -> {
                    if (!only(setOf("scale", "opacity"))) return null
                    val scale = finite(obj["scale"])?.takeIf { it in SCALE } ?: return null
                    var opacity: Double? = null
                    if (obj["opacity"] != null) opacity = finite(obj["opacity"])?.takeIf { it in OPACITY } ?: return null
                    NodeMotion(Kind.Pulse(scale, opacity), duration, delay)
                }
                "spin" -> {
                    if (!only(setOf("direction"))) return null
                    val direction = obj["direction"]?.string
                    if (direction != "clockwise" && direction != "counterclockwise") return null
                    NodeMotion(Kind.Spin(direction == "clockwise"), duration, delay)
                }
                "sway" -> {
                    if (!only(setOf("angle", "pivot"))) return null
                    val pivot = Pivot.entries.firstOrNull { it.word == obj["pivot"]?.string } ?: return null
                    val angle = whole(obj["angle"])?.takeIf { it in ANGLE } ?: return null
                    NodeMotion(Kind.Sway(angle, pivot), duration, delay)
                }
                else -> null
            }
        }

        private fun finite(value: JsonValue?): Double? = value?.number?.takeIf { it.isFinite() }

        internal fun whole(value: JsonValue?): Int? = finite(value)?.takeIf { it == Math.rint(it) && Math.abs(it) < 1e9 }?.toInt()
    }
}
