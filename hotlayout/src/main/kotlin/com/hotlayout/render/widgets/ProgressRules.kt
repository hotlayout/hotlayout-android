package com.hotlayout.render.widgets

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.json.wholeIn
import com.hotlayout.text.DecimalText
import com.hotlayout.theme.GradientValue
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.StyleResolver
import com.hotlayout.theme.TextStyleValue
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The geometry and the rules of the progress scale, as pure functions: the bar and the ring are one
 * scale in two forms (`x-style.layout.progress`, `progressSegments`, `progressCap`, `progressLabel`,
 * `progressGlow`, `segmentsTransition`). Nothing here draws.
 */
internal object ProgressRules {
    const val DEFAULT_THICKNESS = "sm"
    const val DEFAULT_TRACK_COLOR = "secondary"
    const val DEFAULT_COLOR = "primary"
    const val RING_UNIT = "xl"
    val ringScale = mapOf("sm" to 2.0, "md" to 3.0, "lg" to 4.0)
    const val DEFAULT_RING_SIZE = "md"
    const val SEGMENT_GAP = "xs"
    val segmentsRange = 2..12
    const val SEGMENT_PRECISION = 6
    const val MIN_SEGMENT_INLINE = 1.0
    const val DEFAULT_LABEL_TYPOGRAPHY = "caption"
    const val DEFAULT_LABEL_COLOR = "onSurface"
    const val BAR_LABEL_COLOR = "muted"
    const val BAR_LABEL_GAP = "xs"

    // region Ratio

    /** `value / max` clamped to 0…1; no number, no scale (`max` ≤ 0) and not finite give 0. */
    fun ratio(value: Double?, max: Double?): Double {
        if (value == null || max == null || !value.isFinite() || !max.isFinite() || max <= 0) return 0.0
        return (value / max).coerceIn(0.0, 1.0)
    }

    fun clamped(ratio: Double): Double = ratio.coerceIn(0.0, 1.0)

    /** A `BindableNumber`: a literal, or a binding whose value is a number, else its fallback. A string is not a number. */
    fun number(ref: JsonValue?, scope: DataScope): Double? {
        val record = ref?.obj ?: return ref?.number
        if (record["kind"]?.string == "literal") return record["value"]?.number
        if (record["kind"]?.string != "binding") return null
        val path = record["path"]?.string ?: return null
        return scope[path]?.number ?: record["fallback"]?.number
    }

    /** The value of a scale at this frame: the number the engine shows for a literal one, else the data read through the shown scope. */
    fun shownNumber(ref: JsonValue?, scope: DataScope, literal: Double?): Double? {
        if (ref?.get("kind")?.string == "literal" && literal != null) return literal
        return number(ref, scope)
    }

    // endregion

    // region Ring

    fun ringScaleOf(size: JsonValue?): Double = size?.string?.let(ringScale::get) ?: ringScale.getValue(DEFAULT_RING_SIZE)

    /**
     * What the style named and what it consumes inside. Only fields the reader APPLIES are passed:
     * under a row weight the width fields are not, on a fill layer none are.
     */
    data class RingBox(
        val size: JsonValue? = null,
        val unit: Double = 0.0,
        val width: Double? = null,
        val minWidth: Double? = null,
        val maxWidth: Double? = null,
        val height: Double? = null,
        val minHeight: Double? = null,
        val maxHeight: Double? = null,
        val paddingInline: Double = 0.0,
        val paddingBlock: Double = 0.0,
        val border: Double = 0.0,
        val thickness: Double = 0.0,
    )

    data class RingGeometry(val diameter: Double, val thickness: Double, val boxInline: Double, val boxBlock: Double)

    /** A size bounded by a minimum and a maximum; an inverted range gives the minimum. */
    fun constrained(size: Double, min: Double?, max: Double?): Double {
        var result = size
        if (max != null) result = min(max, result)
        if (min != null) result = max(min, result)
        return result
    }

    private fun namedSide(size: Double?, low: Double?, high: Double?, natural: Double): Double? {
        if (size == null && low == null && high == null) return null
        return constrained(size ?: natural, low, high)
    }

