package com.hotlayout.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.render.layout.ColumnWidget
import com.hotlayout.render.layout.RowWidget
import com.hotlayout.render.widgets.BadgeWidget
import com.hotlayout.render.widgets.ButtonWidget
import com.hotlayout.render.widgets.ChoiceGroupWidget
import com.hotlayout.render.widgets.IconButtonWidget
import com.hotlayout.render.widgets.DividerWidget
import com.hotlayout.render.widgets.IconWidget
import com.hotlayout.render.widgets.ImageWidget
import com.hotlayout.render.widgets.SpacerWidget
import com.hotlayout.render.widgets.TextWidget

/**
 * Widgets this reader draws. Every other widget of the schema is drawn by the unknown-widget rule
 * for now (its children in a column, `unknownWidget: 'render-children'`) and reported.
 */
internal val drawnWidgets: Set<String> = setOf(
    "Screen", "Column", "Row", "Container", "Text", "Image", "Icon", "Button", "Spacer", "Divider", "Badge", "IconButton", "ChoiceGroup",
)

/**
 * The one dispatcher by widget type. A new widget family adds ONE line here and its type to
 * [drawnWidgets]; everything else lives in the family's own file.
 */
@Composable
internal fun Widget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    when (node.type) {
        "Screen", "Column" -> ColumnWidget(node.children, context.resolver.spacing(node.props["gap"] ?: JsonValue.Str("sm")) ?: 0.0, node, context, call.depth, call.placement.stretchWidth)
        "Row" -> RowWidget(node, context, call.depth, call.placement.stretchWidth)
        "Container" -> if (node.props["direction"]?.string == "row") {
            RowWidget(node, context, call.depth, call.placement.stretchWidth)
        } else {
            ColumnWidget(node.children, context.resolver.spacing(node.props["gap"] ?: JsonValue.Str("none")) ?: 0.0, node, context, call.depth, call.placement.stretchWidth)
        }
        "Text" -> TextWidget(call)
        "Image" -> ImageWidget(call)
        "Icon" -> IconWidget(call)
        "Button" -> ButtonWidget(call)
        "Badge" -> BadgeWidget(call)
        "IconButton" -> IconButtonWidget(call)
        "ChoiceGroup" -> ChoiceGroupWidget(call)
        "Spacer" -> SpacerWidget(call)
        "Divider" -> DividerWidget(call)
        else -> {
            // Not drawn by this reader (yet): the children are drawn, as for an unknown widget.
            SideEffect {
                context.sink.add(HotlayoutReport.Kind.UnknownWidget, context.location(node), "“${node.type}” is not drawn by this reader yet; its children are drawn")
            }
            ColumnWidget(node.children, 0.0, null, context, call.depth)
        }
    }
}
