package com.hotlayout.render.widgets

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.data.RecordScope
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * `Chart`: how a record becomes a point, the scale, the highlighted point, the bars, the line, the monotone
 * curve and the label boxes. Pure numbers, no views.
 *
 * Units are logical pixels in the content box of the node. `width` is the width the parent gave (the chart fills
 * it); `height` is the height of the PLOT AREA, the content box without the band of labels. The origin is the
 * top-left corner and y grows downwards.
 */
internal object ChartRules {
    /** At least two points make a chart, and at most 31 are read. */
    const val POINTS_MIN = 2
    const val POINTS_MAX = 31

    /** The height of a chart without `style.height`. */
    const val DEFAULT_HEIGHT = 120.0
    val LINE_WIDTH_RANGE = 1.0..8.0
    const val DEFAULT_LINE_WIDTH = 2.0

    /** The opacity of the area under the line at the top of the plot area. */
    const val AREA_TOP_OPACITY = 0.24

    /** The dot of a highlighted point is the stroke plus this. */
    const val DOT_EXTRA = 2.0

    /** A label slot narrower than this thins the labels out. */
    const val LABEL_MIN_WIDTH = 28.0

    /** The tokens of the fields that name none. */
    const val DEFAULT_COLOR = "primary"
    const val DEFAULT_HIGHLIGHT_COLOR = "accent"
    const val DEFAULT_BAR_RADIUS = "sm"
    const val DEFAULT_BAR_GAP = "xs"
    const val LABEL_TYPOGRAPHY = "caption"
    const val LABEL_COLOR = "muted"
    const val LABEL_GAP = "xs"

    data class Point(val value: Double, val label: String)

    /** The value of a point: a finite number is itself, a negative one is zero, anything else is zero (the point keeps its place). */
    fun value(raw: JsonValue?): Double {
        val number = raw?.number ?: return 0.0
        return if (number.isFinite()) max(0.0, number) else 0.0
    }

    /** The label of a point: a string is itself, anything else is an empty label. */
    fun label(raw: JsonValue?): String = raw?.string ?: ""

    /**
     * The points of the `data` field. A collection gives the first 31 records in the order of the data, and a record that
     * is not an object is a record without fields. Fewer than two points, or a shape of `data` this reader does not
     * know, is an EMPTY chart: nothing is drawn and the box stays.
     */
    fun points(data: JsonValue?, collection: (String) -> List<JsonValue>?): List<Point> {
        val kept = rawPoints(data, collection).take(POINTS_MAX)
        return if (kept.size < POINTS_MIN) emptyList() else kept
    }

    private fun rawPoints(data: JsonValue?, collection: (String) -> List<JsonValue>?): List<Point> {
        if (data?.obj == null) return emptyList()
        when (data["kind"]?.string) {
            "literal" -> {
                val points = data["points"]?.array ?: return emptyList()
                return points.take(POINTS_MAX).map { point ->
                    if (point.obj == null) Point(0.0, "") else Point(value(point["value"]), label(point["label"]))
                }
            }
            "collection" -> {
                val source = data["source"]?.string ?: return emptyList()
                val item = data["item"]?.string ?: return emptyList()
                val valueField = data["valueField"]?.string ?: return emptyList()
                val labelField = data["labelField"]?.string
                return (collection(source) ?: emptyList()).take(POINTS_MAX).map { record ->
                    val scope = RecordScope.instanceRecord(item, record.obj ?: JsonObject())
                    Point(value(scope[valueField]), labelField?.let { label(scope[it]) } ?: "")
                }
            }
            else -> return emptyList()
        }
    }

    /**
     * The shares of the height: each value divided by the largest value. All values zero gives all shares zero (an
     * empty chart without a division by zero). [shown] is the share of the appearance (`animateIn`, 0 to 1): the top
     * of the scale stands at once and the values grow.
     */
    fun ratios(values: List<Double>, shown: Double = 1.0): List<Double> {
        val top = values.maxOrNull() ?: 0.0
        return values.map { if (top > 0) it / top * shown else 0.0 }
    }