    /**
     * The diameter is the smaller named side of the content box, else the size word; the unnamed
     * side equals it; the thickness is at most half of it (`progress.ring`).
     */
    fun ringGeometry(input: RingBox): RingGeometry {
        val bySize = ringScaleOf(input.size) * input.unit
        val boxInline = namedSide(input.width, input.minWidth, input.maxWidth, bySize + input.paddingInline + 2 * input.border)
        val boxBlock = namedSide(input.height, input.minHeight, input.maxHeight, bySize + input.paddingBlock + 2 * input.border)
        val content = ArrayList<Double>()
        if (boxInline != null) content.add(max(0.0, boxInline - input.paddingInline - 2 * input.border))
        if (boxBlock != null) content.add(max(0.0, boxBlock - input.paddingBlock - 2 * input.border))
        val diameter = content.minOrNull() ?: bySize
        return RingGeometry(
            diameter = diameter,
            thickness = min(max(0.0, input.thickness), diameter / 2),
            boxInline = boxInline ?: (diameter + input.paddingInline + 2 * input.border),
            boxBlock = boxBlock ?: (diameter + input.paddingBlock + 2 * input.border),
        )
    }

    /** The arc starts at the top and runs clockwise; the ring is not mirrored in right-to-left. */
    fun ringArc(ratio: Double): Pair<Double, Double> = 0.0 to 360 * clamped(ratio)

    // endregion

    // region Bar

    /** Half of the shorter side, never more than the theme `pill` radius. */
    fun barFillRadius(fillInline: Double, thickness: Double, pill: Double): Double = max(0.0, min(pill, min(fillInline / 2, thickness / 2)))

    data class BarGeometry(val trackBlock: Double, val fillInline: Double, val trackRadius: Double, val fillRadius: Double)

    fun barGeometry(trackInline: Double, thickness: Double, ratio: Double, pill: Double): BarGeometry {
        val fill = trackInline * clamped(ratio)
        return BarGeometry(thickness, fill, barFillRadius(trackInline, thickness, pill), barFillRadius(fill, thickness, pill))
    }

    /** A gradient lies along the FILLED part: a stop at `position` stands at `position × ratio`. */
    fun gradientStops(stops: List<GradientValue.Stop>, ratio: Double): List<Pair<Rgba, Double>> = stops.map { it.color to it.position * ratio }

    // endregion

    // region Segments

    /** A whole number 2…12; anything else (a string, a fraction, 1, 13) is the continuous bar. */
    fun segmentsCount(value: JsonValue?): Int? {
        return value.wholeIn(segmentsRange)
    }

    /** `floor(round(ratio × segments, 6))`: a step is full or empty. */
    fun filledSegments(ratio: Double, segments: Int): Int {
        val scale = 10.0.pow(SEGMENT_PRECISION)
        val units = floor(clamped(ratio) * segments * scale + 0.5) / scale
        return min(segments, max(0, floor(units).toInt()))
    }

    enum class SegmentState(val letter: String) { Filled("f"), Current("c"), Unfilled("u") }

    data class SegmentBox(val start: Double, val inline: Double, val state: SegmentState)

    data class SegmentGeometry(val boxes: List<SegmentBox>, val filled: Int, val current: Int?, val radius: Double, val filledInline: Double) {
        val states: String get() = boxes.joinToString("") { it.state.letter }
    }

    /** `null`: the steps are not drawn and the bar is continuous (a step narrower than 1). */
    fun segmentGeometry(trackInline: Double, thickness: Double, ratio: Double, segments: Int, gap: Double, pill: Double): SegmentGeometry? {
        val inline = (trackInline - (segments - 1) * gap) / segments
        if (!inline.isFinite() || inline < MIN_SEGMENT_INLINE) return null
        val filled = filledSegments(ratio, segments)
        val current = if (filled < segments) filled else null
        val boxes = (0 until segments).map { index ->
            SegmentBox(index * (inline + gap), inline, if (index < filled) SegmentState.Filled else if (index == current) SegmentState.Current else SegmentState.Unfilled)
        }
        return SegmentGeometry(boxes, filled, current, barFillRadius(inline, thickness, pill), if (filled == 0) 0.0 else filled * inline + (filled - 1) * gap)
    }

    enum class SegmentsKind { Steps, Continuous }

    /** The width the PLACE gives decides the kind, never the bar's own measure. */
    fun segmentsKind(placeInline: Double, segments: Int, gap: Double): SegmentsKind {
        val inline = (placeInline - (segments - 1) * gap) / segments
        return if (inline.isFinite() && inline >= MIN_SEGMENT_INLINE) SegmentsKind.Steps else SegmentsKind.Continuous
    }

