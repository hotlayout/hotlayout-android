package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.textAlign

/**
 * `Text`: a document string in the inherited colour and style, aligned by `align`, cut at
 * `maxLines` unless `overflow` is `visible`.
 *
 * Wave «text» extends this file: composed parts (`spans`), the list marker, the gradient on the
 * glyphs.
 */
@Composable
internal fun TextWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val align = node.props["align"]?.string
    val maxLines = node.props["maxLines"]?.number?.let { it.coerceIn(1.0, 20.0).toInt() }
    StyledText(
        text = context.text(node, "content"),
        style = context.inherited.typography,
        color = context.inherited.color,
        context = context,
        modifier = if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier,
        italic = context.inherited.italic,
        decoration = context.inherited.decoration,
        align = textAlign(align),
        maxLines = if (node.props["overflow"]?.string == "visible") Int.MAX_VALUE else maxLines ?: Int.MAX_VALUE,
    )
}
