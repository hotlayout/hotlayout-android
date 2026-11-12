package com.hotlayout.render.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.composeTextStyle
import com.hotlayout.render.dp
import com.hotlayout.render.motion.LocalValueTransitionElapsedMs
import com.hotlayout.render.motion.ValueTransition
import com.hotlayout.render.motion.rememberReducedMotion
import com.hotlayout.text.ShownText
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue
import kotlin.math.max
import kotlin.math.min

/**
 * `Chart`: bars or a line over a row of points, with the labels in a band under the plot area. The numbers come from
 * [ChartRules]; this turns the node into theme values (colours, radius, gap, label font) and draws them in the box the
 * parent gave.
 *
 * It is ONE picture for assistive technology, named by `alt`: the drawing and the labels are not read, and a chart
 * without a name is hidden as a decorative picture. The points are not read out as a table.
 */
@Composable
internal fun ChartWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val props = node.props
    val theme = context.theme
    val resolver = context.resolver
    val look = resolver.box(call.style, context.scope)
    val labelStyle = theme.typography[ChartRules.LABEL_TYPOGRAPHY] ?: TextStyleValue(12.0, 1.33, 400)
    val spec = ChartSpec(
        points = ChartRules.points(props["data"]) { context.scope[it]?.array },
        line = props["variant"]?.string == "line",
        smooth = ChartRules.readSmooth(props["curve"]),
        area = props["area"]?.bool == true,
        labels = props["labels"]?.bool == true,
        highlight = ChartRules.readHighlight(props["highlight"]),
        lineWidth = ChartRules.readLineWidth(props["lineWidth"]),
        color = resolver.color(props["color"]) ?: theme.colorValue(ChartRules.DEFAULT_COLOR) ?: Rgba(0.0, 0.0, 0.0),
        highlightColor = resolver.color(props["highlightColor"]) ?: theme.colorValue(ChartRules.DEFAULT_HIGHLIGHT_COLOR) ?: Rgba(0.0, 0.0, 0.0),
        barRadius = resolver.radius(props["barRadius"]) ?: theme.radius[ChartRules.DEFAULT_BAR_RADIUS] ?: 0.0,
        barGap = resolver.spacing(props["barGap"]) ?: theme.spacing[ChartRules.DEFAULT_BAR_GAP] ?: 0.0,
        labelStyle = labelStyle,
        labelColor = theme.colorValue(ChartRules.LABEL_COLOR) ?: Rgba(0.42, 0.45, 0.5),
        labelGap = theme.spacing[ChartRules.LABEL_GAP] ?: 4.0,
    )
    val alt = props["alt"]?.let { raw -> raw.string?.let(ShownText::of) ?: context.text(node, "alt") } ?: ""
    val semantics = Modifier.clearAndSetSemantics {
        if (alt.isNotEmpty()) {
            contentDescription = alt
            role = Role.Image
        }
    }
    // The node box fixes a named height and the chart takes all of it; without one the chart is 120 high.
    val size = if (look.height != null) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth().height(ChartRules.DEFAULT_HEIGHT.dp)
    ChartAppear(props["animateIn"]?.bool == true && spec.points.isNotEmpty()) { shown ->
        BoxWithConstraints(size.then(semantics).clipToBounds()) {
            ChartDrawing(spec, context.rightToLeft, call, maxWidth.value.toDouble(), maxHeight.value.toDouble(), shown)
        }
    }
}

/** Everything the drawing needs, resolved from the node and the theme. */
internal class ChartSpec(
    val points: List<ChartRules.Point>,
    val line: Boolean,
    val smooth: Boolean,
    val area: Boolean,
    val labels: Boolean,
    val highlight: String,
    val lineWidth: Double,
    val color: Rgba,
    val highlightColor: Rgba,
    val barRadius: Double,
    val barGap: Double,
    val labelStyle: TextStyleValue,
    val labelColor: Rgba,
    val labelGap: Double,
)

/** The numbers of one frame of a chart: the bars or the line and the label boxes. */
internal class ChartShape(val spec: ChartSpec, val width: Double, val plot: Double, val shown: Double) {
    private val values = spec.points.map { it.value }
    private val highlight = ChartRules.highlightIndex(values, spec.highlight)
    private val ratios = ChartRules.ratios(values, shown)

    val bars: List<ChartRules.Bar>? =
        if (spec.points.isEmpty() || spec.line) null else ChartRules.bars(ratios, width, plot, spec.barGap, spec.barRadius, highlight)
    val line: ChartRules.Line? =
        if (spec.points.isEmpty() || !spec.line) null else ChartRules.line(ratios, width, plot, spec.lineWidth, highlight)
    val labelBoxes: List<ChartRules.LabelBox> =
        if (!spec.labels || spec.points.isEmpty()) emptyList() else ChartRules.labelBoxes(ChartRules.labelCenters(bars, line), width)
}

