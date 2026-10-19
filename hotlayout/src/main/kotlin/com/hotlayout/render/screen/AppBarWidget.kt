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
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.Placement
import com.hotlayout.render.NodeView
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.widgets.IconGlyph
import com.hotlayout.render.widgets.iconWordBox
import com.hotlayout.render.widgets.ListTileRules
import com.hotlayout.render.widgets.PartStyleRules
import kotlin.math.roundToInt

/** Which part of the app bar a child of the row is. */
internal enum class AppBarPart { Back, LeadingNode, Title, Action }

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
    val child = context.copy(barInset = 0.0, barScrolled = false)
    val titleHidden = AppBarRules.titleShownOnlyWhenScrolled(node.props) && !context.barScrolled
    val policy = remember(side, gap, actionGap, node.props["titleAlign"]) { AppBarPolicy(side, gap, actionGap, node.props["titleAlign"]) }

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
        },
        Modifier.fillMaxWidth(),
        policy,
    )
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
private class AppBarPolicy(val backSide: Double, val gap: Double, val actionGap: Double, val titleAlign: JsonValue?) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val d = density
        val leading = measurables.firstOrNull { it.part == AppBarPart.Back || it.part == AppBarPart.LeadingNode }
        val isNode = leading?.part == AppBarPart.LeadingNode
        val title = measurables.firstOrNull { it.part == AppBarPart.Title }
        val actions = measurables.filter { it.part == AppBarPart.Action }
        fun natural(item: Measurable?): Double = item?.maxIntrinsicWidth(Constraints.Infinity)?.let { it / d.toDouble() } ?: 0.0
        val actionSides = actions.map { natural(it) }
        fun zonesFor(width: Double) = AppBarRules.zones(
            AppBarRules.ZonesInput(width, leading != null && !isNode, backSide, actionSides, gap, actionGap, natural(title), if (isNode) natural(leading) else null, titleAlign),
        )
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            val wide = zonesFor(Double.POSITIVE_INFINITY)
            ((wide.leading + natural(title) + wide.trailing) * d).roundToInt()
        }
        val zones = zonesFor(widthPx / d.toDouble())
        val leadingPlaced = leading?.measure(Constraints(maxWidth = ((if (isNode) zones.leadingWidth else backSide) * d).roundToInt()))
        val titleWidth = (zones.titleWidth * d).roundToInt()
        val titlePlaced = title?.measure(Constraints(minWidth = titleWidth, maxWidth = titleWidth))
        val actionPlaced = actions.map { it.measure(Constraints()) }
        val height = (listOfNotNull(leadingPlaced, titlePlaced) + actionPlaced).maxOfOrNull { it.height } ?: 0
        return layout(widthPx, constraints.constrainHeight(height)) {
            fun put(placeable: Placeable?, start: Double) = placeable?.placeRelative((start * d).roundToInt(), (height - placeable.height) / 2)
            put(leadingPlaced, 0.0)
            put(titlePlaced, zones.titleStart)
            actionPlaced.forEachIndexed { index, placeable -> put(placeable, zones.actionStarts[index]) }
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
