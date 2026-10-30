package com.hotlayout.render.widgets

import androidx.compose.runtime.Composable
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.motion.CountdownTicking
import com.hotlayout.render.motion.HoldingFinalValue
import com.hotlayout.render.motion.ValueTransitioned

/**
 * `Text`: a document string in the inherited colour and style, aligned by `align`, cut at
 * `maxLines` unless `overflow` is `visible`; with composed parts, a list marker, a gradient on the
 * glyphs, a time left that ticks and numbers that lead.
 */
@Composable
internal fun TextWidget(call: WidgetCall) {
    CountdownTicking(call.node, call.context) { ticked ->
        ValueTransitioned(call.node, ticked) { shown ->
            HoldingFinalValue(shown.held, final = { TextContent(call, ticked) }, between = { TextContent(call, ticked.copy(scope = shown.stepped)) })
        }
    }
}