@Composable
private fun ChartDrawing(spec: ChartSpec, rightToLeft: Boolean, call: WidgetCall, width: Double, height: Double, shown: Double) {
    val labelLine = if (spec.labels) spec.labelStyle.lineBox else 0.0
    val plot = ChartRules.plotHeight(height, spec.labels, labelLine, spec.labelGap)
    val shape = ChartShape(spec, width, plot, shown)
    Canvas(Modifier.size(width.dp, plot.dp)) { draw(shape, rightToLeft) }
    if (spec.labels) {
        val style = composeTextStyle(spec.labelStyle, spec.labelColor, call.context, align = TextAlign.Center)
        val top = min(plot + spec.labelGap, height)
        for (box in shape.labelBoxes) {
            // Right-to-left writing mirrors the labels with the drawing.
            val left = if (rightToLeft) width - box.left - box.width else box.left
            Box(Modifier.offset(left.dp, top.dp).size(box.width.dp, labelLine.dp).clipToBounds()) {
                BasicText(AnnotatedString(spec.points[box.index].label), Modifier.fillMaxWidth(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun DrawScope.draw(shape: ChartShape, rightToLeft: Boolean) {
    val density = this.density
    // Right-to-left writing mirrors the whole drawing: the first point stands at the start of the reading direction.
    val mirror = if (rightToLeft) -1f else 1f
    scale(mirror, 1f, pivot = Offset(size.width / 2, 0f)) {
        scale(density, density, pivot = Offset.Zero) {
            shape.bars?.let { drawBars(shape.spec, it) }
            shape.line?.takeIf { it.points.isNotEmpty() }?.let { drawLine(shape.spec, it) }
        }
    }
}

private fun DrawScope.drawBars(spec: ChartSpec, bars: List<ChartRules.Bar>) {
    for (bar in bars) {
        if (bar.height <= 0) continue
        val top = CornerRadius(bar.radius.toFloat())
        val rect = RoundRect(
            bar.x.toFloat(), bar.y.toFloat(), (bar.x + bar.width).toFloat(), (bar.y + bar.height).toFloat(),
            topLeftCornerRadius = top, topRightCornerRadius = top, bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
        )
        drawPath(Path().apply { addRoundRect(rect) }, (if (bar.highlighted) spec.highlightColor else spec.color).color)
    }
}

/** The line through the points: segments, or one cubic Bezier per pair for the smooth curve (never a quadratic one or a Catmull-Rom spline: those overshoot the values). */
private fun linePath(spec: ChartSpec, points: List<ChartRules.XY>): Path = Path().apply {
    moveTo(points[0].x.toFloat(), points[0].y.toFloat())
    if (spec.smooth && points.size >= 3) {
        for (segment in ChartRules.smoothSegments(points)) {
            cubicTo(
                segment.c1.x.toFloat(), segment.c1.y.toFloat(), segment.c2.x.toFloat(), segment.c2.y.toFloat(),
                segment.to.x.toFloat(), segment.to.y.toFloat(),
            )
        }
    } else {
        for (point in points.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
    }
}

private fun DrawScope.drawLine(spec: ChartSpec, line: ChartRules.Line) {
    val path = linePath(spec, line.points)
    val first = line.points.first()
    val last = line.points.last()
    if (spec.area) {
        val fill = Path().apply {
            addPath(path)
            lineTo(last.x.toFloat(), line.baseline.toFloat())
            lineTo(first.x.toFloat(), line.baseline.toFloat())
            close()
        }
        // From the colour of the line at 0.24 of its opacity at the top of the plot area to the same colour at zero opacity at the
        // bottom, so the middle of the fade is not tinted grey.
        val brush = Brush.verticalGradient(
            listOf(spec.color.withOpacity(ChartRules.AREA_TOP_OPACITY).color, spec.color.withOpacity(0.0).color),
            startY = 0f, endY = line.baseline.toFloat(),
        )
        drawPath(fill, brush)
    }
    drawPath(path, spec.color.color, style = Stroke(spec.lineWidth.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    line.dot?.let { dot -> drawCircle(spec.highlightColor.color, dot.radius.toFloat(), Offset(dot.x.toFloat(), dot.y.toFloat())) }
}

/**
 * The appearance (`animateIn`): the values grow from zero when the chart is shown, with the curve and the time of a number
 * that leads; the top of the scale stands at once. "Reduce motion" gives the final chart at once, and so does a change of
 * data under a chart that is already shown (the growth is a function of time alone).
 */
@Composable
private fun ChartAppear(animates: Boolean, content: @Composable (Double) -> Unit) {
    val reduced = rememberReducedMotion()
    val fixed = LocalValueTransitionElapsedMs.current
    if (!animates || reduced) {
        content(1.0)
        return
    }
    if (fixed != null) {
        content(ValueTransition.ease(fixed / ValueTransition.DURATION_MS))
        return
    }
    var elapsed by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (elapsed < ValueTransition.DURATION_MS) {
            withFrameMillis { millis ->
                if (start < 0) start = millis
                elapsed = max(0.0, (millis - start).toDouble())
            }
        }
    }
    content(ValueTransition.ease(elapsed / ValueTransition.DURATION_MS))
}
