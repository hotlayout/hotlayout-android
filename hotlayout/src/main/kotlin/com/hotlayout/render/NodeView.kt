package com.hotlayout.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hotlayout.JsonObject
import com.hotlayout.actions.PressTarget
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.DocumentReader
import com.hotlayout.model.WidgetType
import com.hotlayout.render.interaction.ProvideSiblings
import com.hotlayout.render.interaction.pressableCard
import com.hotlayout.render.motion.LocalLayerTurn
import com.hotlayout.render.motion.NodeMotion
import com.hotlayout.render.motion.NodeMotionBox
import com.hotlayout.render.paint.ProvideGlass
import com.hotlayout.render.paint.glass
import com.hotlayout.render.paint.glassSource
import com.hotlayout.render.paint.rememberGlassSource
import com.hotlayout.render.screen.AppBarRules
import com.hotlayout.render.widgets.CarouselRules
import com.hotlayout.render.widgets.rememberBackgroundImage
import com.hotlayout.theme.BoxLook
import com.hotlayout.theme.Corners
import com.hotlayout.theme.NodeStyle

/** A container is a control when its action is not `none` or carries an analytics event. */
internal fun acts(action: JsonObject?): Boolean {
    if (action == null) return false
    val type = action["type"]?.string ?: return false
    return type != "none" || action["track"]?.obj != null
}

/**
 * One node of a screen: visibility, effective style, the inherited text look, the box, and the
 * widget itself (through [Widget], the one dispatcher by type).
 *
 * Shared by every widget family: change it only for what every node does.
 */
@Composable
internal fun NodeView(
    node: DocumentNode,
    context: RenderContext,
    placement: Placement = Placement.Stretched,
    depth: Int = 0,
    parentType: String? = null,
    modifier: Modifier = Modifier,
) {
    if (depth > DocumentReader.MAX_PARSE_DEPTH || !context.isShown(node)) return
    val style = effectiveStyle(node, context)
    // The place among siblings and the number of a list item are the node's own: descendants do not inherit them.
    val inner = context.copy(inherited = NodeStyle.inherited(context.inherited, style, context.resolver), listPosition = null, listNumber = null, cardWidth = null)
    var look = context.resolver.box(style, context.scope)
    // A card of a carousel with `perView` has the box width the carousel gives it, whatever it says about its own.
    context.cardWidth?.let { look = look.copy(width = it, minWidth = null, maxWidth = null) }
    // The padding of a carousel lies inside its scroll: the first card stands a padding from the edge.
    if (node.widget in WidgetType.carouselHosts && CarouselRules.of(node) != null) look = look.copy(padding = com.hotlayout.theme.Sides.Zero)
    // The inset of the status bar lies inside the box of the app bar, above its padding.
    if (node.widget == WidgetType.AppBar && context.barInset > 0) look = look.copy(padding = look.padding.copy(top = look.padding.top + context.barInset))
    val box = if (node.widget in WidgetType.roleSurfaces) outerOfRole(look) else look
    val press = PressTarget.press(node)
    val long = PressTarget.longPress(node)
    val outerBase = modifier.then(look.margin.paddingModifier())
    val named = box.width != null || box.height != null
    // A role surface (button) fills the minimum its box names: the limits of the box do not reach the surface inside.
    val fillsMinimum = node.widget in WidgetType.roleSurfaces && (box.minWidth != null || box.minHeight != null)
    // Options of a write action under one parent are one group: «1 of 3» is said only inside a collection.
    val optionGroup = node.children.isNotEmpty() && com.hotlayout.actions.WriteActions.holdsOptionGroup(node.children) { context.isShown(it) }
    val glassSource = if (node.widget == WidgetType.Screen) rememberGlassSource(node) else null
    val motion = if (node.widget == WidgetType.Screen) null else NodeMotion.read(node.fields["motion"])
    // The root never moves; a node placed by a stack has its motion turned back by the layer of the stack.
    val turn = if (parentType == "Stack") LocalLayerTurn.current else 0f
    val backgroundImage = rememberBackgroundImage(box.background, (box.radius ?: Corners()).shape(), context)
    val body: @Composable (Modifier) -> Unit = { base ->
        var outer = base
        if (press != null || long != null) outer = outer.then(Modifier.pressableCard(node, inner, press, long))
        // A named size is the size of the content too: a stack, a column or a row inside it fills that box and aligns
        // its children by its own words (without this the content sat at the top start of the named box).
        Box(outer.then(if (optionGroup) Modifier.selectableGroup() else Modifier).glass(box.backdropBlur, (box.radius ?: Corners()).shape()).nodeBox(box.withoutMargin(), placement.stretchWidth, context.rightToLeft, clipsNamedSize = node.widget !in WidgetType.roleSurfaces, backgroundImage = backgroundImage), propagateMinConstraints = named || fillsMinimum) {
            ProvideSiblings(node) { Widget(WidgetCall(node, inner, style, placement, depth, parentType, context.listPosition, context.listNumber)) }
        }
    }
    ProvideGlass(glassSource) {
        val framed = if (glassSource != null) outerBase.glassSource(glassSource) else outerBase
        if (motion == null) body(framed) else NodeMotionBox(motion, context.rightToLeft, turn, framed) { body(Modifier) }
    }
}

/** The effective style; an app bar that is the zone of its screen lays its scrolled style over it while the body is scrolled. */
private fun effectiveStyle(node: DocumentNode, context: RenderContext): JsonObject {
    val style = NodeStyle.effective(node, context.scope)
    if (node.widget != WidgetType.AppBar || !context.barScrolled) return style
    return AppBarRules.withScrolledLayer(style, AppBarRules.scrolledStyle(node), context.resolver)
}

/** The arguments every widget receives; one value so that the dispatcher signature never changes. */
internal data class WidgetCall(
    val node: DocumentNode,
    val context: RenderContext,
    /** The effective style of the node (`style` with the state that holds laid over it). */
    val style: JsonObject,
    val placement: Placement,
    val depth: Int,
    val parentType: String?,
    /** The place among the siblings of the parent (`Text.marker` numbering). */
    val position: com.hotlayout.render.widgets.ListPosition? = null,
    /** The number of a list-template instance. */
    val number: com.hotlayout.render.widgets.ListNumber? = null,
)

/** A role widget draws its own surface: only the size, the margin and the opacity stay on the node box. */
private fun outerOfRole(look: BoxLook) = BoxLook(
    margin = look.margin, width = look.width, height = look.height, minWidth = look.minWidth, maxWidth = look.maxWidth,
    minHeight = look.minHeight, maxHeight = look.maxHeight, opacity = look.opacity,
)
