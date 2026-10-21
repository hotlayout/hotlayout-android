package com.hotlayout.render.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.data.Conditions
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.acts
import com.hotlayout.render.color
import com.hotlayout.render.dp
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.text.BindableText
import com.hotlayout.text.ShownText
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue

/** The side of a thumbnail in a row, in `xl` units of the theme. */
private const val THUMBNAIL_SCALE = 2.0

/** The shown text of a string that lies in a slot of a node (`trailing.text`, `trailing.caption`). */
internal fun RenderContext.slotText(node: DocumentNode, path: String): String {
    val parts = path.split('.')
    val value = node.props[parts.first()]?.get(parts.drop(1).joinToString("."))
    return ShownText.of(BindableText.text(value, language(node, path), textEnvironment, sink, "${location(node)}: $path"))
}

/**
 * `ListTile`: a leading slot, the texts and a trailing slot in one row. One gap, `sm`, on both
 * sides; the trailing column never shrinks, the texts give way.
 */
@Composable
internal fun ListTileWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val gap = theme.spacing["sm"] ?: 8.0
    val title = context.text(node, "title")
    val subtitle = if (node.props["subtitle"] == null) "" else context.text(node, "subtitle")
    val trailingKind = node.props["trailing"]?.get("kind")?.string ?: "chevron"
    val action = node.props["action"]?.obj?.takeIf(::acts)
    val choice = context.choice
    val option = node.props["trailing"]?.get("optionValue")?.string

    var modifier = if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier
    modifier = when {
        // An option row belongs to the choice: its press selects, and an `action` of the row is not run.
        trailingKind == "radio" && choice != null && option != null ->
            modifier.semantics(mergeDescendants = true) {
                testTag = "node:${node.id}"
                role = Role.RadioButton
                selected = choice.selected == option
                onClick { choice.select(option); true }
            }.pressInput(true, false, { choice.select(option) }, {}) {}
        // The press, the long press, the pose and the name of a row with an action are the card's (`pressableCard`, set by the node).
        else -> modifier.semantics(mergeDescendants = true) {}
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
        LeadingSlot(call)
        Texts(call, title, subtitle, Modifier.weight(1f))
        TrailingSlot(call, trailingKind)
    }
}

private fun RenderContext.colorOf(ref: JsonValue): Rgba = resolver.color(ref) ?: inherited.color

private fun RenderContext.typographyOf(ref: JsonValue, fallback: String): TextStyleValue =
    resolver.typography(ref) ?: theme.typography[fallback] ?: inherited.typography

@Composable
private fun Texts(call: WidgetCall, title: String, subtitle: String, modifier: Modifier) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val named = ListTileRules.named(context)
    val subtitleColor = if (named.named) PartStyleRules.withOpacity(named.ref, ListTileRules.SECONDARY_OPACITY) else JsonValue.Str("muted")
    Column(modifier) {
        StyledText(
            title, theme.typography["titleS"] ?: context.inherited.typography, context.inherited.color, context,
            maxLines = ListTileRules.lineLimit(node.props["titleMaxLines"]) ?: Int.MAX_VALUE,
        )
        if (subtitle.isNotEmpty()) {
            StyledText(
                subtitle, theme.typography["bodyS"] ?: context.inherited.typography, context.colorOf(subtitleColor), context,
                maxLines = ListTileRules.lineLimit(node.props["subtitleMaxLines"]) ?: Int.MAX_VALUE,
            )
        }
    }
}

@Composable
private fun LeadingSlot(call: WidgetCall) {
    val context = call.context
    val theme = context.theme
    val slot = call.node.props["leading"] ?: JsonValue.of(mapOf("kind" to "icon", "icon" to "chevron"))
    when (slot["kind"]?.string) {
        "icon" -> LeadingIcon(call, slot)
        "image" -> {
            val side = THUMBNAIL_SCALE * (theme.spacing["xl"] ?: 32.0)
            val placeholder = theme.colorValue("muted")?.withOpacity(0.15) ?: Rgba(0.5, 0.5, 0.5, 0.15)
            RemoteImage(
                imageAddress(slot["source"], context), "cover", placeholder,
                Modifier.size(side.dp).clip(RoundedCornerShape((theme.radius["sm"] ?: 8.0).dp)).clearAndSetSemantics {},
                context.images, context.sink,
            )
        }
        else -> Unit
    }
}

