package com.hotlayout.render.widgets

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.hotlayout.render.color
import com.hotlayout.theme.GradientValue
import com.hotlayout.theme.Rgba
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The pen of a scale: one place that makes paints, so that the glow copy (blurred, drawn under the
 * scale) goes through the very same shapes as the scale itself. All lengths here are pixels.
 */
internal class ProgressInk(private val glow: ProgressRules.Glow?, private val density: Float) {
    val isGlow: Boolean get() = glow != null

    /** Skia asks a radius, and turns it into a sigma by `0.57735 × radius + 0.5`. */
    private fun blurRadius(sigmaPx: Float): Float = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

    private fun base(): Paint {
        val paint = Paint()
        paint.isAntiAlias = true
        if (glow != null) paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blurRadius((glow.sigma * density).toFloat()), BlurMaskFilter.Blur.NORMAL)
        return paint
    }

    private fun alpha(): Float = glow?.opacity?.toFloat() ?: 1f

    fun solid(color: Rgba): Paint = base().also { paint ->
        paint.color = Color(color.argb())
        paint.alpha = paint.alpha * alpha()
    }

    fun brush(brush: Brush, size: Size): Paint = base().also { brush.applyTo(size, it, alpha()) }

    fun stroke(paint: Paint, width: Float): Paint = paint.also {
        it.style = PaintingStyle.Stroke
        it.strokeWidth = width
        it.strokeCap = StrokeCap.Butt
    }
}

/** A rounded rectangle as a path; the radius is limited by half of the shorter side. */
internal fun roundedPath(rect: Rect, radius: Float): Path {
    val r = min(radius, min(rect.width, rect.height) / 2).coerceAtLeast(0f)
    return Path().apply { addRoundRect(RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(r, r))) }
}

/** The part of [shape] that lies inside [cut]: the shape's rounded outline kept, its fill cut. */
internal fun intersect(shape: Path, cut: Rect): Path = Path().apply { op(shape, Path().apply { addRect(cut) }, PathOperation.Intersect) }

/** The gradient of a fill as a brush along a line from [from] to [to] (pixels). */
internal fun linearBrush(stops: List<GradientValue.Stop>, from: Offset, to: Offset): Brush {
    val list = stops.map { it.position.toFloat() to it.color.color }.toTypedArray()
    return Brush.linearGradient(*list, start = from, end = to)
}

internal fun DrawScope.withCanvas(block: (Canvas) -> Unit) = drawIntoCanvas { block(it) }

/** Whether a canvas draws in software with a blur filter; kept for tests that read the native canvas. */
internal fun Canvas.native(): android.graphics.Canvas = nativeCanvas

internal fun polar(center: Offset, radius: Float, degrees: Double): Offset {
    val radians = Math.toRadians(degrees)
    return Offset(center.x + (radius * cos(radians)).toFloat(), center.y + (radius * sin(radians)).toFloat())
}

internal fun Path.native(): android.graphics.Path = asAndroidPath()
