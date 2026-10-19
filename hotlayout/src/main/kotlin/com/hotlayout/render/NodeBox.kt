package com.hotlayout.render

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.hotlayout.render.paint.dashedBorder
import com.hotlayout.theme.BackgroundValue
import com.hotlayout.theme.BoxLook
import com.hotlayout.theme.Corners
import com.hotlayout.theme.GradientValue
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.ShadowLayer
import com.hotlayout.theme.Sides
import kotlin.math.cos
import kotlin.math.sin

internal val Rgba.color: Color get() = Color(argb())

/** Logical corners: Compose mirrors `topStart`… in right-to-left layout, as the contract asks. */
internal fun Corners.shape(): Shape = RoundedCornerShape(topStart.dp, topEnd.dp, bottomEnd.dp, bottomStart.dp)

internal fun Sides.paddingModifier(extra: Double = 0.0): Modifier =
    Modifier.padding(start = (start + extra).dp, top = (top + extra).dp, end = (end + extra).dp, bottom = (bottom + extra).dp)

/** The same look without its margin (the margin stands outside the press target). */
internal fun BoxLook.withoutMargin(): BoxLook = copy(margin = Sides.Zero)

/**
 * The box of a node: size, background, border, radius, shadow, opacity, in the order CSS draws them
 * (`sizeIncludes: border, padding`; the radius clips the background and the content). The margin
 * is applied by the caller, outside the press target.
 */
internal fun Modifier.nodeBox(look: BoxLook, stretch: Boolean, rightToLeft: Boolean): Modifier {
    val shape = (look.radius ?: Corners()).shape()
    val rounded = look.radius != null
    val border = look.borderWidth ?: 0.0
    var modifier = this
    look.width?.let { modifier = modifier.width(it.dp) } ?: run { if (stretch) modifier = modifier.fillMaxWidth() }
    if (look.minWidth != null || look.maxWidth != null) {
        modifier = modifier.widthIn(min = look.minWidth?.dp ?: androidx.compose.ui.unit.Dp.Unspecified, max = look.maxWidth?.dp ?: androidx.compose.ui.unit.Dp.Unspecified)
    }
    // A named height is held by the limits too (the minimum beats the maximum), so the clamp is done here:
    // `height()` first would fix the constraints and the limits inside could no longer change them.
    look.height?.let { named ->
        val capped = look.maxHeight?.let { minOf(named, it) } ?: named
        modifier = modifier.height((look.minHeight?.let { maxOf(capped, it) } ?: capped).dp)
    }
    if (look.height == null && (look.minHeight != null || look.maxHeight != null)) {
        modifier = modifier.heightIn(min = look.minHeight?.dp ?: androidx.compose.ui.unit.Dp.Unspecified, max = look.maxHeight?.dp ?: androidx.compose.ui.unit.Dp.Unspecified)
    }
    look.opacity?.let { modifier = modifier.alpha(it.toFloat()) }
    if (look.shadow.isNotEmpty()) modifier = modifier.cssShadows(look.shadow, shape)
    if (rounded || look.width != null || look.height != null) modifier = modifier.clip(shape)
    modifier = modifier.backgroundOf(look.background, shape, rightToLeft)
    val borderColor = look.borderColor
    if (borderColor != null && border > 0) {
        modifier = if (look.borderDashed) modifier.dashedBorder(border, borderColor.color, look.radius) else modifier.border(border.dp, borderColor.color, shape)
    }
    return modifier.then(look.padding.paddingModifier(border))
}

/** A background of the contract: a colour, a CSS linear gradient, or a picture (drawn by its widget family). */
internal fun Modifier.backgroundOf(background: BackgroundValue?, shape: Shape, rightToLeft: Boolean): Modifier = when (background) {
    is BackgroundValue.Color -> background(background.color.color, shape)
    is BackgroundValue.Gradient -> drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutlineBrush(outline, cssLinearGradient(background.gradient, size, rightToLeft))
    }
    else -> this
}

private fun DrawScope.drawOutlineBrush(outline: Outline, brush: Brush) {
    when (outline) {
        is Outline.Rectangle -> drawRect(brush)
        is Outline.Rounded -> drawPath(Path().apply { addRoundRect(outline.roundRect) }, brush)
        is Outline.Generic -> drawPath(outline.path, brush)
    }
}

/**
 * A CSS linear gradient: 0° points up, 90° to the end; a diagonal runs corner to corner
 * (`diagonalGradientGeometry: 'css-to-corner'`).
 */
internal fun cssLinearGradient(gradient: GradientValue, size: Size, rightToLeft: Boolean): Brush {
    var angle = gradient.angle % 360
    if (angle < 0) angle += 360
    if (rightToLeft) angle = (360 - angle) % 360
    val radians = Math.toRadians(angle)
    var dx = sin(radians)
    var dy = -cos(radians)
    if (angle % 90 != 0.0) {
        dx = if (dx > 0) 1.0 else -1.0
        dy = if (dy > 0) 1.0 else -1.0
    }
    val start = Offset(((0.5 - dx / 2) * size.width).toFloat(), ((0.5 - dy / 2) * size.height).toFloat())
    val end = Offset(size.width - start.x, size.height - start.y)
    val stops = gradient.stops.map { it.position.toFloat() to it.color.color }.toTypedArray()
    return Brush.linearGradient(*stops, start = start, end = end)
}

/**
 * CSS outer shadows: drawn only OUTSIDE the box (the inside is clipped out), so a box without a
 * background shows no shadow through itself. Blur is a Gaussian of half the CSS blur radius.
 */
internal fun Modifier.cssShadows(layers: List<ShadowLayer>, shape: Shape): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val boxPath = Path().apply { addOutline(outline) }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.clipOutPath(boxPath.asAndroidPath())
        for (layer in layers) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = layer.color.argb()
            val sigma = (layer.blur / 2).toFloat() * density
            if (sigma > 0f) paint.maskFilter = BlurMaskFilter(sigma, BlurMaskFilter.Blur.NORMAL)
            val spread = layer.spread.toFloat() * density
            val grown = Size(maxOf(0f, size.width + 2 * spread), maxOf(0f, size.height + 2 * spread))
            val shifted = Path().apply { addOutline(shape.createOutline(grown, layoutDirection, this@drawBehind)) }
            shifted.translate(Offset(layer.offsetX.toFloat() * density - spread, layer.offsetY.toFloat() * density - spread))
            native.drawPath(shifted.asAndroidPath(), paint)
        }
        native.restore()
    }
}
