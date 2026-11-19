package com.hotlayout.render.motion

import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidgetType

/**
 * The one-time appearance of a node when its screen is shown (`appear`). The field is `{kind: 'fade', duration, delay?}` or
 * `{kind: 'rise', distance, duration, delay?}`. The curve is the one a node uses when its condition makes it appear
 * ([VisibilityFadeRules.inCurve]): the same act, one tempo. The appearance paints over the box the node has at rest and
 * changes no layout.
 */
internal data class NodeAppear(val kind: Kind, val distance: Int, val duration: Int, val delay: Int) {
    enum class Kind(val word: String) { Fade("fade"), Rise("rise") }

    /** Where a node stands at a moment of its appearance: the shift down in logical pixels and the opacity factor. */
    data class Pose(val y: Double = 0.0, val opacity: Double = 1.0) {
        companion object {
            val REST = Pose()
        }
    }

    /** The moment the appearance is over, milliseconds after the show. */
    val endsAt: Double get() = (delay + duration).toDouble()

    /**
     * The pose [elapsedMs] after the screen was shown: `p = clamp((t − delay) / duration, 0, 1)`, `e = cubic-bezier(0, 0, 0.2, 1)(p)`,
     * opacity `e`, a `rise` shifts down by `distance · (1 − e)`. While the delay runs the node stands in the START pose
     * (invisible, fully shifted), so a cascade does not flash. «Reduce motion» is rest.
     */
    fun pose(elapsedMs: Double, reducedMotion: Boolean = false): Pose {
        if (reducedMotion) return Pose.REST
        val elapsed = elapsedMs - delay
        val share = if (elapsed.isFinite()) (elapsed / duration).coerceIn(0.0, 1.0) else 0.0
        val eased = VisibilityFadeRules.cubicBezierAt(VisibilityFadeRules.inCurve, share)
        return Pose(y = (if (kind == Kind.Rise) distance * (1 - eased) else 0.0) + 0.0, opacity = eased)
    }

    /** The moments of a flow the table names. */
    enum class Moment(val word: String) {
        FirstShow("firstShow"), Forward("forward"), Back("back"), Switch("switch"),
        ModalClosed("modalClosed"), ConditionMet("conditionMet"), WidthClassChanged("widthClassChanged"), NewRecord("newRecord"),
    }

    companion object {
        const val FIELD = "appear"
        val DURATION = 100..2000
        val DISTANCE = 1..48

        /**
         * The appearance in a value, or `null` when it is not one this reader can draw: an unknown kind, a missing or surplus
         * field or a number out of bounds means NO appearance, and the node stands at once.
         */
        fun read(value: JsonValue?): NodeAppear? {
            val obj = value?.obj ?: return null
            val kind = Kind.entries.firstOrNull { it.word == obj["kind"]?.string } ?: return null
            val own = if (kind == Kind.Rise) setOf("kind", "distance", "duration", "delay") else setOf("kind", "duration", "delay")
            if (!obj.all { (key, _) -> key in own }) return null
            val duration = NodeMotion.whole(obj["duration"])?.takeIf { it in DURATION } ?: return null
            var delay = 0
            if (obj["delay"] != null) delay = NodeMotion.whole(obj["delay"])?.takeIf { it in NodeMotion.DELAY } ?: return null
            var distance = 0
            if (kind == Kind.Rise) distance = NodeMotion.whole(obj["distance"])?.takeIf { it in DISTANCE } ?: return null
            return NodeAppear(kind, distance, duration, delay)
        }

        /**
         * Whether the appearance plays at a moment: a show of the screen does; closing a layer above is not a show, and a node
         * that appears on a screen already shown (its condition, the width class, a new record) follows `visibilityTransition`
         * or stands at once.
         */
        fun plays(on: Moment): Boolean = when (on) {
            Moment.FirstShow, Moment.Forward, Moment.Back, Moment.Switch -> true
            Moment.ModalClosed, Moment.ConditionMet, Moment.WidthClassChanged, Moment.NewRecord -> false
        }

        /** Whether the appearance is read on a node: not on the root, not on a zone of the screen. */
        fun reads(node: DocumentNode, root: DocumentNode?): Boolean =
            node.widget != WidgetType.Screen && !VisibilityFadeRules.isScreenZoneNode(node, root)
    }
}

/**
 * The window a screen opens while it is built for its show: a node that is built inside it plays its appearance, a node that
 * finds it closed (it came later: its condition, a width class, a new record of a list) stands at once. A screen closes it
 * right after its first build.
 */
internal class AppearWindow {
    var isOpen: Boolean = true
        private set

    fun close() {
        isOpen = false
    }
}
