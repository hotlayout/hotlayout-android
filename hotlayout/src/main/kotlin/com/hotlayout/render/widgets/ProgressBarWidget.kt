package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.composeTextStyle
import com.hotlayout.render.motion.ValueTransition
import com.hotlayout.render.motion.ValueTransitionEngine
import com.hotlayout.render.motion.ValueTransitioned
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue

/**
 * `ProgressBar`: a track under the whole scale, the fill over it, optionally cut into steps, with
 * round ends, a glow and a label at the end of the row.
 */
@Composable
internal fun ProgressBarWidget(call: WidgetCall) {
    ValueTransitioned(call.node, call.context) { shown -> ProgressBarContent(call, shown) }
}

@Composable
private fun ProgressBarContent(call: WidgetCall, shown: ValueTransitionEngine.Shown) {
    val node = call.node
    val context = call.context
    val props = node.props
    val resolver = context.resolver
    val theme = context.theme
    val value = ProgressRules.shownNumber(props["value"], shown.smooth, shown.literal?.second) ?: 0.0
    val max = ProgressRules.number(props["max"], shown.smooth) ?: 100.0
    val steppedValue = ProgressRules.shownNumber(props["value"], shown.stepped, shown.literal?.first) ?: 0.0
    val steppedMax = ProgressRules.number(props["max"], shown.stepped) ?: 100.0
    val ratio = ProgressRules.ratio(value, max)
    val paint = ProgressRules.paint(props, resolver)
    val segments = ProgressRules.segmentsCount(props["segments"])
    val valueKey = if (props["value"]?.get("kind")?.string == "literal") ValueTransition.LITERAL_KEY else props["value"]?.get("path")?.string
    val units = segments?.let { count ->
        val ends = valueKey?.let { shown.ends[it] } ?: return@let null
        ProgressRules.segmentUnits(
            ProgressRules.ratio(ends.from, max), ProgressRules.ratio(ends.to, max),
            ProgressRules.transitionShare(ends.from, ends.to, ends.shown), count,
        )
    }
    val gap = theme.spacing[ProgressRules.SEGMENT_GAP] ?: 0.0
    val scale = BarScale(
        paint = paint, ratio = ratio, rightToLeft = context.rightToLeft, pill = theme.radius["pill"] ?: 72.0,
        roundEnds = props["cap"]?.string == "round", segments = segments, gap = gap,
        currentColor = ProgressRules.currentColor(props, resolver), units = units, glow = ProgressRules.Glow.of(props["glow"]),
    )
    val label = ProgressRules.labelText(props["labelFormat"]?.string ?: "valueOfMax", steppedValue, steppedMax, ratio)
    val labelGap = theme.spacing[ProgressRules.BAR_LABEL_GAP] ?: 4.0
    val atRest = ProgressRules.ratio(ProgressRules.number(props["value"], context.scope), ProgressRules.number(props["max"], context.scope) ?: 100.0)
    val name = context.text(node, "label")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(labelGap.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .then(segments?.let { Modifier.widthIn(min = ProgressRules.segmentsMinInline(it, gap).dp) } ?: Modifier)
                .height(paint.thickness.dp)
                .semantics {
                    if (name.isNotEmpty()) contentDescription = name
                    progressBarRangeInfo = ProgressBarRangeInfo(atRest.toFloat(), 0f..1f)
                }
                .drawBehind { scale.draw(this) },
        )
        if (label != null) {
            val style = theme.typography[ProgressRules.DEFAULT_LABEL_TYPOGRAPHY] ?: TextStyleValue(10.0, 1.2, 500)
            val color = theme.colorValue(ProgressRules.BAR_LABEL_COLOR) ?: Rgba(0.42, 0.45, 0.5)
            BasicText(
                text = AnnotatedString(label),
                modifier = Modifier.align(Alignment.End),
                style = composeTextStyle(style, color, context).copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/** The scale of a bar drawn in one pass over a canvas: continuous or in steps, the glow copy under it. */
internal class BarScale(
    val paint: ProgressRules.Paint,
    val ratio: Double,
    val rightToLeft: Boolean,
    val pill: Double,
    val roundEnds: Boolean,
    val segments: Int?,
    val gap: Double,
    val currentColor: Rgba?,
    val units: Double?,
    val glow: ProgressRules.Glow?,
) {
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        if (glow != null) pass(scope, ProgressInk(glow, scope.density))
        pass(scope, ProgressInk(null, scope.density))
    }

    private fun pass(scope: androidx.compose.ui.graphics.drawscope.DrawScope, ink: ProgressInk) {
        val density = scope.density
        val width = scope.size.width / density.toDouble()
        val height = scope.size.height / density.toDouble()
        val thickness = paint.thickness
        if (thickness <= 0 || width <= 0) return
        scope.withCanvas { canvas ->
            val bar = Drawing(canvas, ink, density, width, height)
            val geometry = segments?.let { ProgressRules.segmentGeometry(width, thickness, ratio, it, gap, pill) }
            if (geometry != null) bar.steps(geometry) else bar.continuous()
        }
    }

    private inner class Drawing(val canvas: androidx.compose.ui.graphics.Canvas, val ink: ProgressInk, val density: Float, val width: Double, val height: Double) {
        /** A logical offset from the start of the row, as an x of the canvas: mirrored in right-to-left. */
        fun x(offset: Double): Double = if (rightToLeft) width - offset else offset

        fun rect(start: Double, length: Double): Rect {
            val origin = if (rightToLeft) width - start - length else start
            val k = density
            return Rect((origin * k).toFloat(), 0f, ((origin + maxOf(0.0, length)) * k).toFloat(), (height * k).toFloat())
        }

        fun fillPaint(line: Double, size: androidx.compose.ui.geometry.Size) = when (val fill = paint.fill) {
            is ProgressRules.Fill.Solid -> ink.solid(fill.color)
            is ProgressRules.Fill.Gradient -> ink.brush(linearBrush(fill.stops, Offset((x(0.0) * density).toFloat(), 0f), Offset((x(line) * density).toFloat(), 0f)), size)
        }

        fun continuous() {
            val geometry = ProgressRules.barGeometry(width, paint.thickness, ratio, pill)
            val trackRadius = if (roundEnds) ProgressRules.barCapRadius(paint.thickness) else geometry.trackRadius
            val trackRect = rect(0.0, width)
            val track = roundedPath(trackRect, (trackRadius * density).toFloat())
            if (!ink.isGlow) canvas.drawPath(track, ink.solid(paint.track))
            val fillInline = if (roundEnds) ProgressRules.barCapFillInline(width, paint.thickness, ratio) else geometry.fillInline
            if (fillInline <= 0) return
            val fillRadius = if (roundEnds) trackRadius else geometry.fillRadius
            val fillRect = rect(0.0, fillInline)
            val shape = roundedPath(fillRect, (fillRadius * density).toFloat())
            // The fill is clipped by the track; its glow copy is not.
            val drawn = if (ink.isGlow) shape else intersect(shape, trackRect)
            canvas.drawPath(drawn, fillPaint(fillInline, trackRect.size))
        }

        fun steps(geometry: ProgressRules.SegmentGeometry) {
            val count = geometry.boxes.size
            val radius = if (roundEnds) ProgressRules.barCapRadius(paint.thickness) else geometry.radius
            val step = geometry.boxes.firstOrNull()?.inline ?: 0.0
            val drawnUnits = units ?: geometry.filled.toDouble()
            val fills = ProgressRules.segmentFills(drawnUnits, count)
            val current = ProgressRules.segmentCurrent(fills)
            val line = ProgressRules.segmentFillInline(drawnUnits, step, gap)
            val lit = ProgressRules.glowingStep(fills, currentColor != null)
            val whole = rect(0.0, width)
            for ((index, box) in geometry.boxes.withIndex()) {
                val frame = rect(box.start, box.inline)
                val shape = roundedPath(frame, (radius * density).toFloat())
                val fill = fills[index]
                if (ink.isGlow) {
                    when {
                        lit is ProgressRules.GlowingStep.Current && lit.index == index -> currentColor?.let { canvas.drawPath(shape, ink.solid(it)) }
                        lit is ProgressRules.GlowingStep.Front && lit.index == index -> cut(shape, frame, lit.fill, box, line, whole)
                    }
                    continue
                }
                val base = if (fill >= 1) paint.track else if (index == current) (currentColor ?: paint.track) else paint.track
                if (fill < 1) canvas.drawPath(shape, ink.solid(base))
                if (fill > 0) cut(shape, frame, fill, box, line, whole)
            }
        }

        /** The fill of a step cut at [share] of its length from the logical start, the step's rounded shape kept. */
        fun cut(shape: androidx.compose.ui.graphics.Path, frame: Rect, share: Double, box: ProgressRules.SegmentBox, line: Double, whole: Rect) {
            val cutRect = rect(box.start, box.inline * minOf(1.0, share))
            canvas.drawPath(intersect(shape, cutRect), fillPaint(line, whole.size))
        }
    }
}
