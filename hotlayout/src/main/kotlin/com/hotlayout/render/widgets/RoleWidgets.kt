package com.hotlayout.render.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.actions.PressRules
import com.hotlayout.render.RenderContext
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.backgroundOf
import com.hotlayout.render.color
import com.hotlayout.render.cssShadows
import com.hotlayout.render.dp
import com.hotlayout.render.interaction.PressFeedbackRules
import com.hotlayout.render.interaction.controlSemantics
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.interaction.pressPose
import com.hotlayout.render.interaction.rememberWriteControl
import com.hotlayout.render.paddingModifier
import com.hotlayout.render.paint.DashedBorderRules
import com.hotlayout.render.paint.dashedBorder
import com.hotlayout.render.paint.glass
import com.hotlayout.render.shape
import com.hotlayout.theme.BackgroundValue
import com.hotlayout.theme.Corners
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.ShadowLayer
import com.hotlayout.theme.ShadowTint
import com.hotlayout.theme.Sides
import com.hotlayout.theme.TextStyleValue

/**
 * The surface of a widget that takes its look from a role of the theme (`roles.buttonVariant`,
 * `roles.badgeTone`): every slot the node's own style names replaces the slot of the role.
 */
internal data class RoleSurface(
    val background: Rgba?,
    /** The node's own gradient background: it lies over the colour of the role. */
    val gradient: BackgroundValue.Gradient?,
    val foreground: Rgba,
    val borderColor: Rgba?,
    val borderWidth: Double,
    val borderDashed: Boolean,
    val typography: TextStyleValue?,
    val padding: Sides?,
    val corners: Corners?,
    val shadow: List<ShadowLayer>,
    /** `style.backdropBlur` (glass): the blur under the surface; it lies on the surface of the role. */
    val backdropBlur: Int? = null,
) {
    companion object {
        /** A role border is one point wide. */
        const val ROLE_BORDER_WIDTH = 1.0

        fun resolve(group: String, name: String, style: JsonObject, context: RenderContext): RoleSurface {
            val theme = context.theme
            val resolver = context.resolver
            val role = theme.roles[group]?.get(name)
            val ownBackground = if (style["background"]?.get("kind")?.string == "color") resolver.color(style["background"]?.get("color")) else null
            val look = resolver.box(style, context.scope)
            val ownBorder = style["border"]?.obj
            return RoleSurface(
                background = ownBackground ?: role?.background?.let(theme::colorValue),
                gradient = look.background as? BackgroundValue.Gradient,
                foreground = resolver.color(style["color"]) ?: role?.foreground?.let(theme::colorValue) ?: context.inherited.color,
                borderColor = ownBorder?.let { resolver.color(it["color"]) } ?: role?.border?.let(theme::colorValue),
                borderWidth = ownBorder?.get("width")?.number ?: if (role?.border != null) ROLE_BORDER_WIDTH else 0.0,
                borderDashed = DashedBorderRules.isDashed(style["border"]),
                typography = resolver.typography(style["typography"]),
                padding = resolver.sides(style["padding"]),
                corners = resolver.corners(style["radius"]),
                shadow = ShadowTint.apply(resolver.shadow(style["shadow"]).orEmpty(), style, resolver),
                backdropBlur = look.backdropBlur,
            )
        }
    }
}

/** Draws the box of a role surface around the content: shadow, shape, fill, border, padding. */
@androidx.compose.runtime.Composable
internal fun Modifier.roleBox(surface: RoleSurface, shape: Shape, padding: Sides, rightToLeft: Boolean): Modifier {
    var modifier = this.glass(surface.backdropBlur, shape)
    if (surface.shadow.isNotEmpty()) modifier = modifier.cssShadows(surface.shadow, shape)
    modifier = modifier.clip(shape)
    modifier = modifier.backgroundOf(surface.gradient ?: surface.background?.let(BackgroundValue::Color), shape, rightToLeft)
    val border = surface.borderColor
    if (border != null && surface.borderWidth > 0) {
        modifier = if (surface.borderDashed) modifier.dashedBorder(surface.borderWidth, border.color, surface.corners) else modifier.border(surface.borderWidth.dp, border.color, shape)
    }
    return modifier.then(padding.paddingModifier(surface.borderWidth))
}