    /** The own minimum width is the gaps, in both kinds. */
    fun segmentsMinInline(segments: Int, gap: Double): Double = max(0.0, (segments - 1) * gap)

    // endregion

    // region Steps during a transition

    /** `U(from) + (U(to) − U(from)) × e`, `U` the whole steps at rest; both ends are pictures at rest. */
    fun segmentUnits(fromRatio: Double, toRatio: Double, eased: Double, segments: Int): Double {
        val from = filledSegments(fromRatio, segments).toDouble()
        val to = filledSegments(toRatio, segments).toDouble()
        return from + (to - from) * eased.coerceIn(0.0, 1.0)
    }

    fun segmentFills(units: Double, segments: Int): List<Double> = (0 until segments).map { (units - it).coerceIn(0.0, 1.0) }

    /** The first step that is not full; `null` when every step is. */
    fun segmentCurrent(fills: List<Double>): Int? = fills.indexOfFirst { it < 1 }.takeIf { it >= 0 }

    /** The length of the gradient line along the drawn fill. */
    fun segmentFillInline(units: Double, step: Double, gap: Double): Double {
        if (units <= 0) return 0.0
        return units * step + (Math.ceil(units) - 1) * gap
    }

    /** The eased share recovered from the shown value of the scale. */
    fun transitionShare(from: Double, to: Double, shown: Double): Double {
        if (from == to || !from.isFinite() || !to.isFinite() || !shown.isFinite()) return 1.0
        return ((shown - from) / (to - from)).coerceIn(0.0, 1.0)
    }

    // endregion

    // region Round ends

    enum class RingCapKind(val word: String) { None("none"), Dot("dot"), Arc("arc"), Full("full") }

    data class RingCap(val kind: RingCapKind, val capAngle: Double, val startDeg: Double, val endDeg: Double)

    /** Half-circle ends whose centres stand θc = thickness / (diameter − thickness) inside the filled part from each edge. */
    fun ringCap(ratio: Double, diameter: Double, thickness: Double): RingCap {
        val share = clamped(ratio)
        val stroke = min(thickness, diameter / 2)
        val capAngle = if (diameter - stroke > 0) stroke / (diameter - stroke) else Math.PI
        val capDeg = capAngle * 180 / Math.PI
        if (share <= 0 || stroke <= 0) return RingCap(RingCapKind.None, capAngle, 0.0, 0.0)
        if (share >= 1) return RingCap(RingCapKind.Full, capAngle, 0.0, 360.0)
        val sweep = 360 * share
        if (sweep <= 2 * capDeg) return RingCap(RingCapKind.Dot, capAngle, capDeg, capDeg)
        return RingCap(RingCapKind.Arc, capAngle, capDeg, sweep - capDeg)
    }

    /** `max(thickness, ratio × track)`, at most the track; an empty scale draws nothing. */
    fun barCapFillInline(trackInline: Double, thickness: Double, ratio: Double): Double {
        val share = clamped(ratio)
        if (share <= 0) return 0.0
        return min(trackInline, max(thickness, trackInline * share))
    }

    fun barCapRadius(thickness: Double): Double = max(0.0, thickness / 2)

    // endregion

    // region Glow

    enum class Glow(val word: String, val sigma: Double, val opacity: Double) {
        Soft("soft", 4.0, 0.55), Strong("strong", 8.0, 0.9);

        companion object {
            fun of(value: JsonValue?): Glow? = value?.string?.let { word -> entries.firstOrNull { it.word == word } }
        }
    }

    sealed class GlowingStep {
        object None : GlowingStep()
        data class Current(val index: Int) : GlowingStep()
        data class Front(val index: Int, val fill: Double) : GlowingStep()
    }

    /** One step glows: the current one when `currentColor` paints it, else the last with any fill. */
    fun glowingStep(fills: List<Double>, currentPainted: Boolean): GlowingStep {
        val current = fills.indexOfFirst { it < 1 }.takeIf { it >= 0 }
        if (currentPainted && current != null) return GlowingStep.Current(current)
        val front = fills.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: return GlowingStep.None
        return GlowingStep.Front(front, fills[front])
    }

    // endregion

    // region Paint

    /** The paint of a scale resolved against the theme: the thickness, the track under the WHOLE scale and the fill over it. */
    sealed class Fill {
        data class Solid(val color: Rgba) : Fill()
        data class Gradient(val stops: List<GradientValue.Stop>) : Fill()
    }

