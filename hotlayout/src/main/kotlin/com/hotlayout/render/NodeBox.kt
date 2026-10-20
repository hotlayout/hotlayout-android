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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
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
import com.hotlayout.render.dp
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
 *
 * A named size clips the content larger than the box; a role surface (a button) is clipped by its own shape inside, and the
 * shadow it paints outside its box must not be cut by this outer box, so it passes `clipsNamedSize = false`.
 */
internal fun Modifier.nodeBox(look: BoxLook, stretch: Boolean, rightToLeft: Boolean, clipsNamedSize: Boolean = true, backgroundImage: Modifier = Modifier): Modifier {
    val shape = (look.radius ?: Corners()).shape()
    val rounded = look.radius != null
    val border = look.borderWidth ?: 0.0
    var modifier = this
    // The limits come first (outside): a box that stretches fills what they leave it (`width: 100%; max-width: 190px` is 190),
    // while a limit set inside a forced width could not narrow it.
    if (look.minWidth != null || look.maxWidth != null) {
        modifier = modifier.widthIn(min = look.minWidth?.dp ?: androidx.compose.ui.unit.Dp.Unspecified, max = look.maxWidth?.dp ?: androidx.compose.ui.unit.Dp.Unspecified)
    }
    look.width?.let { modifier = modifier.width(it.dp) } ?: run {
        if (stretch) modifier = if (look.maxWidth != null) modifier.stretchUpTo(look.maxWidth.dp) else modifier.fillMaxWidth()
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
    if (rounded || (clipsNamedSize && (look.width != null || look.height != null))) modifier = modifier.clip(shape)
    modifier = modifier.backgroundOf(look.background, shape, rightToLeft).then(backgroundImage)
    val borderColor = look.borderColor
    if (borderColor != null && border > 0) {
        modifier = if (look.borderDashed) modifier.dashedBorder(border, borderColor.color, look.radius) else modifier.border(border.dp, borderColor.color, shape)
    }
    return modifier.then(look.padding.paddingModifier(border))
}

/**
 * A box that stretches to its parent but names a largest width: it takes the width the parent gives, held to [limit], and
 * stands at the start (`width: 100%` with `max-width` in a flex column). The parent still sees the width it forced, so a
 * column that makes its stretched children as wide as itself keeps its own layout.
 */
private fun Modifier.stretchUpTo(limit: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val room = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
    val width = minOf(room, limit.roundToPx())
    val placeable = measurable.measure(Constraints(minWidth = minOf(width, if (constraints.hasBoundedWidth) width else 0), maxWidth = width, minHeight = constraints.minHeight, maxHeight = constraints.maxHeight))
    val reported = placeable.width.coerceIn(constraints.minWidth, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
    layout(reported, placeable.height) { placeable.placeRelative(0, 0) }
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
 * A linear gradient of the contract (`x-gradientGeometry`). The axis angles are CSS degrees, from the middle of one edge to the
 * middle of the opposite one. The diagonal angles are the CSS `to <corner>` (45 to top right, 135 to bottom right, 225 to bottom
 * left, 315 to top left): the gradient line runs through the centre perpendicular to the diagonal between the two OTHER corners,
 * so on a wide box the bands are nearly parallel to that diagonal and not to the other one; for a square it is corner to corner.
 * The direction is physical: it is not mirrored in right-to-left scripts.
 */
internal fun cssLinearGradient(gradient: GradientValue, size: Size, @Suppress("UNUSED_PARAMETER") rightToLeft: Boolean): Brush {
    var angle = gradient.angle % 360
    if (angle < 0) angle += 360
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    // Normalized start and end of the gradient line, (0, 0) at the top start corner, y down.
    val line: DoubleArray = when (angle) {
        0.0 -> doubleArrayOf(0.5, 1.0, 0.5, 0.0)
        90.0 -> doubleArrayOf(0.0, 0.5, 1.0, 0.5)
        180.0 -> doubleArrayOf(0.5, 0.0, 0.5, 1.0)
        270.0 -> doubleArrayOf(1.0, 0.5, 0.0, 0.5)
        45.0, 135.0, 225.0, 315.0 -> {
            val sx = if (angle == 45.0 || angle == 135.0) 1.0 else -1.0
            val sy = if (angle == 135.0 || angle == 225.0) 1.0 else -1.0
            val total = w * w + h * h
            val ex = 0.5 + if (total == 0.0) 0.5 * sx else sx * h * h / total
            val ey = 0.5 + if (total == 0.0) 0.5 * sy else sy * w * w / total
            doubleArrayOf(1.0 - ex, 1.0 - ey, ex, ey)
        }
        else -> {
            // An angle the contract does not name: corner to corner in the quadrant it points to.
            val radians = Math.toRadians(angle)
            val dx = if (sin(radians) > 0) 1.0 else -1.0
            val dy = if (-cos(radians) > 0) 1.0 else -1.0
            doubleArrayOf(0.5 - dx / 2, 0.5 - dy / 2, 0.5 + dx / 2, 0.5 + dy / 2)
        }
    }
    val start = Offset((line[0] * w).toFloat(), (line[1] * h).toFloat())
    val end = Offset((line[2] * w).toFloat(), (line[3] * h).toFloat())
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
            if (sigma > 0f) paint.maskFilter = BlurMaskFilter(blurRadiusOfSigma(sigma), BlurMaskFilter.Blur.NORMAL)
            val spread = layer.spread.toFloat() * density
            val grown = Size(maxOf(0f, size.width + 2 * spread), maxOf(0f, size.height + 2 * spread))
            val shifted = Path().apply { addOutline(shape.createOutline(grown, layoutDirection, this@drawBehind)) }
            shifted.translate(Offset(layer.offsetX.toFloat() * density - spread, layer.offsetY.toFloat() * density - spread))
            native.drawPath(shifted.asAndroidPath(), paint)
        }
        native.restore()
    }
}

/**
 * `BlurMaskFilter` asks a radius, and Skia turns it into a deviation by `0.57735 × radius + 0.5`; a CSS blur of B is a Gaussian
 * of deviation B / 2, so the radius to ask for is the inverse of that.
 */
internal fun blurRadiusOfSigma(sigmaPx: Float): Float = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
