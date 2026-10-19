package com.hotlayout.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.hotlayout.actions.PressTarget
import com.hotlayout.json.JsonObject
import com.hotlayout.render.interaction.ProvideSiblings
import com.hotlayout.render.interaction.pressableCard
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.DocumentReader
import com.hotlayout.render.motion.LocalLayerTurn
import com.hotlayout.render.motion.NodeMotion
import com.hotlayout.render.motion.NodeMotionBox
import com.hotlayout.render.paint.ProvideGlass
import com.hotlayout.render.paint.glass
import com.hotlayout.render.paint.glassSource
import com.hotlayout.render.paint.rememberGlassSource
import com.hotlayout.theme.BoxLook
import com.hotlayout.theme.Corners
import com.hotlayout.theme.NodeStyle

/** Widgets whose style lands on their own surface (a role of the theme), not on the node box. */
internal val roleSurfaceWidgets = setOf("Button", "IconButton", "Badge")

/** Containers that are pressable as a whole when they carry an action (9.19.0). */
internal val pressableContainers = setOf("Column", "Row", "Container", "Stack")

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
    val style = NodeStyle.effective(node, context.scope)
    // The place among siblings and the number of a list item are the node's own: descendants do not inherit them.
    val inner = context.copy(inherited = NodeStyle.inherited(context.inherited, style, context.resolver), listPosition = null, listNumber = null)
    val look = context.resolver.box(style, context.scope)
    val box = if (node.type in roleSurfaceWidgets) outerOfRole(look) else look
    val press = PressTarget.pressAction(node)
    val long = PressTarget.longPress(node)?.takeIf { node.type in pressableContainers }
    val outerBase = modifier.then(look.margin.paddingModifier())
    val named = box.width != null || box.height != null
    val glassSource = if (node.type == "Screen") rememberGlassSource(node) else null
    val motion = if (node.type == "Screen") null else NodeMotion.read(node.fields["motion"])
    // The root never moves; a node placed by a stack has its motion turned back by the layer of the stack.
    val turn = if (parentType == "Stack") LocalLayerTurn.current else 0f
    val body: @Composable (Modifier) -> Unit = { base ->
        var outer = base
        if (press != null || long != null) outer = outer.then(Modifier.pressableCard(node, inner, press, long))
        // A named size is the size of the content too: a stack, a column or a row inside it fills that box and aligns
        // its children by its own words (without this the content sat at the top start of the named box).
        Box(outer.glass(box.backdropBlur, (box.radius ?: Corners()).shape()).nodeBox(box.withoutMargin(), placement.stretchWidth, context.rightToLeft), propagateMinConstraints = named) {
            ProvideSiblings(node) { Widget(WidgetCall(node, inner, style, placement, depth, parentType, context.listPosition, context.listNumber)) }
        }
    }
    ProvideGlass(glassSource) {
        val framed = if (glassSource != null) outerBase.glassSource(glassSource) else outerBase
        if (motion == null) body(framed) else NodeMotionBox(motion, context.rightToLeft, turn, framed) { body(Modifier) }
    }
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
