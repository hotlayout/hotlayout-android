package com.hotlayout.render.paint

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.hotlayout.render.blurRadiusOfSigma
import com.hotlayout.render.shape
import com.hotlayout.theme.Corners
import com.hotlayout.theme.ShadowLayer

/**
 * The geometry of an inner layer of a shadow, for a box of a size (all numbers in dp).
 *
 * The layer is clipped to the padding box (the box inside its border, corners smaller by the border), and what is painted is
 * everything OUTSIDE a hole: the clip moved by the offset and narrowed by the spread on every side (a positive spread makes the
 * shadow thicker, a negative one thinner), its corners smaller by the spread, a square corner staying square. As in CSS
 * `box-shadow: inset`.
 */
internal object InsetShadowGeometry {
    internal data class Rect(val x: Double, val y: Double, val width: Double, val height: Double, val corners: Corners)

    /** The padding box: the box inside the border, its corners smaller by the border width. */
    fun clip(width: Double, height: Double, border: Double, corners: Corners): Rect {
        fun inner(radius: Double) = maxOf(0.0, radius - border)
        return Rect(
            border, border, maxOf(0.0, width - 2 * border), maxOf(0.0, height - 2 * border),
            Corners(inner(corners.topStart), inner(corners.topEnd), inner(corners.bottomEnd), inner(corners.bottomStart)),
        )
    }

    /** The hole of a layer inside the clip. */
    fun hole(clip: Rect, offsetX: Double, offsetY: Double, spread: Double): Rect {
        fun narrowed(radius: Double) = if (radius > 0) maxOf(0.0, radius - spread) else 0.0
        val c = clip.corners
        return Rect(
            clip.x + spread + offsetX, clip.y + spread + offsetY, maxOf(0.0, clip.width - 2 * spread), maxOf(0.0, clip.height - 2 * spread),
            Corners(narrowed(c.topStart), narrowed(c.topEnd), narrowed(c.bottomEnd), narrowed(c.bottomStart)),
        )
    }
}

/** How far past the box the painted outside of a hole reaches: far enough for the largest blur and offset. */
private const val REACH_DP = 600f

/**
 * The inner layers of a shadow (`inset: true`): inside the border, over the background and under the content. The first layer
 * lies nearest to the element and on top. A layer is the outside of its hole filled with its colour, blurred like the outer layers
 * and cut to the padding box; the offset is physical. Outer layers are not drawn here (`cssShadows` draws them).
 */
internal fun Modifier.innerShadows(layers: List<ShadowLayer>, radius: Corners?, borderWidth: Double): Modifier {
    val inner = layers.filter { it.inset }
    if (inner.isEmpty()) return this
    val corners = radius ?: Corners()
    return drawBehind {
        val clip = InsetShadowGeometry.clip((size.width / density).toDouble(), (size.height / density).toDouble(), borderWidth, corners)
        val clipPath = rectPath(clip, this, this.layoutDirection)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipPath(clipPath.asAndroidPath())
            // The list runs from the layer nearest the element; painted from the bottom, the first goes last.
            for (layer in inner.asReversed()) {
                val hole = InsetShadowGeometry.hole(clip, layer.offsetX, layer.offsetY, layer.spread)
                val reach = REACH_DP * density
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(-reach, -reach, size.width + reach, size.height + reach))
                    addPath(rectPath(hole, this@drawBehind, this@drawBehind.layoutDirection))
                }
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = layer.color.argb()
                val sigma = (layer.blur / 2).toFloat() * density
                if (sigma > 0f) paint.maskFilter = BlurMaskFilter(blurRadiusOfSigma(sigma), BlurMaskFilter.Blur.NORMAL)
                native.drawPath(outside.asAndroidPath(), paint)
            }
            native.restore()
        }
    }
}

/** The path of a rounded rectangle of [InsetShadowGeometry] (dp) in the pixels of the scope. */
private fun rectPath(rect: InsetShadowGeometry.Rect, density: androidx.compose.ui.unit.Density, direction: androidx.compose.ui.unit.LayoutDirection): Path {
    val size = Size((rect.width * density.density).toFloat(), (rect.height * density.density).toFloat())
    val path = Path().apply { addOutline(rect.corners.shape().createOutline(size, direction, density)) }
    path.translate(Offset((rect.x * density.density).toFloat(), (rect.y * density.density).toFloat()))
    return path
}
