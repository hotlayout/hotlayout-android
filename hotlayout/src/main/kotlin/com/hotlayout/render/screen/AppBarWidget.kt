package com.hotlayout.render.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.adaptive.contentColumn
import com.hotlayout.render.adaptive.wideNavMetrics
import com.hotlayout.render.color
import com.hotlayout.render.dp
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.roundToInt
import com.hotlayout.render.widgets.IconGlyph
import com.hotlayout.render.widgets.ListTileRules
import com.hotlayout.render.widgets.PartStyleRules
import com.hotlayout.render.widgets.iconWordBox

/** Which part of the app bar a child of the row is. */
internal enum class AppBarPart { Back, LeadingNode, Title, Action, NavItem }

private class PartData(val part: AppBarPart) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = part
}

private val Measurable.part: AppBarPart get() = parentData as? AppBarPart ?: AppBarPart.Title

/**
 * `AppBar`: the back arrow or the node of the leading zone, the title block, and up to two
 * visible actions (`x-style.layout.appBar`, `appBarLeading`, `appBarTitle`).
 *
 * The arrow performs `navigate.back`: in the contract `showBack` is a flag without an action, so
 * the reader defines «back» by the action a button performs, with the same degradation. The bar has
 * no background of its own: it is the style of the node, drawn by the box of the node.
 */
@Composable
internal fun AppBarWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val children = node.children
    val leadingNode = AppBarRules.leadingIndex(children)?.let { children[it] }
    val shown = AppBarRules.leadingShown(leadingNode?.let { context.isShown(it) }, node.props["showBack"]?.bool == true)
    val actionNodes = AppBarRules.actionIndexes(children).map { children[it] }.filter { context.isShown(it) }.take(AppBarRules.MAX_DRAWN_ACTIONS)
    val side = theme.spacing[AppBarRules.BACK_SIDE_TOKEN] ?: 24.0
    val gap = theme.spacing[AppBarRules.GAP_TOKEN] ?: 8.0
    val actionGap = theme.spacing[AppBarRules.ACTION_GAP_TOKEN] ?: 4.0
    // What the children read: not the inset of the bar itself, not its scrolled state.
    val child = context.copy(barInset = 0.0, barScrolled = false, contentMaxWidth = null, barNav = null)
    val titleHidden = AppBarRules.titleShownOnlyWhenScrolled(node.props) && !context.barScrolled
    // The items of the navigation bar that stands in this bar (`widePlacement`): the first five shown ones, in one block with the actions.
    val navItems = context.barNav?.children.orEmpty().filter { it.type == "NavItem" && context.isShown(it) }.take(NavBarRules.MAX_ITEMS)
    val navMetrics = if (navItems.isEmpty()) null else wideNavMetrics(theme)
    val navContext = navItemsContext(context, child)
    val policy = remember(side, gap, actionGap, node.props["titleAlign"], navMetrics) { AppBarPolicy(side, gap, actionGap, node.props["titleAlign"], navMetrics) }

    Layout(
        {
            when (shown) {
                AppBarRules.LeadingShown.Node -> if (leadingNode != null) {
                    NodeView(naturalWidth(leadingNode), child, Placement.Natural, call.depth + 1, "AppBar", Modifier.then(PartData(AppBarPart.LeadingNode)))
                }
                AppBarRules.LeadingShown.Back -> BackArrow(side, child, node, Modifier.then(PartData(AppBarPart.Back)))
                AppBarRules.LeadingShown.None -> Unit
            }
            TitleBlock(node, child, Modifier.then(PartData(AppBarPart.Title)).then(if (titleHidden) Modifier.alpha(0f) else Modifier))
            for (action in actionNodes) NodeView(action, child, Placement.Natural, call.depth + 1, "AppBar", Modifier.then(PartData(AppBarPart.Action)))
            for (item in navItems) NodeView(item, navContext, Placement.Natural, call.depth + 1, "NavBar", Modifier.then(PartData(AppBarPart.NavItem)))
        },
        Modifier.contentColumn(context.contentMaxWidth).fillMaxWidth().then(if (navItems.isEmpty()) Modifier else Modifier.clipToBounds()),
        policy,
    )
}

/** The context of the navigation items in this bar: the box of the navigation bar is not drawn, but the colour and the font it names pass to its items. */
private fun navItemsContext(context: com.hotlayout.render.RenderContext, child: com.hotlayout.render.RenderContext): com.hotlayout.render.RenderContext {
    val nav = context.barNav ?: return child
    val style = com.hotlayout.theme.NodeStyle.effective(nav, context.scope)
    return child.copy(navAtTop = true, inherited = com.hotlayout.theme.NodeStyle.inherited(context.inherited, style, context.resolver))
}

/** The width of the node of the leading zone is natural, even for a button that would fill: «full width» has nothing to mean in a zone as wide as the node. */
private fun naturalWidth(leading: DocumentNode): DocumentNode {
    if (leading.type != "Button" || leading.props["fullWidth"]?.bool == false) return leading
    return leading.copy(props = leading.props.with("fullWidth", JsonValue.Bool(false)))
}