    /**
     * The highlighted point: `last` is the last one; `max` is the LAST of the largest values (the fresher of equals),
     * and only when it is above zero; `none` and any other word give none.
     */
    fun highlightIndex(values: List<Double>, highlight: String?): Int? {
        if (values.isEmpty()) return null
        if (highlight == "last") return values.size - 1
        if (highlight != "max") return null
        val top = values.max()
        return if (top > 0) values.lastIndexOf(top) else null
    }

    /** A bar: its box and the radius of its TOP corners (the bottom corners are square, a bar stands on zero). */
    data class Bar(val x: Double, val y: Double, val width: Double, val height: Double, val radius: Double, val highlighted: Boolean)

    /**
     * The bars: n equal slots over the whole width with [gap] between them only. A gap that would make a bar narrower
     * than one point becomes zero. The height is the share of [height]; a bar of height zero is not drawn. The radius of
     * the top corners is no more than half the width and no more than the height of the bar.
     */
    fun bars(ratios: List<Double>, width: Double, height: Double, gap: Double, radius: Double, highlight: Int?): List<Bar> {
        val count = ratios.size
        if (count == 0 || width <= 0) return emptyList()
        val wanted = max(0.0, gap)
        val usedGap = if ((width - wanted * (count - 1)) / count < 1) 0.0 else wanted
        val barWidth = (width - usedGap * (count - 1)) / count
        val plot = max(0.0, height)
        return ratios.mapIndexed { index, ratio ->
            val barHeight = max(0.0, ratio) * plot
            Bar(
                index * (barWidth + usedGap), plot - barHeight, barWidth, barHeight,
                max(0.0, min(radius, min(barWidth / 2, barHeight))), index == highlight,
            )
        }
    }

    data class XY(val x: Double, val y: Double)

    data class Dot(val x: Double, val y: Double, val radius: Double)

    /**
     * The line: the points, the [inset] from the edges (so that the stroke and the dot are not cut by the box), the
     * dot of the highlighted point (`null` when nothing is highlighted) and the bottom of the area under it.
     */
    data class Line(val points: List<XY>, val inset: Double, val dot: Dot?, val baseline: Double)

    /**
     * The line. The inset is half the stroke, or with a highlight the radius of the dot (the stroke plus [DOT_EXTRA])
     * when that is larger. The points run from edge to edge: `x_k = i + k (W - 2i) / (n - 1)` and
     * `y_k = i + (1 - share_k) (H - 2i)`; zero lies at `H - i` and the top at `i`.
     */
    fun line(ratios: List<Double>, width: Double, height: Double, lineWidth: Double, highlight: Int?): Line {
        val count = ratios.size
        val plot = max(0.0, height)
        val dotRadius = lineWidth + DOT_EXTRA
        val inset = if (highlight == null) lineWidth / 2 else max(lineWidth / 2, dotRadius)
        if (count < POINTS_MIN || width <= 0) return Line(emptyList(), inset, null, plot)
        val step = (width - 2 * inset) / (count - 1)
        val span = max(0.0, plot - 2 * inset)
        val points = ratios.mapIndexed { index, ratio -> XY(inset + index * step, inset + (1 - max(0.0, ratio)) * span) }
        val lit = highlight?.let { points.getOrNull(it) }
        return Line(points, inset, lit?.let { Dot(it.x, it.y, dotRadius) }, plot)
    }

    private fun sign(value: Double): Double = if (value > 0) 1.0 else if (value < 0) -1.0 else 0.0