/** `Button` and `IconButton` sizes (`x-style.layout.buttonSizes`, `iconButton`). */
internal object ButtonGeometry {
    data class Size(val paddingBlock: Double, val paddingInline: Double, val typography: String)

    private val sizes = mapOf(
        "sm" to Triple("xs", "md", "bodyM"),
        "md" to Triple("sm", "md", "titleS"),
        "lg" to Triple("md", "lg", "titleM"),
    )

    /** The size word, or `md` for no word and for a word of a newer minor. */
    fun size(word: String?, theme: HotlayoutTheme): Size {
        val entry = sizes[word ?: "md"] ?: sizes.getValue("md")
        return Size(theme.spacing[entry.first] ?: 8.0, theme.spacing[entry.second] ?: 12.0, entry.third)
    }

    /** The height of a button: the line of its text, the vertical padding and the border twice. */
    fun height(size: Size, theme: HotlayoutTheme, border: Double): Double =
        (theme.typography[size.typography]?.lineBox ?: 24.0) + 2 * size.paddingBlock + 2 * border

    data class IconButton(val side: Double, val glyph: Double, val padding: Double)

    /** The icon button: a square whose content box is the font line (or `glyphSize`), padded by the vertical padding. */
    fun iconButton(word: String?, glyphSize: Double?, theme: HotlayoutTheme, border: Double): IconButton {
        val size = size(word, theme)
        val typography = theme.typography[size.typography] ?: TextStyleValue(16.0, 1.5, 400)
        val own = glyphSize?.coerceIn(8.0, 128.0)
        val content = own ?: typography.lineBox
        return IconButton(content + 2 * size.paddingBlock + 2 * border, own ?: typography.fontSize, size.paddingBlock)
    }

    /** The accessible name of an icon button: the label, else the name of the icon in words. */
    fun iconButtonName(label: String, iconName: JsonValue?): String {
        if (!com.hotlayout.text.TextHeard.isSilent(label)) return label
        return iconName?.string?.replace('-', ' ') ?: ""
    }
}

/**
 * `Button`: the surface of a role of the theme (`roles.buttonVariant`), each slot overridable by the
 * node's own style; padding and text style by size (`x-style.layout.buttonSizes`). A press goes to
 * the screen; the button is a control with its label as the accessible name.
 *
 * A disabled button (`disabledIf`, `requiresFilled`) stays in the order of the screen reader, is
 * drawn at half opacity and does nothing: no action, no analytics event, no request for haptic
 * feedback. With `confirm: 'swipe'` it is a track with a thumb ([SwipeConfirmButton]).
 */
@Composable
internal fun ButtonWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val size = ButtonGeometry.size(node.props["size"]?.string, theme)
    val surface = RoleSurface.resolve("buttonVariant", node.props["variant"]?.string ?: "primary", call.style, context)
    val typography = surface.typography ?: theme.typography[size.typography] ?: context.inherited.typography
    val padding = surface.padding ?: Sides(size.paddingBlock, size.paddingInline, size.paddingBlock, size.paddingInline)
    val corners = surface.corners ?: Corners.all(theme.radius["md"] ?: 12.0)
    val shape = corners.shape()
    val label = context.text(node, "label")
    val fullWidth = node.props["fullWidth"]?.bool ?: true
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val icon = IconBesideTextRules.resolve(node.props, "icon", context.scope)
    val gap = theme.spacing[IconBesideTextRules.GAP_TOKEN] ?: 4.0
    val disabled = context.isDisabled(node)
    val writeControl = rememberWriteControl(node, context, action)
    val stretch = fullWidth || call.placement.stretchWidth
    if (SwipeConfirmRules.confirmsBySwipe(node.props)) {
        val role = theme.roles["buttonVariant"]?.get(node.props["variant"]?.string ?: "primary")
        SwipeConfirmButton(
            node, context,
            SwipeConfirmLook(
                label = label, typography = typography, theme = theme, trackFill = surface.background, labelColor = surface.foreground,
                thumbFill = role?.background?.let(theme::colorValue) ?: theme.colorValue("primary") ?: surface.foreground,
                glyphColor = role?.foreground?.let(theme::colorValue) ?: theme.colorValue("onPrimary") ?: surface.foreground,
                borderColor = surface.borderColor, borderWidth = surface.borderWidth, dashedBorder = surface.borderDashed,
                padding = padding, corners = corners, shadow = surface.shadow, fullWidth = stretch, icon = icon.name,
            ),
            disabled,
            Modifier.then(if (disabled) Modifier.alpha(PressRules.DISABLED_OPACITY.toFloat()) else Modifier),
        )
        return
    }
    var pressed by remember { mutableStateOf(false) }
    val box = Modifier
        .then(if (stretch) Modifier.fillMaxWidth() else Modifier)
        .then(if (disabled) Modifier.alpha(PressRules.DISABLED_OPACITY.toFloat()) else Modifier)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            controlSemantics(node, writeControl)
            if (label.isNotEmpty()) contentDescription = label
            if (disabled) disabled()
            onClick { if (!disabled) context.press(action, node); true }
        }
        .pressInput(true, false, { if (!disabled) context.press(action, node) }, {}) { pressed = it }
        .pressPose(PressFeedbackRules.kind(node, disabled), pressed)
        .roleBox(surface, shape, padding, context.rightToLeft)
    Box(box, contentAlignment = Alignment.Center) {
        IconBesideText(icon, node.props["iconPosition"]?.string, typography, gap, surface.foreground.color, context) {
            if (label.isNotEmpty()) StyledText(label, typography, surface.foreground, context, align = TextAlign.Center)
        }
    }
}

