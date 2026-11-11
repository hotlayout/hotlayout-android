package com.hotlayout.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.hotlayout.theme.TextShadowLayer
import com.hotlayout.theme.TextShadowRules

/**
 * The text a shadow is cast from: the same string and the same pieces as the text it lies under, with every colour and brush
 * taken off, so only the shadow of the glyphs shows.
 */
internal class ShadowCopy(val text: AnnotatedString, val style: TextStyle, val maxLines: Int, val softWrap: Boolean)

/**
 * The shadow of the glyphs of a text (`style.textShadow`): a copy of the whole paragraph under it for each layer, moved by the offset
 * along the screen axes and blurred like the shadow of a box. The first layer lies nearest to the text and on top. The copy is
 * laid out like the text but takes no room and is no part of the semantics. Under a gradient on the glyphs it lies under the
 * gradient, so an opaque gradient hides it as a solid colour would.
 */
@Composable
internal fun Modifier.textShadowBehind(layers: List<TextShadowLayer>, copy: ShadowCopy): Modifier {
    if (layers.isEmpty()) return this
    val measurer = rememberTextMeasurer()
    return drawBehind { drawTextShadows(layers, copy, measurer) }
}

private fun DrawScope.drawTextShadows(layers: List<TextShadowLayer>, copy: ShadowCopy, measurer: TextMeasurer) {
    if (size.width <= 0f) return
    val layout = measurer.measure(
        copy.text, copy.style, TextOverflow.Ellipsis, copy.softWrap, copy.maxLines,
        constraints = Constraints(maxWidth = size.width.toInt()),
    )
    // The list runs from the layer nearest the text; painted from the bottom, the first goes last.
    for (layer in TextShadowRules.bottomFirst(layers)) {
        val sigma = (layer.blur / 2).toFloat() * density
        val blur = if (sigma > 0f) blurRadiusOfSigma(sigma) else 0f
        drawText(layout, shadow = Shadow(layer.color.color, Offset(layer.offsetX.toFloat() * density, layer.offsetY.toFloat() * density), blur))
    }
}
