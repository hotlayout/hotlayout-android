package com.hotlayout.render.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import com.hotlayout.JsonValue
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.dp
import com.hotlayout.render.layout.ColumnWidget
import com.hotlayout.theme.NodeStyle

/**
 * `Accordion`: a title row with a chevron over a body that exists only while the block is open.
 * `initiallyExpanded` is the starting state only; after that the person decides. The card
 * (padding, radius, background) is the `style` of the node, not part of the widget.
 */
@Composable
internal fun AccordionWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    var expanded by remember(node.id) { mutableStateOf<Boolean?>(null) }
    val open = expanded ?: (node.props["initiallyExpanded"]?.bool ?: false)
    val gap = theme.spacing["sm"] ?: 8.0
    val title = context.text(node, "title")
    val chevronRef = context.inherited.colorRef?.let { PartStyleRules.withOpacity(it, ListTileRules.SECONDARY_OPACITY) } ?: JsonValue.Str("muted")
    val (fontSize, word) = iconWordBox("md", theme)
    // Collapsed the chevron points to the end of the line, open it points down; the drawing is
    // already mirrored in right-to-left text, so «down» turns the other way there.
    val turn = if (open) (if (context.rightToLeft) -90f else 90f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .semantics(mergeDescendants = true) { role = Role.Button; stateDescription = if (open) "expanded" else "collapsed" }
                .clickable(remember { MutableInteractionSource() }, null) { expanded = !open },
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledText(title, theme.typography["titleS"] ?: context.inherited.typography, context.inherited.color, context, Modifier.weight(1f))
            IconGlyph(
                "chevron", fontSize, fontSize, word.second,
                (context.resolver.color(chevronRef) ?: context.inherited.color).color, context.rightToLeft, Modifier.rotate(turn),
            )
        }
        if (open) {
            Box(Modifier.padding(top = gap.dp)) { ColumnWidget(node.children, gap, null, context, call.depth) }
        }
    }
}

/**
 * `Tabs`: a strip of titles over the panels, one of which is drawn. The title lies on the `Tab`
 * child; the strip scrolls and holds only visible tabs; the panel is the `Tab` node itself, so its
 * own style applies to it. The arrow keys move between titles (mirrored in right-to-left text).
 */
@Composable
internal fun TabsWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val declared = node.children.filter { it.type == "Tab" }
    val visible = declared.map { context.isShown(it) }
    val tabs = declared.filterIndexed { index, _ -> visible[index] }
    var selected by remember(node.id) { mutableStateOf<Int?>(null) }
    if (tabs.isEmpty()) return
    val initial = TabsRules.openTabIndex(node.props["initialTab"], visible)?.let { index -> tabs.indexOfFirst { it.id == declared[index].id } }?.takeIf { it >= 0 } ?: 0
    val open = (selected ?: initial).coerceIn(0, tabs.size - 1)
    val requesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    fun choose(index: Int) {
        selected = index
        runCatching { requesters[index].requestFocus() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((theme.spacing[TabsRules.PANEL_GAP_TOKEN] ?: 12.0).dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy((theme.spacing[TabsRules.TITLE_GAP_TOKEN] ?: 16.0).dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                TabTitle(call, tab, index == open, requesters[index], onSelect = { choose(index) }) { key ->
                    TabsRules.arrowTarget(open, tabs.size, key, context.rightToLeft)?.let { choose(it) } != null
                }
            }
        }
        // The panel is keyed by the tab, so the state of one panel never leaks into another.
        androidx.compose.runtime.key(tabs[open].id) { NodeView(tabs[open], context, Placement.Stretched, call.depth + 1, node.type) }
    }
}

/**
 * One title. The indicator is always there, transparent while the tab is closed, so the strip does
 * not change height when a tab opens. The colour is read in the context of the tab itself: the
 * title is its prop, although the strip draws it.
 */
@Composable
private fun TabTitle(call: WidgetCall, tab: com.hotlayout.model.DocumentNode, open: Boolean, focus: FocusRequester, onSelect: () -> Unit, onArrow: (String) -> Boolean) {
    val context = call.context
    val theme = context.theme
    val style = NodeStyle.effective(tab, context.scope)
    val look = NodeStyle.inherited(context.inherited, style, context.resolver)
    val named = look.colorRef
    val ref: JsonValue = if (open) named ?: JsonValue.Str("primary")
    else named?.let { PartStyleRules.withOpacity(it, ListTileRules.SECONDARY_OPACITY) } ?: JsonValue.Str("muted")
    val color = context.resolver.color(ref) ?: look.color
    val indicator = theme.spacing[TabsRules.INDICATOR_TOKEN] ?: 4.0
    val padding = theme.spacing[TabsRules.TITLE_PADDING_TOKEN] ?: 8.0
    val primary = theme.colorValue("primary")?.color ?: androidx.compose.ui.graphics.Color.Blue
    val title = context.text(tab, "title")
    // The strip scrolls sideways (unbounded width): the indicator takes the width of the title, measured, not the strip's.
    Column(
        Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> onArrow("right")
                    Key.DirectionLeft -> onArrow("left")
                    Key.MoveHome -> onArrow("home")
                    Key.MoveEnd -> onArrow("end")
                    else -> false
                }
            }
            .focusable()
            .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = open; contentDescription = title }
            .clickable(remember { MutableInteractionSource() }, null) { onSelect() },
    ) {
        Box(Modifier.padding(top = padding.dp, bottom = maxOf(0.0, padding - indicator).dp)) {
            StyledText(title, theme.typography["titleS"] ?: look.typography, color, context, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(indicator.dp).semantics { testTag = if (open) "tab-indicator:open" else "tab-indicator:closed" }.background(if (open) primary else androidx.compose.ui.graphics.Color.Transparent))
    }
}