    /**
     * The tangents of the monotone cubic spline (Steffen: the same as `curveMonotoneX` of d3). The step h is equal; the
     * secant is `s_k = (y_{k+1} - y_k) / h`. Inside: `m_k = (sign s_{k-1} + sign s_k) min(|s_{k-1}|, |s_k|,
     * |s_{k-1} + s_k| / 4)`. At the ends: `m_0 = (3 s_0 - m_1) / 2` and `m_{n-1} = (3 s_{n-2} - m_{n-2}) / 2`. Secants of
     * opposite signs give a zero tangent, so a peak lies exactly on its point.
     */
    fun tangents(points: List<XY>): List<Double> {
        val count = points.size
        if (count < 3) return List(count) { 0.0 }
        val step = points[1].x - points[0].x
        val secants = (0 until count - 1).map { if (step == 0.0) 0.0 else (points[it + 1].y - points[it].y) / step }
        val result = DoubleArray(count)
        for (index in 1 until count - 1) {
            val before = secants[index - 1]
            val after = secants[index]
            result[index] = (sign(before) + sign(after)) * min(abs(before), min(abs(after), abs(before + after) / 4))
        }
        result[0] = (3 * secants[0] - result[1]) / 2
        result[count - 1] = (3 * secants[count - 2] - result[count - 2]) / 2
        return result.toList()
    }

    /** A cubic Bezier segment of the smooth curve: two control points and the end. */
    data class Cubic(val c1: XY, val c2: XY, val to: XY)

    /**
     * The smooth curve: a cubic Bezier between neighbours, `c1 = (x_k + h/3, y_k + m_k h/3)` and
     * `c2 = (x_{k+1} - h/3, y_{k+1} - m_{k+1} h/3)`. A monotone spline never leaves the values of its neighbours, so the
     * curve does not dive below zero or rise above the top. Two points are a straight segment.
     */
    fun smoothSegments(points: List<XY>): List<Cubic> {
        if (points.size < 3) return (1 until points.size).map { Cubic(points[it - 1], points[it], points[it]) }
        val slopes = tangents(points)
        return (1 until points.size).map { index ->
            val from = points[index - 1]
            val to = points[index]
            val third = (to.x - from.x) / 3
            Cubic(XY(from.x + third, from.y + slopes[index - 1] * third), XY(to.x - third, to.y - slopes[index] * third), to)
        }
    }

    /** The box of one label: the index of its point, where it starts and how wide it is. */
    data class LabelBox(val index: Int, val left: Double, val width: Double)

    /**
     * The label boxes. Each is `W / n` wide, centred under the middle of the bar or under the point of the line, and
     * shifted inside `[0, W]`. When `W / n` is narrower than [LABEL_MIN_WIDTH], every k-th point is labelled,
     * `k = ceil(min / (W / n))`, counting from the LAST point (it is "today").
     */
    fun labelBoxes(centers: List<Double>, width: Double): List<LabelBox> {
        val count = centers.size
        if (count == 0 || width <= 0) return emptyList()
        val slot = width / count
        val every = max(1, ceil(LABEL_MIN_WIDTH / slot - 1e-9).toInt())
        return centers.indices.mapNotNull { index ->
            if ((count - 1 - index) % every != 0) null
            else LabelBox(index, min(max(centers[index] - slot / 2, 0.0), width - slot), slot)
        }
    }

    /** The centres of the points for the labels: the middle of a bar, or the point of the line. */
    fun labelCenters(bars: List<Bar>? = null, line: Line? = null): List<Double> =
        bars?.map { it.x + it.width / 2 } ?: line?.points?.map { it.x }.orEmpty()

    /** The height of the plot area: the content box without the band of labels (a line of label text plus the gap), never below zero. */
    fun plotHeight(height: Double, labels: Boolean, labelLine: Double, labelGap: Double): Double =
        max(0.0, height - if (labels) labelLine + labelGap else 0.0)

    /** The word of `highlight` as the reader reads it: an unknown word is `none`. */
    fun readHighlight(value: JsonValue?): String = value?.string?.takeIf { it == "last" || it == "max" } ?: "none"

    /** Whether the curve is smooth: any other word is a straight line. */
    fun readSmooth(value: JsonValue?): Boolean = value?.string == "smooth"

    /** The stroke of the line within its range; anything that is not a finite number is the default. */
    fun readLineWidth(value: JsonValue?): Double {
        val number = value?.number ?: return DEFAULT_LINE_WIDTH
        if (!number.isFinite()) return DEFAULT_LINE_WIDTH
        return min(LINE_WIDTH_RANGE.endInclusive, max(LINE_WIDTH_RANGE.start, number))
    }
}
