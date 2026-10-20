package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.hotlayout.JsonObject
import com.hotlayout.actions.PressRules
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.interaction.PressFeedbackRules
import com.hotlayout.render.interaction.controlSemantics
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.interaction.pressPose
import com.hotlayout.render.interaction.rememberWriteControl
import com.hotlayout.render.screen.GlyphGeometry
import com.hotlayout.render.shape
import com.hotlayout.theme.Corners
import com.hotlayout.theme.Sides

/**
 * `IconButton`: a round button with a picture and no visible text (`x-style.layout.iconButton`).
 * The side is the height of a button of the same size (28 / 40 / 48); the accessible name is the
 * label, or, with none, the name of the icon in words. It takes the group of roles of a button
 * (`secondary` unless the document says another) and is disabled like a button.
 */
@Composable
internal fun IconButtonWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val surface = RoleSurface.resolve("buttonVariant", node.props["variant"]?.string ?: "secondary", call.style, context)
    val size = ButtonGeometry.size(node.props["size"]?.string, theme)
    val typography = surface.typography ?: theme.typography[size.typography] ?: context.inherited.typography
    val padding = surface.padding ?: Sides(size.paddingBlock, size.paddingBlock, size.paddingBlock, size.paddingBlock)
    val shape = (surface.corners ?: Corners.all(theme.radius["pill"] ?: 72.0)).shape()
    val own = GlyphGeometry.own(node.props["glyphSize"]?.number)
    val glyph = own ?: typography.fontSize
    val content = own ?: typography.lineBox
    // A named side (`style.width` / `height`) is the box: it clamps the drawing, which stands in the
    // centre of the content box.
    val named = context.resolver.box(call.style, context.scope)
    val inline = padding.start + padding.end + 2 * surface.borderWidth
    val block = padding.top + padding.bottom + 2 * surface.borderWidth
    val boxWidth = named.width?.let { maxOf(0.0, it - inline) } ?: content
    val boxHeight = named.height?.let { maxOf(0.0, it - block) } ?: content
    val drawn = minOf(glyph, boxWidth, boxHeight)
    val icon = IconBesideTextRules.resolve(node.props, "icon", context.scope)
    val name = ButtonGeometry.iconButtonName(context.text(node, "label"), icon.resolved)
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val disabled = context.isDisabled(node)
    val writeControl = rememberWriteControl(node, context, action)
    var pressed by remember { mutableStateOf(false) }
    val modifier = Modifier
        .then(if (disabled) Modifier.alpha(PressRules.DISABLED_OPACITY.toFloat()) else Modifier)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            controlSemantics(node, writeControl)
            contentDescription = name
            if (disabled) disabled()
            onClick { if (!disabled) context.press(action, node); true }
        }
        .pressInput(true, false, { if (!disabled) context.press(action, node) }, {}) { pressed = it }
        .pressPose(PressFeedbackRules.kind(node, disabled), pressed)
        .roleBox(surface, shape, padding, context.rightToLeft)
    Box(modifier, contentAlignment = Alignment.Center) {
        IconGlyph(icon.name, drawn, boxWidth, boxHeight, surface.foreground.color, context.rightToLeft)
    }
}
