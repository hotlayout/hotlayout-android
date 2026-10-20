package com.hotlayout.render.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.motion.LocalLayerTurn
import com.hotlayout.render.paint.aboveGlass
import com.hotlayout.theme.StyleResolver

/** What a layer is for the layout of its stack, handed through the parent data of the layer's box. */
private class LayerData(val role: LayerRole) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = role
}

/** A layer as the layout needs it: its place is resolved against the theme. */
private sealed class LayerRole {
    data object Sizing : LayerRole()
    data object Fill : LayerRole()
    data class Overlay(val place: OverlayPlace) : LayerRole()
}

internal data class OverlayPlace(
    val alignHorizontal: LayerAlignWord,
    val alignVertical: LayerAlignWord,
    val offsetX: Pair<String, Double>?,
    val offsetY: Pair<String, Double>?,
    val rotate: Int,
)

/** The own alignment word or the word of the stack; an offset whose token the theme does not know is no offset on that axis. */
internal fun overlayPlace(read: LayerRead, stack: DocumentNode, resolver: StyleResolver): OverlayPlace {
    fun pixels(axis: LayerAxisOffset?): Pair<String, Double>? =
        axis?.let { a -> resolver.spacing(a.by.json)?.let { a.toward to it } }
    return OverlayPlace(
        read.alignHorizontal ?: LayerAlignWord.of(stack.props["alignHorizontal"]) ?: LayerAlignWord.Center,
        read.alignVertical ?: LayerAlignWord.of(stack.props["alignVertical"]) ?: LayerAlignWord.Center,
        pixels(read.offsetX), pixels(read.offsetY), read.rotate,
    )
}

/**
 * `Stack`: layers in one point, the first is the lowest.
 *
 *  - layers that size the stack take their own size, the stack is the largest of them (zero when
 *    none is drawn), each stands by the alignment words of the stack;
 *  - a `fill` layer takes the size of the stack;
 *  - an overlay stands against the box of the stack and never changes it. Its place is a PLACEMENT of
 *    this layout, not a draw-time shift, so a touch lands where the layer is drawn; the turn is a
 *    layer transform, which pointer input follows too.
 */
@Composable
internal fun StackWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val alignH = LayerAlignWord.of(node.props["alignHorizontal"]) ?: LayerAlignWord.Center
    val alignV = LayerAlignWord.of(node.props["alignVertical"]) ?: LayerAlignWord.Center
    val kinds = LayerRules.kinds(node.type, node.children.map { it.fields })
    val acting = LayerRules.acting(kinds, node.children.map { context.isShown(it) })
    // A layer after a glass layer lies over that glass (a dot on a glass button), so it is drawn over it, sharp.
    val firstGlass = node.children.indexOfFirst { com.hotlayout.render.paint.GlassRules.uses(it) }
    Layout(
        content = {
            node.children.forEachIndexed { index, child ->
                val role = when (val kind = acting[index]) {
                    LayerKind.Sizing -> LayerRole.Sizing
                    LayerKind.Fill -> LayerRole.Fill
                    is LayerKind.Overlay -> LayerRole.Overlay(overlayPlace(kind.read, node, context.resolver))
                }
                // The motion of a layer is painted along the axes of the screen, outside the turn of its place.
                val turn = (role as? LayerRole.Overlay)?.let { LayerGeometry.drawnAngle(it.place.rotate, context.rightToLeft).toFloat() } ?: 0f
                // A layer takes its OWN size: only `fill` stretches one (done by the layout).
                CompositionLocalProvider(LocalLayerTurn provides turn) {
                    // A `fill` layer has the size of the stack, so its own flow is as wide as the stack, not as its widest child.
                NodeView(
                        child, context, if (role == LayerRole.Fill) Placement.Stretched else Placement.Natural, call.depth + 1, node.type,
                        Modifier.then(LayerData(role)).then(if (firstGlass in 0 until index) Modifier.aboveGlass() else Modifier),
                    )
                }
            }
        },
    ) { measurables, incoming ->
        layoutStack(measurables, incoming, alignH, alignV, call.placement.stretchWidth, context.rightToLeft)
    }
}

private fun androidx.compose.ui.layout.MeasureScope.layoutStack(
    measurables: List<Measurable>,
    incoming: Constraints,
    alignH: LayerAlignWord,
    alignV: LayerAlignWord,
    stretch: Boolean,
    rightToLeft: Boolean,
): androidx.compose.ui.layout.MeasureResult {
    val roles = measurables.map { it.parentData as? LayerRole ?: LayerRole.Sizing }
    val offered = if (incoming.hasBoundedWidth) incoming.maxWidth else Constraints.Infinity
    val sizing = measurables.indices.filter { roles[it] == LayerRole.Sizing }
        .associateWith { measurables[it].measure(Constraints(maxWidth = offered, maxHeight = incoming.maxHeight)) }
    val natural = sizing.values.maxOfOrNull { it.width } ?: 0
    val width = if (stretch && incoming.hasBoundedWidth && sizing.isNotEmpty()) incoming.maxWidth else natural
    val height = sizing.values.maxOfOrNull { it.height } ?: 0
    val stackWidth = width.coerceIn(incoming.minWidth, if (incoming.hasBoundedWidth) incoming.maxWidth else Int.MAX_VALUE)
    val stackHeight = height.coerceIn(incoming.minHeight, if (incoming.hasBoundedHeight) incoming.maxHeight else Int.MAX_VALUE)
    val room = if (stackWidth > 0) stackWidth else offered
    val placed = measurables.indices.map { index ->
        when (val role = roles[index]) {
            LayerRole.Sizing -> sizing.getValue(index)
            LayerRole.Fill -> measurables[index].measure(Constraints.fixed(stackWidth, stackHeight))
            is LayerRole.Overlay -> measurables[index].measure(Constraints(maxWidth = room, maxHeight = incoming.maxHeight))
        }
    }
    return layout(stackWidth, stackHeight) {
        for (index in measurables.indices) {
            val p = placed[index]
            when (val role = roles[index]) {
                LayerRole.Fill -> p.placeRelative(0, 0)
                LayerRole.Sizing -> p.placeRelative(
                    Math.round(LayerGeometry.along(stackWidth.toDouble(), p.width.toDouble(), alignH)).toInt(),
                    Math.round(LayerGeometry.along(stackHeight.toDouble(), p.height.toDouble(), alignV)).toInt(),
                )
                is LayerRole.Overlay -> placeOverlay(p, role.place, stackWidth, stackHeight, rightToLeft, density)
            }
        }
    }
}

private fun Placeable.PlacementScope.placeOverlay(p: Placeable, place: OverlayPlace, stackWidth: Int, stackHeight: Int, rightToLeft: Boolean, density: Float) {
    val input = LayerGeometry.Input(
        stackWidth.toDouble(), stackHeight.toDouble(), p.width.toDouble(), p.height.toDouble(),
        place.alignHorizontal, place.alignVertical,
        place.offsetX?.let { it.first to it.second * density }, place.offsetY?.let { it.first to it.second * density },
        place.rotate, rightToLeft,
    )
    val angle = LayerGeometry.drawnAngle(place.rotate, rightToLeft).toFloat()
    // `placeRelative` mirrors the logical start in right-to-left text; the sign of the turn is flipped above.
    val x = Math.round(LayerGeometry.logicalStart(input)).toInt()
    val y = Math.round(LayerGeometry.top(input)).toInt()
    p.placeRelativeWithLayer(x, y) { rotationZ = angle }
}