/**
 * `Badge` (and the «chip» of layouts: a badge with an icon and its own style): a pill of a tone of
 * the theme (`roles.badgeTone`) with a caption. A badge is text, not a control.
 */
@Composable
internal fun BadgeWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val theme = context.theme
    val surface = RoleSurface.resolve("badgeTone", node.props["tone"]?.string ?: "neutral", call.style, context)
    val typography = surface.typography ?: theme.typography["caption"] ?: context.inherited.typography
    val block = theme.spacing["xs"] ?: 4.0
    val inline = theme.spacing["sm"] ?: 8.0
    val padding = surface.padding ?: Sides(block, inline, block, inline)
    val shape = (surface.corners ?: Corners.all(theme.radius["pill"] ?: 72.0)).shape()
    val icon = IconBesideTextRules.resolve(node.props, "icon", context.scope)
    val label = context.text(node, "label")
    Box(
        Modifier
            .clearAndSetSemantics {
                text = AnnotatedString(label)
                contentDescription = label
            }
            .roleBox(surface, shape, padding, context.rightToLeft),
    ) {
        IconBesideText(icon, node.props["iconPosition"]?.string, typography, theme.spacing[IconBesideTextRules.GAP_TOKEN] ?: 4.0, surface.foreground.color, context) {
            StyledText(label, typography, surface.foreground, context, maxLines = 1)
        }
    }
}

/** `Spacer`: `horizontal` adds height, `vertical` adds width and is at least the `lg` step tall. */
@Composable
internal fun SpacerWidget(call: WidgetCall) {
    val context = call.context
    val size = context.resolver.spacing(call.node.props["size"] ?: JsonValue.Str("md")) ?: 12.0
    if (call.node.props["orientation"]?.string == "vertical") {
        Box(Modifier.size(size.dp, (context.theme.spacing["lg"] ?: 16.0).dp))
    } else {
        Box(Modifier.size(0.dp, size.dp))
    }
}

/** `Divider`: a one-point line in its own colour (`props.color`, not the inherited text colour) with its spacing. */
@Composable
internal fun DividerWidget(call: WidgetCall) {
    val context = call.context
    val node = call.node
    val shade = context.resolver.color(node.props["color"] ?: JsonValue.Str("border")) ?: context.theme.colorValue("border") ?: Rgba(0.9, 0.9, 0.92)
    val spacing = (context.resolver.spacing(node.props["spacing"] ?: JsonValue.Str("sm")) ?: 8.0).dp
    if (node.props["orientation"]?.string == "vertical") {
        Box(Modifier.padding(horizontal = spacing).width(1.dp).height((context.theme.spacing["lg"] ?: 16.0).dp).background(shade.color))
    } else {
        Box(Modifier.padding(vertical = spacing).fillMaxWidth().height(1.dp).background(shade.color))
    }
}
