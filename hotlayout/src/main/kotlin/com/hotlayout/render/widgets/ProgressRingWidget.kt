package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.composeTextStyle
import com.hotlayout.render.motion.ValueTransitionEngine
import com.hotlayout.render.motion.ValueTransitioned
import com.hotlayout.theme.StyleResolver
import kotlin.math.min

/**
 * `CircularProgress`: the same scale bent into an arc. The diameter comes from the box of the node;
 * the arc lies inside the circle, starts at the top and runs clockwise (not mirrored in
 * right-to-left); the label, if any, is one line in the centre that does not size the ring.
 */
@Composable
internal fun CircularProgressWidget(call: WidgetCall) {
    ValueTransitioned(call.node, call.context) { shown, tracking -> RingContent(call, shown, tracking) }
}

@Composable
private fun RingContent(call: WidgetCall, shown: ValueTransitionEngine.Shown, tracking: Modifier) {
    val node = call.node
    val context = call.context
    val props = node.props
    val resolver = context.resolver
    val theme = context.theme
    val value = ProgressRules.shownNumber(props["value"], shown.smooth, shown.literal?.second) ?: 0.0
    val max = ProgressRules.number(props["max"], shown.smooth) ?: 100.0
    val ratio = ProgressRules.ratio(value, max)
    val paint = ProgressRules.paint(props, resolver)
    // Under a row weight (a stretched placement) the width fields of the style do not name the diameter.
    val widthApplies = !call.placement.stretchWidth
    val look = resolver.box(call.style, context.scope)
    val inset = look.padding.horizontal + 2 * (look.borderWidth ?: 0.0)
    val insetBlock = look.padding.vertical + 2 * (look.borderWidth ?: 0.0)
    val geometry = ProgressRules.ringGeometry(
        ProgressRules.RingBox(
            size = props["size"], unit = theme.spacing[ProgressRules.RING_UNIT] ?: 0.0,
            width = look.width.takeIf { widthApplies }, minWidth = look.minWidth.takeIf { widthApplies }, maxWidth = look.maxWidth.takeIf { widthApplies },
            height = look.height, minHeight = look.minHeight, maxHeight = look.maxHeight,
            paddingInline = look.padding.horizontal, paddingBlock = look.padding.vertical, border = look.borderWidth ?: 0.0,
            thickness = paint.thickness,
        ),
    )
    val ring = RingScale(paint, ratio, geometry.diameter, geometry.thickness, props["cap"]?.string == "round", ProgressRules.Glow.of(props["glow"]))
    val atRest = ProgressRules.ratio(ProgressRules.number(props["value"], context.scope), ProgressRules.number(props["max"], context.scope))
    val name = context.text(node, "label")
    var box = Modifier.then(tracking)
    // A named box centres the ring in it: the box is filled, the ring stands in the middle.
    if (widthApplies && look.width != null) box = box.fillMaxWidth() else if (widthApplies && look.minWidth != null) box = box.widthIn(min = (look.minWidth - inset).coerceAtLeast(0.0).dp)
    if (look.height != null) box = box.fillMaxHeight() else if (look.minHeight != null) box = box.heightIn(min = (look.minHeight - insetBlock).coerceAtLeast(0.0).dp)

    Box(
        box.semantics {
            if (name.isNotEmpty()) contentDescription = name
            progressBarRangeInfo = ProgressBarRangeInfo(atRest.toFloat(), 0f..1f)
        },
        contentAlignment = Alignment.Center,
        propagateMinConstraints = false,
    ) {
        Box(Modifier.size(geometry.diameter.dp).drawBehind { ring.draw(this) }, contentAlignment = Alignment.Center) {
            val steppedValue = ProgressRules.shownNumber(props["value"], shown.stepped, shown.literal?.first) ?: 0.0
            val steppedMax = ProgressRules.number(props["max"], shown.stepped) ?: 100.0
            val text = ProgressRules.labelText(props["labelFormat"]?.string, steppedValue, steppedMax, ratio)
            if (text != null) {
                val look = ProgressRules.labelStyle(props, resolver)
                // One centred line, never wrapped and never clipped by the ring.
                BasicText(
                    text = AnnotatedString(text),
                    modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true),
                    style = composeTextStyle(look.typography, look.color, context).copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

/** The arc of a ring drawn in one pass over a canvas, the glow copy under it. */
internal class RingScale(
    val paint: ProgressRules.Paint,
    val ratio: Double,
    val diameter: Double,
    val thickness: Double,
    val roundEnds: Boolean,
    val glow: ProgressRules.Glow?,
) {
    fun draw(scope: DrawScope) {
        if (glow != null) pass(scope, ProgressInk(glow, scope.density))
        pass(scope, ProgressInk(null, scope.density))
    }

    private fun pass(scope: DrawScope, ink: ProgressInk) {
        if (thickness <= 0 || diameter <= 0) return
        val k = scope.density
        scope.withCanvas { canvas ->
            val center = Offset(scope.size.width / 2, scope.size.height / 2)
            val radius = ((diameter - thickness) / 2 * k).toFloat()
            val width = (thickness * k).toFloat()
            val oval = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            canvas.save()
            // The arc starts at the top: the canvas turns a quarter, so that a sweep gradient starts there too.
            canvas.translate(center.x, center.y)
            canvas.rotate(-90f)
            canvas.translate(-center.x, -center.y)
            try {
                if (!ink.isGlow) canvas.drawArc(oval.left, oval.top, oval.right, oval.bottom, 0f, 360f, false, ink.stroke(ink.solid(paint.track), width))
                val share = ProgressRules.clamped(ratio)
                if (share <= 0) return@withCanvas
                val sweepBrush = conic(center, share)
                fun stroke(from: Double, to: Double) {
                    canvas.drawArc(oval.left, oval.top, oval.right, oval.bottom, from.toFloat(), (to - from).toFloat(), false, ink.stroke(if (paint.fill is ProgressRules.Fill.Solid) ink.solid(paint.fill.color) else ink.brush(sweepBrush, scope.size), width))
                }
                fun dot(angle: Double, sweep: Double) {
                    val point = polar(center, radius, angle)
                    val shade = colorAt(angle, sweep)
                    canvas.drawCircle(point, width / 2, ink.solid(shade))
                }
                if (!roundEnds) {
                    stroke(0.0, if (share >= 1) 360.0 else 360 * share)
                    return@withCanvas
                }
                val cap = ProgressRules.ringCap(share, diameter, thickness)
                val sweep = 360 * share
                when (cap.kind) {
                    ProgressRules.RingCapKind.None -> Unit
                    ProgressRules.RingCapKind.Full -> stroke(0.0, 360.0)
                    ProgressRules.RingCapKind.Dot -> dot(cap.startDeg, sweep)
                    ProgressRules.RingCapKind.Arc -> {
                        stroke(cap.startDeg, cap.endDeg)
                        dot(cap.startDeg, sweep)
                        dot(cap.endDeg, sweep)
                    }
                }
            } finally {
                canvas.restore()
            }
        }
    }

    /** The stops lie along the FILLED part: the first colour runs from the start, the last to the end of the fill. */
    private fun conic(center: Offset, share: Double): Brush = when (val fill = paint.fill) {
        is ProgressRules.Fill.Solid -> Brush.sweepGradient(0f to fill.color.color, 1f to fill.color.color, center = center)
        is ProgressRules.Fill.Gradient -> {
            val on = ProgressRules.gradientStops(fill.stops, share)
            val list = ArrayList<Pair<Float, androidx.compose.ui.graphics.Color>>()
            on.firstOrNull()?.let { list.add(0f to it.first.color) }
            on.forEach { list.add(it.second.coerceIn(0.0, 1.0).toFloat() to it.first.color) }
            on.lastOrNull()?.let { list.add(min(1.0, share).toFloat() to it.first.color) }
            list.add(1f to (on.lastOrNull()?.first?.color ?: androidx.compose.ui.graphics.Color.Transparent))
            Brush.sweepGradient(*list.toTypedArray(), center = center)
        }
    }

    private fun colorAt(angle: Double, sweep: Double) = when (val fill = paint.fill) {
        is ProgressRules.Fill.Solid -> fill.color
        is ProgressRules.Fill.Gradient -> ProgressRules.gradientColor(fill.stops, if (sweep > 0) angle / sweep else 0.0)
    }
}
