package com.hotlayout.render.motion

import com.hotlayout.json.JsonValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The endless motion of a node (`motion`): `float` drifts, `pulse` breathes. A motion is a paint
 * transform over the box the node has at rest; it changes no layout. The curve is not a field: one
 * formula with a sine gives the pose at every moment.
 */
internal data class NodeMotion(val kind: Kind, val duration: Int, val delay: Int) {
    enum class Axis(val word: String) { Vertical("vertical"), Horizontal("horizontal"), Both("both") }

    sealed class Kind {
        data class Float(val axis: Axis, val amplitude: Int) : Kind()
        data class Pulse(val scale: Double, val opacity: Double?) : Kind()
    }

    /** A pose: a shift in logical pixels (x to the right, y down), a scale and an opacity factor. */
    data class Pose(val x: Double = 0.0, val y: Double = 0.0, val scale: Double = 1.0, val opacity: Double = 1.0) {
        val isRest: Boolean get() = this == REST

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
        }
    }

    companion object {
        val DURATION = 400..60000
        val DELAY = 1..60000
        val AMPLITUDE = 1..48
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
                else -> null
            }
        }

        private fun finite(value: JsonValue?): Double? = value?.number?.takeIf { it.isFinite() }

        private fun whole(value: JsonValue?): Int? = finite(value)?.takeIf { it == Math.rint(it) && Math.abs(it) < 1e9 }?.toInt()
    }
}
