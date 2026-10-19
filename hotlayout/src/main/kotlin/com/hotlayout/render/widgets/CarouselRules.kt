package com.hotlayout.render.widgets

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import kotlin.math.abs

/** The `carousel` field of a sideways row as the reader reads it. */
internal data class CarouselSpec(
    /** Whole cards in view, 1…6; `null` keeps the natural widths. */
    val perView: Int? = null,
    /** A spacing token or a whole number of pixels, as the document wrote it; `null` is the default. */
    val peek: JsonValue? = null,
    val dots: Boolean = true,
)

/**
 * The rules of the carousel (`x-style.layout.carousel`): who can be one, how the field is read, the
 * width of a card, the snap stops, the active stop and the dots. Plain numbers in logical pixels;
 * the theme and the measuring are done by the caller.
 */
internal object CarouselRules {
    val perViewRange = 1..6
    const val MAX_SPACING = 128.0
    const val DEFAULT_PEEK = "lg"
    const val MAX_DOTS = 10
    const val INACTIVE_DOT_OPACITY = 0.3
    const val MIN_CARD = 1.0
    const val STOP_MERGE = 0.5
    const val DOT_SIZE_TOKEN = "sm"
    const val DOT_GAP_TOKEN = "sm"
    const val INDICATOR_GAP_TOKEN = "sm"
    const val DOT_COLOR_TOKEN = "primary"

    /** A row that scrolls, or a data list laid out in a row. A list with `columns` never reads its direction. Props decide, not the type alone. */
    fun isHost(type: String, props: JsonObject): Boolean = when (type) {
        "Row" -> props["scrollable"]?.bool == true
        "Repeater" -> props["direction"]?.string == "row" && RepeaterPlan.columns(props) == null
        else -> false
    }

    /** The field as the reader reads it; `null` when it is not an object. */
    fun read(value: JsonValue?): CarouselSpec? {
        val obj = value?.obj ?: return null
        var perView: Int? = null
        val number = obj["perView"]?.number
        if (number != null && number == Math.rint(number) && number.toInt() in perViewRange) perView = number.toInt()
        var peek: JsonValue? = null
        when (val raw = obj["peek"]) {
            is JsonValue.Str -> if (raw.value.isNotEmpty()) peek = raw
            is JsonValue.Num -> raw.number?.let { if (it == Math.rint(it) && it >= 0 && it <= MAX_SPACING) peek = raw }
            else -> {}
        }
        return CarouselSpec(perView, peek, dots = obj["indicator"]?.string != "none")
    }

    /** The carousel of a node: it must be a host and the field must be readable. */
    fun of(node: DocumentNode): CarouselSpec? = if (isHost(node.type, node.props)) read(node.props["carousel"]) else null

    /** The peek in pixels. A token the theme does not declare is the default token. */
    fun peekPixels(peek: JsonValue?, spacing: (String) -> Double?): Double {
        if (peek is JsonValue.Num) peek.number?.let { return it }
        if (peek is JsonValue.Str) spacing(peek.value)?.let { return it }
        return spacing(DEFAULT_PEEK) ?: 16.0
    }

    /**
     * The width of a card BOX for `perView`: `(viewport − padding start − perView × gap − peek) ÷ perView`,
     * never rounded. `null` when it is not a card (narrower than one pixel): natural widths.
     */
    fun cardWidth(viewport: Double, paddingStart: Double, gap: Double, peek: Double, perView: Int): Double? {
        val width = (viewport - paddingStart - perView * gap - peek) / perView
        return if (width.isFinite() && width >= MIN_CARD) width else null
    }

    class Stops(val stops: List<Double>, val maxScroll: Double, /** Which stop shows each card at its start (or at the end of the scroll). */ val stopOfCard: List<Int>)

    /** The snap stops: the start of every card, up to the end of the scroll, which is a stop always. `cards` are outer widths (box and margin). */
    fun stops(viewport: Double, paddingStart: Double, paddingEnd: Double, gap: Double, cards: List<Double>): Stops {
        val starts = ArrayList<Double>()
        var cursor = 0.0
        for ((index, card) in cards.withIndex()) {
            starts.add(cursor)
            cursor += card + if (index < cards.size - 1) gap else 0.0
        }
        val maxScroll = maxOf(0.0, paddingStart + cursor + paddingEnd - viewport)
        val stops = ArrayList<Double>()
        val stopOfCard = ArrayList<Int>()
        for (start in starts) {
            if (maxScroll > 0 && start < maxScroll - STOP_MERGE) {
                stops.add(start)
            } else if (stops.lastOrNull() != maxScroll) {
                stops.add(maxScroll)
            }
            stopOfCard.add(stops.size - 1)
        }
        if (stops.isEmpty()) stops.add(0.0)
        if (maxScroll > 0 && stops.last() < maxScroll - STOP_MERGE) stops.add(maxScroll)
        return Stops(stops, maxScroll, stopOfCard)
    }

    /** The stop nearest to the offset (from the logical start); a tie is the earlier one. */
    fun activeStop(stops: List<Double>, offset: Double): Int {
        val at = if (offset.isFinite()) maxOf(0.0, offset) else 0.0
        var best = 0
        var distance = Double.POSITIVE_INFINITY
        for ((index, stop) in stops.withIndex()) if (abs(stop - at) < distance) {
            best = index
            distance = abs(stop - at)
        }
        return best
    }

    fun dotsRowWidth(count: Int, dotSize: Double, dotGap: Double): Double = if (count > 0) count * dotSize + (count - 1) * dotGap else 0.0

    /** How many dots are drawn: one per stop; none for a single stop, more than ten, or a row wider than the area. */
    fun dotCount(dots: Boolean, stops: Int, viewport: Double, dotSize: Double, dotGap: Double): Int {
        if (!dots || stops <= 1 || stops > MAX_DOTS) return 0
        return if (dotsRowWidth(stops, dotSize, dotGap) <= viewport) stops else 0
    }

    /** The height under the area: the gap and a dot; nothing when no dots are drawn. */
    fun indicatorBlock(dots: Int, gap: Double, dotSize: Double): Double = if (dots > 0) gap + dotSize else 0.0

    /** The key of the state: the node and the chain of template instances it stands in. */
    fun stateKey(nodeId: String, instancePath: List<Int>): String = "${nodeId.length}:$nodeId|${instancePath.joinToString(",")}"
}

/**
 * What the carousels of the screens in the stack remember: the active stop, per frame of the stack
 * and per node (with its template instances). A frame leaving the stack takes its entries.
 */
internal class CarouselMemory {
    private val stops = HashMap<String, Int>()

    fun stop(key: String): Int? = stops[key]

    fun set(stop: Int, key: String) { stops[key] = stop }

    /** Keeps the entries of the frames still in the stack (frame index and screen). */
    fun prune(frames: List<String>) {
        val live = frames.mapIndexed { index, screen -> key(index, screen, "") }
        stops.keys.retainAll { entry -> live.any { entry.startsWith(it) } }
    }

    val count: Int get() = stops.size

    companion object {
        fun key(frame: Int, screenId: String, state: String): String = "$frame\u001F$screenId\u001F$state"
    }
}
