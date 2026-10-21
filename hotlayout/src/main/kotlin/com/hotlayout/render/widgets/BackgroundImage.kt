package com.hotlayout.render.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hotlayout.HotlayoutReport
import com.hotlayout.render.RenderContext
import com.hotlayout.theme.BackgroundValue

/**
 * The picture of a background of the kind `image` (`style.background`): the same source as the `Image` widget, laid over
 * the whole box (the border box) and held by the radius of the node. It is the box that sizes it, so the file never asks
 * for room; while it loads, or when it cannot, nothing is drawn.
 */
@Composable
internal fun rememberBackgroundImage(background: BackgroundValue?, shape: androidx.compose.ui.graphics.Shape, context: RenderContext): Modifier {
    val image = background as? BackgroundValue.Image ?: return Modifier
    val address = imageAddress(image.source, context)
    val side = context.images.maxPixelSide
    var bitmap by remember(address, side) { mutableStateOf<ImageBitmap?>(address?.let { ImageStore.cached(it, side) }?.asImageBitmap()) }
    LaunchedEffect(address, side) {
        if (address != null && bitmap == null) {
            bitmap = ImageStore.load(address, context.images.maxDownloadBytes, context.images) { reason ->
                context.sink.add(HotlayoutReport.Kind.IgnoredInput, "image", reason)
            }?.asImageBitmap()
        }
    }
    val picture = bitmap?.takeIf { it.height > 0 } ?: return Modifier
    return Modifier.drawBehind {
        val rect = ImageRules.paintRect(size.width.toDouble(), size.height.toDouble(), picture.width.toDouble() / picture.height, image.fit)
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply { addOutline(outline) }
        clipPath(clip) {
            drawImage(picture, dstOffset = IntOffset(rect[0].toInt(), rect[1].toInt()), dstSize = IntSize(rect[2].toInt(), rect[3].toInt()))
        }
    }
}
