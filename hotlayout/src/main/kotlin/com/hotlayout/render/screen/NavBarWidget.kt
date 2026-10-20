package com.hotlayout.render.screen

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.hotlayout.JsonObject
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.adaptive.contentColumn
import com.hotlayout.render.color
import com.hotlayout.render.dp
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.interaction.pressPose
import com.hotlayout.render.interaction.PressFeedbackRules
import com.hotlayout.render.roundToInt
import com.hotlayout.render.signedDp
import com.hotlayout.render.widgets.ButtonGeometry
import com.hotlayout.render.widgets.IconBesideTextRules
import com.hotlayout.render.widgets.IconGlyph
import com.hotlayout.render.widgets.ListTileRules
import com.hotlayout.render.widgets.PartStyleRules
import com.hotlayout.render.widgets.iconWordBox

/**
 * `NavBar`: the items side by side in equal shares, on the `surface` of the theme with a line on
 * top, unless the node names its own background or border (`x-style.layout.navBar`). The safe
 * inset under the items belongs to the bar unless it floats.
 */
@Composable
internal fun NavBarWidget(call: WidgetCall) {
    if (call.context.navAtTop) {
        com.hotlayout.render.adaptive.OwnNavBar(call)
        return
    }
    val context = call.context
    val theme = context.theme
    val items = call.node.children.filter { it.type == "NavItem" && context.isShown(it) }.take(NavBarRules.MAX_ITEMS)
    val line = if (call.style["border"] == null) 1.0 else 0.0
    val surface = if (call.style["background"] == null) theme.colorValue("surface")?.color ?: Color.White else Color.Transparent
    val borderColor = theme.colorValue("border")?.color ?: Color(0xFFE6E6EB)
    val child = context.copy(navLine = line, navInset = 0.0)
    Column(
        Modifier.fillMaxWidth()
            .background(surface)
            .drawBehind { if (line > 0) drawRect(borderColor, size = Size(size.width, (line * density).toFloat())) },
    ) {
        EqualShares(Modifier.contentColumn(context.contentMaxWidth)) {
            for (item in items) NodeView(item, child, Placement.Stretched, call.depth + 1, "NavBar")
        }
        if (context.navInset > 0) Spacer(Modifier.height(context.navInset.dp))
    }
}

/** The items of the bar: equal shares of its width, the first at the start of the reading direction. A share is not rounded (`NavBarRules.shares`); pixels are shared so that the shares fill the bar exactly. */
@Composable
private fun EqualShares(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables: List<Measurable>, constraints: Constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val shares = NavBarRules.shares(width.toDouble(), measurables.map { true }, false)
        val placeables = measurables.mapIndexed { index, item ->
            val start = shares[index]?.start ?: 0.0
            val end = start + (shares[index]?.width ?: 0.0)
            val share = end.roundToInt() - start.roundToInt()
            item.measure(Constraints(minWidth = share, maxWidth = share))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, constraints.constrainHeight(height)) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative((shares[index]?.start ?: 0.0).roundToInt(), 0) }
        }
    }
}

/**
 * `NavItem`: the icon above the label, the whole share is the touch area. The item that switches
 * to the shown screen is active (the colour `primary`), the others are `muted`; a dot may sit on
 * the icon (`badgeWhen`).
 */
@Composable
internal fun NavItemWidget(call: WidgetCall) {
    if (call.context.navAtTop) {
        com.hotlayout.render.adaptive.TopNavItem(call)
        return
    }
    val node = call.node
    val context = call.context
    val theme = context.theme
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val active = NavBarRules.isActive(action, context.screenId)
    val named = context.inherited.colorRef
    val colorRef = if (active) named ?: com.hotlayout.JsonValue.Str("primary")
    else named?.let { PartStyleRules.withOpacity(it, ListTileRules.SECONDARY_OPACITY) } ?: com.hotlayout.JsonValue.Str("muted")
    val color = context.resolver.color(colorRef) ?: context.inherited.color
    val typography = theme.typography["bodyS"] ?: context.inherited.typography
    val label = context.text(node, "label")
    val icon = IconBesideTextRules.resolve(node.props, "icon", context.scope)
    val (glyph, word) = iconWordBox("md", theme)
    val block = theme.spacing["sm"] ?: 8.0
    val side = theme.spacing["xs"] ?: 4.0
    val name = ButtonGeometry.iconButtonName(label, icon.resolved)
    val showsDot = NavBarRules.badgeShown(node.props, context.scope)
    val press = { if (!active) context.press(action, node) }
    var pressed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = active
                contentDescription = name
                onClick { press(); true }
            }
            .pressInput(true, false, { press() }, {}) { pressed = it }
            .pressPose(PressFeedbackRules.kind(node), pressed)
            .padding(start = side.dp, top = (block + context.navLine).dp, end = side.dp, bottom = block.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((theme.spacing["xs"] ?: 4.0).dp),
    ) {
        Box(Modifier.size(word.first.dp, word.second.dp)) {
            IconGlyph(icon.name, glyph, word.first, word.second, color.color, context.rightToLeft)
            NavBadgeDot(showsDot, glyph, word.second, (theme.colorValue("accent") ?: color).color, context.rightToLeft)
        }
        StyledText(label, typography, color, context, Modifier.fillMaxWidth(), align = TextAlign.Center, maxLines = 1)
    }
}

/**
 * The dot on the icon of an item: 8 points, `accent`, its centre on the top inline-end corner of the
 * glyph, not clipped by the item. It appears with a short flash and fades out when its condition
 * changes its answer on a shown screen; the first show and «reduce motion» are not moved.
 */
@Composable
internal fun NavBadgeDot(shown: Boolean, glyph: Double, boxHeight: Double, color: Color, rightToLeft: Boolean) {
    val resolver = LocalContext.current.contentResolver
    val reduced = remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    var seen by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(shown) }
    var change by remember { mutableStateOf(NavBarRules.BadgeChange.None) }
    var elapsed by remember { mutableStateOf(0.0) }
    LaunchedEffect(shown) {
        val moving = NavBarRules.badgeChange(seen, before, shown, reduced)
        seen = true
        before = shown
        change = moving
        if (moving == NavBarRules.BadgeChange.None) return@LaunchedEffect
        val duration = if (moving == NavBarRules.BadgeChange.Appear) NavBarRules.APPEAR_MS else NavBarRules.DISAPPEAR_MS
        val start = androidx.compose.runtime.withFrameMillis { it }
        elapsed = 0.0
        while (elapsed < duration) elapsed = (androidx.compose.runtime.withFrameMillis { it } - start).toDouble()
        change = NavBarRules.BadgeChange.None
    }
    if (!shown && change != NavBarRules.BadgeChange.Disappear) return
    val dot = NavBarRules.badgeBox(0.0, (boxHeight - glyph) / 2, glyph, rightToLeft)
    val scale = if (change == NavBarRules.BadgeChange.Appear) NavBarRules.appearScale(elapsed) else 1.0
    val opacity = if (change == NavBarRules.BadgeChange.Disappear) NavBarRules.disappearOpacity(elapsed) else 1.0
    Box(
        Modifier.absoluteOffset(dot.left.signedDp, dot.top.signedDp).size(dot.size.dp)
            .scale(scale.toFloat()).alpha(opacity.toFloat()).background(color, CircleShape)
            .testTag("nav-badge-dot"),
    )
}
