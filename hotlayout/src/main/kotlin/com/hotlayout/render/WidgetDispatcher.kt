package com.hotlayout.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.hotlayout.HotlayoutReport
import com.hotlayout.JsonValue
import com.hotlayout.model.WidgetType
import com.hotlayout.render.layout.AlignRules
import com.hotlayout.render.layout.ColumnWidget
import com.hotlayout.render.layout.RowWidget
import com.hotlayout.render.screen.AppBarWidget
import com.hotlayout.render.screen.NavBarWidget
import com.hotlayout.render.screen.NavItemWidget
import com.hotlayout.render.widgets.AccordionWidget
import com.hotlayout.render.widgets.BadgeWidget
import com.hotlayout.render.widgets.ButtonWidget
import com.hotlayout.render.widgets.CheckboxWidget
import com.hotlayout.render.widgets.ChoiceGroupWidget
import com.hotlayout.render.widgets.CircularProgressWidget
import com.hotlayout.render.widgets.DividerWidget
import com.hotlayout.render.widgets.GridWidget
import com.hotlayout.render.widgets.IconButtonWidget
import com.hotlayout.render.widgets.IconWidget
import com.hotlayout.render.widgets.ImageWidget
import com.hotlayout.render.widgets.ListTileWidget
import com.hotlayout.render.widgets.ProgressBarWidget
import com.hotlayout.render.widgets.RepeaterWidget
import com.hotlayout.render.widgets.SpacerWidget
import com.hotlayout.render.widgets.StackWidget
import com.hotlayout.render.widgets.SwitchWidget
import com.hotlayout.render.widgets.TabsWidget
import com.hotlayout.render.widgets.TextFieldWidget
import com.hotlayout.render.widgets.TextWidget

/**
 * The one dispatcher by widget type. A new widget family adds ONE line here and its [WidgetType];
 * everything else lives in the family's own file. A type the schema does not have is drawn as its children.
 */
@Composable
internal fun Widget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    // A layer that fills its stack has the stack's height as the free height of its own flow.
    val fills = AlignRules.fills(node.fields, call.parentType)
    when (node.widget) {
        WidgetType.Screen, WidgetType.Column -> ColumnWidget(node.children, context.resolver.spacing(node.props["gap"] ?: JsonValue.Str("sm")) ?: 0.0, node, context, call.depth, call.placement.stretchWidth, fills)
        WidgetType.Row -> RowWidget(node, context, call.depth, call.placement.stretchWidth, fills)
        WidgetType.Container -> if (node.props["direction"]?.string == "row") {
            RowWidget(node, context, call.depth, call.placement.stretchWidth, fills)
        } else {
            ColumnWidget(node.children, context.resolver.spacing(node.props["gap"] ?: JsonValue.Str("none")) ?: 0.0, node, context, call.depth, call.placement.stretchWidth, fills)
        }
        WidgetType.Text -> TextWidget(call)
        WidgetType.Image -> ImageWidget(call)
        WidgetType.Icon -> IconWidget(call)
        WidgetType.Button -> ButtonWidget(call)
        WidgetType.Badge -> BadgeWidget(call)
        WidgetType.IconButton -> IconButtonWidget(call)
        WidgetType.ChoiceGroup -> ChoiceGroupWidget(call)
        WidgetType.TextField -> TextFieldWidget(call)
        WidgetType.Switch -> SwitchWidget(call)
        WidgetType.Checkbox -> CheckboxWidget(call)
        WidgetType.Spacer -> SpacerWidget(call)
        WidgetType.Divider -> DividerWidget(call)
        WidgetType.ProgressBar -> ProgressBarWidget(call)
        WidgetType.CircularProgress -> CircularProgressWidget(call)
        WidgetType.Repeater -> RepeaterWidget(call)
        WidgetType.Grid -> GridWidget(call)
        WidgetType.ListTile -> ListTileWidget(call)
        WidgetType.Accordion -> AccordionWidget(call)
        WidgetType.Tabs -> TabsWidget(call)
        // The panel is the node itself: its children in a column, `md` apart.
        WidgetType.Tab -> ColumnWidget(node.children, context.resolver.spacing(JsonValue.Str("md")) ?: 12.0, null, context, call.depth, call.placement.stretchWidth)
        WidgetType.Stack -> StackWidget(call)
        WidgetType.AppBar -> AppBarWidget(call)
        WidgetType.NavBar -> NavBarWidget(call)
        WidgetType.NavItem -> NavItemWidget(call)
        WidgetType.Unknown -> {
            // Not drawn by this reader (yet): the children are drawn, as for an unknown widget.
            SideEffect {
                context.sink.add(HotlayoutReport.Kind.UnknownWidget, context.location(node), "“${node.type}” is not drawn by this reader yet; its children are drawn")
            }
            ColumnWidget(node.children, 0.0, null, context, call.depth)
        }
    }
}
