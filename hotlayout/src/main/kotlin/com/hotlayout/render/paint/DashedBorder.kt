package com.hotlayout.render.paint

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.LayoutDirection
import com.hotlayout.JsonValue
import com.hotlayout.theme.Corners

/**
 * A dashed border (`style.border.style: 'dashed'`, `x-style.layout.borderDash`): the centre line of
 * the border, dashes of three widths and gaps of two, the count rounded so that the loop closes
 * evenly. Not the system dash: the pattern is the contract's.
 */
internal object DashedBorderRules {
    const val WORD = "dashed"
    const val DASH = 3.0
    const val GAP = 2.0

    data class Radii(val topStart: Double = 0.0, val topEnd: Double = 0.0, val bottomEnd: Double = 0.0, val bottomStart: Double = 0.0)

    data class Box(val width: Double, val height: Double, val border: Double, val radii: Radii)

    data class Line(val radii: Radii, val perimeter: Double, val count: Int, val dash: Double, val gap: Double)

    data class Frame(val left: Double, val top: Double, val right: Double, val bottom: Double, val radii: Radii)

    /** Only the word `dashed` changes the line; any other word is solid. */
    fun isDashed(border: JsonValue?): Boolean = border?.get("style")?.string == WORD

    fun radii(corners: Corners?): Radii = corners?.let { Radii(it.topStart, it.topEnd, it.bottomEnd, it.bottomStart) } ?: Radii()

    /** The outer radii scaled down as CSS does when they do not fit. */
    private fun fitted(box: Box): Radii {
        fun clamp(value: Double) = if (value.isFinite() && value > 0) value else 0.0
        val r = box.radii
        val topStart = clamp(r.topStart)
        val topEnd = clamp(r.topEnd)
        val bottomEnd = clamp(r.bottomEnd)
        val bottomStart = clamp(r.bottomStart)
        fun ratio(side: Double, a: Double, b: Double) = if (a + b > side) side / (a + b) else 1.0
        val factor = minOf(
            ratio(box.width, topStart, topEnd), ratio(box.width, bottomStart, bottomEnd),
            ratio(box.height, topStart, bottomStart), ratio(box.height, topEnd, bottomEnd),
        )
        return Radii(topStart * factor, topEnd * factor, bottomEnd * factor, bottomStart * factor)
    }

    fun line(box: Box): Line {
        val border = maxOf(0.0, box.border)
        val outer = fitted(box)
        val half = border / 2
        fun inner(radius: Double) = maxOf(0.0, radius - half)
        val radii = Radii(inner(outer.topStart), inner(outer.topEnd), inner(outer.bottomEnd), inner(outer.bottomStart))
        val width = maxOf(0.0, box.width - border)
        val height = maxOf(0.0, box.height - border)
        val sum = radii.topStart + radii.topEnd + radii.bottomEnd + radii.bottomStart
        val perimeter = maxOf(0.0, 2 * (width + height) - 2 * sum + (Math.PI / 2) * sum)
        val step = (DASH + GAP) * border
        if (step <= 0 || perimeter <= 0) return Line(radii, perimeter, 0, 0.0, 0.0)
        // `Math.round` of the reference is half up; the quotient is positive.
        val count = maxOf(1, Math.floor(perimeter / step + 0.5).toInt())
        val stretch = perimeter / (count * step)
        return Line(radii, perimeter, count, DASH * border * stretch, GAP * border * stretch)
    }

    /** The centre line as a rectangle: half a width inside the outer edge. */
    fun frame(box: Box): Frame {
        val half = maxOf(0.0, box.border) / 2
        return Frame(half, half, maxOf(half, box.width - half), maxOf(half, box.height - half), line(box).radii)
    }

    /** The path as the table writes it: from the inline start of the top straight edge toward the end. */
    fun pathString(box: Box): String {
        val f = frame(box)
        fun n(value: Double): String {
            val rounded = Math.round(value * 1000) / 1000.0
            return if (rounded == Math.rint(rounded)) rounded.toLong().toString() else rounded.toString()
        }
        fun arc(r: Double, x: Double, y: Double) = if (r > 0) "A${n(r)} ${n(r)} 0 0 1 ${n(x)} ${n(y)}" else "L${n(x)} ${n(y)}"
        val a = f.radii.topStart
        val b = f.radii.topEnd
        val c = f.radii.bottomEnd
        val d = f.radii.bottomStart
        return listOf(
            "M${n(f.left + a)} ${n(f.top)}", "L${n(f.right - b)} ${n(f.top)}", arc(b, f.right, f.top + b),
            "L${n(f.right)} ${n(f.bottom - c)}", arc(c, f.right - c, f.bottom), "L${n(f.left + d)} ${n(f.bottom)}",
            arc(d, f.left, f.bottom - d), "L${n(f.left)} ${n(f.top + a)}", arc(a, f.left + a, f.top), "Z",
        ).joinToString(" ")
    }
}

/**
 * Draws the border of a box as dashes (`style.border.style: 'dashed'`), sized by its own box. Right
 * to left mirrors the drawing, so the line still starts at the inline start.
 */
internal fun Modifier.dashedBorder(width: Double, color: Color, radii: Corners?): Modifier = drawWithContent {
    drawContent()
    val density = this.density
    val box = DashedBorderRules.Box(size.width / density.toDouble(), size.height / density.toDouble(), width, DashedBorderRules.radii(radii))
    val line = DashedBorderRules.line(box)
    if (line.count == 0) return@drawWithContent
    val frame = DashedBorderRules.frame(box)
    val r = frame.radii
    fun px(value: Double) = (value * density).toFloat()
    val left = px(frame.left)
    val top = px(frame.top)
    val right = px(frame.right)
    val bottom = px(frame.bottom)
    val path = Path()
    fun arc(radius: Double, cx: Float, cy: Float, start: Float) {
        val rad = px(radius)
        if (rad > 0) path.arcTo(Rect(cx - rad, cy - rad, cx + rad, cy + rad), start, 90f, false)
    }
    path.moveTo(left + px(r.topStart), top)
    path.lineTo(right - px(r.topEnd), top)
    arc(r.topEnd, right - px(r.topEnd), top + px(r.topEnd), 270f)
    path.lineTo(right, bottom - px(r.bottomEnd))
    arc(r.bottomEnd, right - px(r.bottomEnd), bottom - px(r.bottomEnd), 0f)
    path.lineTo(left + px(r.bottomStart), bottom)
    arc(r.bottomStart, left + px(r.bottomStart), bottom - px(r.bottomStart), 90f)
    path.lineTo(left, top + px(r.topStart))
    arc(r.topStart, left + px(r.topStart), top + px(r.topStart), 180f)
    path.close()
    val stroke = Stroke(width = px(width), cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(line.dash), px(line.gap))))
    if (layoutDirection == LayoutDirection.Rtl) {
        scale(-1f, 1f, pivot = Offset(size.width / 2, size.height / 2)) { drawPath(path, color, style = stroke) }
    } else {
        drawPath(path, color, style = stroke)
    }
}
