package com.hotlayout.render.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.screen.NavBarRules
import com.hotlayout.render.screen.NavBadgeDot
import com.hotlayout.render.widgets.ButtonGeometry
import com.hotlayout.render.widgets.IconBesideTextRules
import com.hotlayout.render.widgets.IconGlyph
import com.hotlayout.render.widgets.ListTileRules
import com.hotlayout.render.widgets.PartStyleRules
import com.hotlayout.render.widgets.iconWordBox
import com.hotlayout.theme.HotlayoutTheme
import kotlin.math.roundToInt

/** The numbers of an item at the top from the theme: the side padding is `md`, the gap between glyph and label `xs`, the gap between items `xs`, the box of the glyph is that of `md`. */
internal fun wideNavMetrics(theme: HotlayoutTheme): WideChrome.NavMetrics = WideChrome.NavMetrics(
    paddingInline = theme.spacing["md"] ?: 12.0,
    iconWidth = iconWordBox("md", theme).second.first,
    gap = theme.spacing["xs"] ?: 4.0,
    between = theme.spacing["xs"] ?: 4.0,
)

/**
 * A bar of navigation of its own at the top (`wideNav.ownBar`): the items from the start of the row, natural widths,
 * with their labels when all fit together, else the glyphs alone; the surface is `surface` with a line UNDER it,
 * unless the node names its own background or border. The status bar inset lies inside its box, as in an app bar.
 */
@Composable
internal fun OwnNavBar(call: WidgetCall) {
    val context = call.context
    val theme = context.theme
    val items = call.node.children.filter { it.type == "NavItem" && context.isShown(it) }.take(NavBarRules.MAX_ITEMS)
    val line = if (call.style["border"] == null) 1.0 else 0.0
    val surface = if (call.style["background"] == null) theme.colorValue("surface")?.color ?: Color.White else Color.Transparent
    val borderColor = theme.colorValue("border")?.color ?: Color(0xFFE6E6EB)
    val child = context.copy(contentMaxWidth = null, navAtTop = true)
    Column(
        Modifier.fillMaxWidth().clipToBounds().background(surface)
            .drawBehind { if (line > 0) drawRect(borderColor, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - (line * density).toFloat()), size = Size(size.width, (line * density).toFloat())) }
            .padding(top = context.barInset.dp),
    ) {
        WideNavRow(wideNavMetrics(theme), Modifier.contentColumn(context.contentMaxWidth)) {
            for (item in items) NodeView(item, child, Placement.Natural, call.depth + 1, "NavBar")
        }
        if (line > 0) androidx.compose.foundation.layout.Spacer(Modifier.height(line.dp))
    }
}

/** The items of a bar of its own: from the start of the row, each at its natural width; the labels are shown when all fit together (`WideChrome.navBar`). Items that do not fit even so are cut by the end edge. */
@Composable
private fun WideNavRow(metrics: WideChrome.NavMetrics, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth().clipToBounds(), WideNavRowPolicy(metrics))
}

private class WideNavRowPolicy(val metrics: WideChrome.NavMetrics) : MeasurePolicy {
    private fun labels(items: List<Measurable>, d: Double) = items.map { maxOf(0.0, it.maxIntrinsicWidth(Constraints.Infinity) / d - metrics.fixed) }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val d = density.toDouble()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            (WideChrome.navGroupWidth(labels(measurables, d), metrics, WideChrome.NavMode.Labels) * d).roundToInt()
        }
        val box = WideChrome.navBar(width / d, labels(measurables, d), metrics)
        val placeables = measurables.mapIndexed { index, item ->
            val w = ((box.navItemWidths.getOrNull(index) ?: 0.0) * d).roundToInt()
            item.measure(Constraints(minWidth = w, maxWidth = w))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        return layout(width, constraints.constrainHeight(height)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(((box.navItemStarts.getOrNull(index) ?: 0.0) * d).roundToInt(), 0)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        (WideChrome.navGroupWidth(measurables.map { maxOf(0.0, it.maxIntrinsicWidth(Constraints.Infinity) / density.toDouble() - metrics.fixed) }, metrics, WideChrome.NavMode.Labels) * density).roundToInt()

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = maxIntrinsicHeight(measurables, width)
}

/**
 * An item at the top: the glyph and the label in a row at their natural width, the label cut at [WideChrome.LABEL_MAX].
 * The box it is given may be narrower than its content (icons alone): the label is then outside it and clipped away,
 * and stays the accessible name.
 */
@Composable
internal fun TopNavItem(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val active = NavBarRules.isActive(action, context.screenId)
    val named = context.inherited.colorRef
    val colorRef = if (active) named ?: com.hotlayout.json.JsonValue.Str("primary")
    else named?.let { PartStyleRules.withOpacity(it, ListTileRules.SECONDARY_OPACITY) } ?: com.hotlayout.json.JsonValue.Str("muted")
    val color = context.resolver.color(colorRef) ?: context.inherited.color
    val typography = theme.typography["bodyM"] ?: context.inherited.typography
    val label = context.text(node, "label")
    val icon = IconBesideTextRules.resolve(node.props, "icon", context.scope)
    val (glyph, word) = iconWordBox("md", theme)
    val metrics = wideNavMetrics(theme)
    val block = theme.spacing["sm"] ?: 8.0
    val name = ButtonGeometry.iconButtonName(label, icon.resolved)
    val showsDot = NavBarRules.badgeShown(node.props, context.scope)
    val press = { if (!active) context.press(action, node) }
    Layout(
        {
            Box(Modifier.size(word.first.dp, word.second.dp)) {
                IconGlyph(icon.name, glyph, word.first, word.second, color.color, context.rightToLeft)
                NavBadgeDot(showsDot, glyph, word.second, (theme.colorValue("accent") ?: color).color, context.rightToLeft)
            }
            StyledText(label, typography, color, context, Modifier, align = TextAlign.Start, maxLines = 1)
        },
        Modifier
            .clipToBounds()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = active
                contentDescription = name
                onClick { press(); true }
            }
            .pressInput(true, false, { press() }, {}) {},
        TopItemPolicy(metrics, block),
    )
}

private class TopItemPolicy(val metrics: WideChrome.NavMetrics, val block: Double) : MeasurePolicy {
    private fun Density.px(dp: Double) = (dp * density).roundToInt()

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val icon = measurables[0].measure(Constraints())
        val natural = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val fixed = px(metrics.fixed)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fixed + minOf(natural, px(WideChrome.LABEL_MAX))
        val room = maxOf(0, width - fixed)
        val label = measurables[1].measure(Constraints(maxWidth = minOf(room, px(WideChrome.LABEL_MAX))))
        val height = px(block) * 2 + maxOf(icon.height, label.height)
        return layout(width, constraints.constrainHeight(height)) {
            val middle = height / 2
            icon.placeRelative(px(metrics.paddingInline), middle - icon.height / 2)
            label.placeRelative(px(metrics.paddingInline + metrics.iconWidth + metrics.gap), middle - label.height / 2)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        px(metrics.fixed) + measurables[1].maxIntrinsicWidth(Constraints.Infinity)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        px(block) * 2 + maxOf(measurables[0].maxIntrinsicHeight(width), measurables[1].maxIntrinsicHeight(width))

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = maxIntrinsicHeight(measurables, width)
}
