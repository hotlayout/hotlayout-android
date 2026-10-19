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
import com.hotlayout.json.JsonObject
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.DocumentReader
import com.hotlayout.theme.BoxLook
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
    val action = node.props["action"]?.obj?.takeIf { node.type in pressableContainers && acts(it) }
    var outer = modifier.then(look.margin.paddingModifier())
    if (action != null) outer = outer.then(pressable(node, inner, action))
    // A named size is the size of the content too: a stack, a column or a row inside it fills that box and aligns
    // its children by its own words (without this the content sat at the top start of the named box).
    val named = box.width != null || box.height != null
    Box(outer.nodeBox(box.withoutMargin(), placement.stretchWidth, context.rightToLeft), propagateMinConstraints = named) {
        Widget(WidgetCall(node, inner, style, placement, depth, parentType, context.listPosition, context.listNumber))
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

@Composable
private fun pressable(node: DocumentNode, context: RenderContext, action: JsonObject): Modifier {
    val label = if (node.props["label"] != null) context.text(node, "label") else null
    val source = remember { MutableInteractionSource() }
    return Modifier
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!label.isNullOrEmpty()) contentDescription = label
        }
        .clickable(interactionSource = source, indication = null) { context.press(action, node) }
}