    data class Paint(val thickness: Double, val track: Rgba, val fill: Fill)

    /** The label of a ring: the style it is drawn with (`progressLabel`). */
    data class LabelStyle(val token: String, val typography: TextStyleValue, val color: Rgba)

    /** A spacing token or a whole number; a fraction, an unknown token or another shape gives `null` and the default applies. */
    fun thickness(ref: JsonValue?, resolver: StyleResolver): Double? = when (ref) {
        is JsonValue.Num -> ref.number?.takeIf { it == Math.rint(it) }?.let { resolver.spacing(ref) }
        is JsonValue.Str -> resolver.spacing(ref)
        else -> null
    }

    fun paint(props: JsonObject, resolver: StyleResolver): Paint {
        val thickness = thickness(props["thickness"], resolver) ?: resolver.spacing(JsonValue.Str(DEFAULT_THICKNESS)) ?: 0.0
        val track = resolver.color(props["trackColor"]) ?: resolver.color(JsonValue.Str(DEFAULT_TRACK_COLOR)) ?: Rgba(0.93, 0.94, 0.96)
        resolver.gradient(props["gradient"])?.let { return Paint(thickness, track, Fill.Gradient(it.stops)) }
        val color = resolver.color(props["color"]) ?: resolver.color(JsonValue.Str(DEFAULT_COLOR)) ?: Rgba(0.31, 0.27, 0.9)
        return Paint(thickness, track, Fill.Solid(color))
    }

    /** The colour of the current step: none when absent or unresolved, so it looks unfilled. */
    fun currentColor(props: JsonObject, resolver: StyleResolver): Rgba? = resolver.color(props["currentColor"])

    /**
     * `labelTypography` / `labelColor` where they resolve, `caption` / `onSurface` otherwise. A token
     * the theme does not know gives the default style WHOLE; the label inherits nothing.
     */
    fun labelStyle(props: JsonObject, resolver: StyleResolver): LabelStyle {
        val theme = resolver.theme
        val ref = props["labelTypography"]
        var token = DEFAULT_LABEL_TYPOGRAPHY
        var typography = theme.typography[DEFAULT_LABEL_TYPOGRAPHY] ?: TextStyleValue(10.0, 1.2, 500)
        resolver.typography(ref)?.let {
            typography = it
            token = ref?.string ?: ref?.get("token")?.string ?: DEFAULT_LABEL_TYPOGRAPHY
        }
        val color = resolver.color(props["labelColor"]) ?: theme.colorValue(DEFAULT_LABEL_COLOR) ?: Rgba(0.07, 0.09, 0.15)
        return LabelStyle(token, typography, color)
    }

    /**
     * `percent` is the SHOWN share; `valueOfMax` writes the numbers it is given, in one string with
     * its spaces. `none` (and a word not known) draws nothing.
     */
    fun labelText(format: String?, value: Double, max: Double, ratio: Double): String? = when (format) {
        "percent" -> "${floor(ratio * 100 + 0.5).toInt()}%"
        "valueOfMax" -> "${signed(value)} / ${signed(max)}"
        else -> null
    }

    /** A number as JavaScript writes it: shortest digits, no exponent, a minus sign. */
    fun signed(value: Double): String = (if (value < 0) "-" else "") + DecimalText.plain(value)

    /**
     * The colour of a gradient along a filled part at `share` (0…1 of the fill): before the first
     * stop the first colour, after the last the last; between them a straight blend.
     */
    fun gradientColor(stops: List<GradientValue.Stop>, share: Double): Rgba {
        val first = stops.firstOrNull() ?: return Rgba(0.0, 0.0, 0.0, 0.0)
        val last = stops.last()
        if (share <= first.position) return first.color
        if (share >= last.position) return last.color
        for ((low, high) in stops.zipWithNext()) {
            if (share < low.position || share > high.position) continue
            val span = high.position - low.position
            val weight = if (span > 0) (share - low.position) / span else 1.0
            return Rgba(
                low.color.red + (high.color.red - low.color.red) * weight,
                low.color.green + (high.color.green - low.color.green) * weight,
                low.color.blue + (high.color.blue - low.color.blue) * weight,
                low.color.alpha + (high.color.alpha - low.color.alpha) * weight,
            )
        }
        return last.color
    }

    // endregion
}
