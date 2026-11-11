package com.hotlayout.render.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.hotlayout.render.LocalTabularFigures
import com.hotlayout.render.motion.ValueTransition
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
    // A text that runs its numbers is written in figures of equal width always, at rest too: the place it holds and the number
    // painted over it never differ in width.
    val tabular = ValueTransition.tabularFigures(call.node.type, call.node.props)
    CompositionLocalProvider(LocalTabularFigures provides (tabular || LocalTabularFigures.current)) {
        CountdownTicking(call.node, call.context) { ticked ->
            ValueTransitioned(call.node, ticked) { shown ->
                HoldingFinalValue(shown.held, final = { TextContent(call, ticked) }, between = { TextContent(call, ticked.copy(scope = shown.stepped)) })
            }
        }
    }
}