/** The arrow: a square of the `xl` step with the `chevron` glyph of size `md` turned half a turn; the set mirrors the chevron itself in right-to-left text, so the arrow points toward where the person came from in both directions. */
@Composable
private fun BackArrow(side: Double, context: com.hotlayout.render.RenderContext, node: DocumentNode, modifier: Modifier) {
    val (glyph, box) = iconWordBox("md", context.theme)
    val press = { context.press(JsonObject.of("type" to "navigate.back"), node) }
    Box(
        modifier
            .size(side.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = context.backLabel
                onClick { press(); true }
            }
            .pressInput(true, false, { press() }, {}) {},
        contentAlignment = Alignment.Center,
    ) {
        IconGlyph("chevron", glyph, box.first, box.second, context.inherited.color.color, context.rightToLeft, Modifier.rotate(180f))
    }
}

@Composable
private fun TitleBlock(node: DocumentNode, context: com.hotlayout.render.RenderContext, modifier: Modifier) {
    val theme = context.theme
    val start = AppBarRules.titleAlign(node.props["titleAlign"]) == AppBarRules.TitleAlign.Start
    val title = context.text(node, "title")
    val subtitle = if (node.props["subtitle"] == null) "" else context.text(node, "subtitle")
    val titleStyle = theme.typography["titleM"] ?: context.inherited.typography
    val subtitleStyle = theme.typography["bodyS"] ?: context.inherited.typography
    val named = context.inherited.colorRef
    val subtitleColor = named?.let { context.resolver.color(PartStyleRules.withOpacity(it, ListTileRules.SECONDARY_OPACITY)) } ?: theme.colorValue("muted") ?: context.inherited.color
    val align = if (start) TextAlign.Start else TextAlign.Center
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy((theme.spacing[AppBarRules.SUBTITLE_GAP_TOKEN] ?: 4.0).dp),
        horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        StyledText(title, titleStyle, context.inherited.color, context, Modifier.fillMaxWidth().semantics { heading() }, align = align, maxLines = 1)
        if (subtitle.isNotEmpty()) StyledText(subtitle, subtitleStyle, subtitleColor, context, Modifier.fillMaxWidth(), align = align, maxLines = 1)
    }
}

/** The row of the app bar: the leading zone, the title block, the actions. The formula is [AppBarRules.zones]; this measures the children and places them. Coordinates are logical: `placeRelative` mirrors them in right-to-left text. */
private class AppBarPolicy(val backSide: Double, val gap: Double, val actionGap: Double, val titleAlign: JsonValue?, val nav: WideChrome.NavMetrics?) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val d = density
        val leading = measurables.firstOrNull { it.part == AppBarPart.Back || it.part == AppBarPart.LeadingNode }
        val isNode = leading?.part == AppBarPart.LeadingNode
        val title = measurables.firstOrNull { it.part == AppBarPart.Title }
        val actions = measurables.filter { it.part == AppBarPart.Action }
        val navItems = if (nav == null) emptyList() else measurables.filter { it.part == AppBarPart.NavItem }
        fun natural(item: Measurable?): Double = item?.maxIntrinsicWidth(Constraints.Infinity)?.let { it / d.toDouble() } ?: 0.0
        val actionSides = actions.map { natural(it) }
        fun input(width: Double) =
            AppBarRules.ZonesInput(width, leading != null && !isNode, backSide, actionSides, gap, actionGap, natural(title), if (isNode) natural(leading) else null, titleAlign)
        // With items the block «items, gap, actions» is one trailing zone and the labels are decided by the natural widths (never by the look chosen).
        fun wideFor(width: Double): WideChrome.WideAppBarZones? =
            if (nav == null || navItems.isEmpty()) null
            else WideChrome.appBarZones(WideChrome.WideAppBarInput(input(width), navItems.map { maxOf(0.0, natural(it) - nav.fixed) }, nav))
        fun zonesFor(width: Double) = wideFor(width)?.zones ?: AppBarRules.zones(input(width))
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            val wide = zonesFor(Double.POSITIVE_INFINITY)
            ((wide.leading + natural(title) + wide.trailing) * d).roundToInt()
        }
        val zones = zonesFor(widthPx / d.toDouble())
        val leadingPlaced = leading?.measure(Constraints(maxWidth = ((if (isNode) zones.leadingWidth else backSide) * d).roundToInt()))
        val titleWidth = (zones.titleWidth * d).roundToInt()
        val titlePlaced = title?.measure(Constraints(minWidth = titleWidth, maxWidth = titleWidth))
        val actionPlaced = actions.map { it.measure(Constraints()) }
        val wide = wideFor(widthPx / d.toDouble())
        // An item stands in the box of the rule; a box narrower than its content (icons alone) clips the label away.
        val navPlaced = navItems.mapIndexed { index, item ->
            val w = ((wide?.navItemWidths?.getOrNull(index) ?: 0.0) * d).roundToInt()
            item.measure(Constraints(minWidth = w, maxWidth = w))
        }
        val height = (listOfNotNull(leadingPlaced, titlePlaced) + actionPlaced + navPlaced).maxOfOrNull { it.height } ?: 0
        return layout(widthPx, constraints.constrainHeight(height)) {
            fun put(placeable: Placeable?, start: Double) = placeable?.placeRelative((start * d).roundToInt(), (height - placeable.height) / 2)
            put(leadingPlaced, 0.0)
            put(titlePlaced, zones.titleStart)
            actionPlaced.forEachIndexed { index, placeable -> put(placeable, zones.actionStarts[index]) }
            navPlaced.forEachIndexed { index, placeable -> put(placeable, wide?.navItemStarts?.getOrNull(index) ?: 0.0) }
        }
    }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + (gap * density * 2).roundToInt()

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        maxIntrinsicWidth(measurables, height)

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        maxIntrinsicHeight(measurables, width)
}