@Composable
private fun LeadingIcon(call: WidgetCall, slot: JsonValue) {
    val context = call.context
    val (fontSize, word) = iconWordBox("md", context.theme)
    val name = IconBesideTextRules.resolve(JsonObject.of("icon" to slot["icon"]), "icon", emptyMap()).name
    val color = context.colorOf(ListTileRules.leadingIconColor(slot, ListTileRules.named(context))).color
    val tile = ListTileRules.tile(slot)
    if (tile == null) {
        IconGlyph(name, fontSize, fontSize, word.second, color, context.rightToLeft)
    } else {
        val radius = context.resolver.radius(tile.radius) ?: context.theme.radius["sm"] ?: 8.0
        Box(
            Modifier.size(tile.size.dp).clip(RoundedCornerShape(radius.dp)).background(context.colorOf(tile.color).color),
            contentAlignment = Alignment.Center,
        ) { IconGlyph(name, fontSize, fontSize, word.second, color, context.rightToLeft) }
    }
}

@Composable
private fun TrailingSlot(call: WidgetCall, kind: String) {
    val context = call.context
    val theme = context.theme
    val slot = call.node.props["trailing"] ?: JsonValue.of(mapOf("kind" to "chevron"))
    when (kind) {
        "text" -> TrailingText(call, slot)
        "radio" -> {
            val size = iconWordBox("md", theme).first
            val option = slot["optionValue"]?.string
            val selected = option != null && call.context.choice?.selected == option
            RadioMark(
                selected, size,
                (theme.colorValue("primary") ?: Rgba(0.2, 0.4, 1.0)).color,
                (theme.colorValue("muted") ?: Rgba(0.5, 0.5, 0.5)).color,
            )
        }
        "none" -> Unit
        else -> {
            val (fontSize, word) = iconWordBox("md", theme)
            val named = ListTileRules.named(context)
            val chevron = if (named.named) PartStyleRules.withOpacity(named.ref, ListTileRules.SECONDARY_OPACITY) else JsonValue.Str("muted")
            IconGlyph("chevron", fontSize, fontSize, word.second, context.colorOf(chevron).color, context.rightToLeft)
        }
    }
}

/** The trailing text with its icon beside it, over the caption; both stand at the end. */
@Composable
private fun TrailingText(call: WidgetCall, slot: JsonValue) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val named = ListTileRules.named(context)
    val isMet: (JsonValue) -> Boolean = { Conditions.holds(it, context.scope) }
    val look = ListTileRules.trailingTextLook(slot, named, isMet)
    val textColor = context.colorOf(look.color)
    val icon = IconBesideTextRules.resolve(slot.obj ?: JsonObject(), "icon", context.scope)
    // The icon takes the small step of the slot (`listTileIconSizes.trailingIcon`), not the size of the text.
    val iconTypography = theme.typography["bodyS"] ?: TextStyleValue(12.0, 1.33, 400)
    val caption = if (slot["caption"] == null) "" else context.slotText(node, "trailing.caption")
    Column(horizontalAlignment = Alignment.End) {
        IconBesideText(icon, "end", iconTypography, theme.spacing[IconBesideTextRules.GAP_TOKEN] ?: 4.0, textColor.color, context) {
            StyledText(context.slotText(node, "trailing.text"), context.typographyOf(look.typography, "bodyM"), textColor, context, maxLines = 1, softWrap = false)
        }
        if (caption.isNotEmpty()) {
            val captionLook = ListTileRules.captionLook(slot, named, isMet)
            StyledText(
                caption, context.typographyOf(captionLook.typography, "bodyS"), context.colorOf(captionLook.color), context,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

/** The mark of an option row: a ring, with a dot inside when chosen. */
@Composable
private fun RadioMark(selected: Boolean, size: Double, selectedColor: Color, mutedColor: Color) {
    Canvas(Modifier.size(size.dp).clearAndSetSemantics {}) {
        val stroke = 2.dp.toPx()
        val radius = (this.size.minDimension - stroke) / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(if (selected) selectedColor else mutedColor, radius, center, style = Stroke(stroke))
        if (selected) drawCircle(selectedColor, radius / 2, center)
    }
}
