package com.hotlayout.render.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.RenderContext
import com.hotlayout.render.takesNaturalWidth
import com.hotlayout.render.widgets.ListSiblings
import com.hotlayout.render.widgets.at
import com.hotlayout.theme.NodeStyle

/**
 * The height of the visible body of the screen being drawn, for the `justify` of the screen itself:
 * the free space of the body is this height less the paddings and the content. `null` where nobody
 * gave one (a layer as tall as its content has no free space).
 */
internal val LocalScreenBodyHeight = compositionLocalOf<Dp?> { null }

/** The heights a host can distribute along its main axis, in its content box, in logical pixels. */
internal data class HostSpace(val block: Double? = null, val minBlock: Double? = null)

/** The named and the minimal height of the content box of a host (`style.height` / `style.minHeight` less padding and border). */
@Composable
private fun hostSpace(node: DocumentNode?, context: RenderContext): HostSpace {
    if (node == null) return HostSpace()
    val look = context.resolver.box(NodeStyle.effective(node, context.scope), context.scope)
    val inset = look.padding.top + look.padding.bottom + 2 * (look.borderWidth ?: 0.0)
    val body = if (node.type == "Screen") LocalScreenBodyHeight.current?.value?.toDouble() else null
    val named = look.minHeight?.let { maxOf(0.0, it - inset) }
    val screen = body?.let { maxOf(0.0, it - inset) }
    return HostSpace(
        block = look.height?.let { maxOf(0.0, it - inset) },
        minBlock = listOfNotNull(named, screen).maxOrNull(),
    )
}

/** How far a child is pulled toward the start of its flow, in logical pixels (`pull`); a hidden child is not pulled. */
private fun pullOf(child: DocumentNode, parent: DocumentNode?, context: RenderContext): Double {
    if (parent == null || !context.isShown(child)) return 0.0
    val (by, _) = FlowPull.read(child, parent.type, parent.props) ?: return 0.0
    return context.resolver.spacing(by) ?: 0.0
}

/**
 * A column: children stretch to its width, except those of natural width, which stand where `align`
 * puts them (`column.naturalWidthAlign`), [gap] apart; `justify` distributes the free height when the
 * column has a source of it (`x-style.layout.alignment`); a child with `pull` is drawn earlier by that
 * much and the ones after it follow. [fills] — the column is a layer that fills its stack.
 */
@Composable
internal fun ColumnWidget(
    children: List<DocumentNode>,
    gap: Double,
    node: DocumentNode?,
    context: RenderContext,
    depth: Int,
    stretch: Boolean = true,
    fills: Boolean = false,
    ignoresFirstPull: Boolean = false,
) {
    val read = node?.let { AlignRules.read(it.type, it.props) }
    val space = hostSpace(node, context)
    // A column of natural width is as wide as its widest child, and the others stretch to that.
    val width = if (stretch) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)
    AlignedColumn(gap, read?.align ?: AlignWord.Start, read?.justify ?: JustifyWord.Start, space.block, space.minBlock, fills, width) {
        val siblings = ListSiblings(children, context)
        for ((index, child) in children.withIndex()) {
            val natural = takesNaturalWidth(child)
            val pull = if (ignoresFirstPull && index == 0) 0.0 else pullOf(child, node, context)
            NodeView(child, context.at(siblings, index), if (natural) Placement.Natural else Placement.Stretched, depth + 1, node?.type, FlowChild(pull = pull, stretch = !natural))
        }
    }
}

/** How a child of a row gets its width (`x-style.layout.row`). */
internal enum class RowKind { Natural, Rigid, Named }

/** The weight of a row child: a whole number from the document, or a share of one for stretching content. */
internal fun rowWeight(child: DocumentNode): Double? {
    validWeight(child)?.let { return it }
    if (child.style?.get("width") != null) return null
    return when (child.type) {
        "Button" -> if (child.props["fullWidth"]?.bool != false) 1.0 else null
        "Divider", "Spacer" -> if (child.props["orientation"]?.string == "vertical") null else 1.0
        "ProgressBar", "Grid" -> 1.0
        else -> null
    }
}

/** A child without a weight: named (does not shrink, not bound by the row), rigid (does not shrink), or natural (shrinks). */
internal fun rowKind(child: DocumentNode): RowKind = when {
    child.style?.get("width") != null -> RowKind.Named
    takesNaturalWidth(child) -> RowKind.Rigid
    else -> RowKind.Natural
}

/** Whether a child takes the width of the visible area in a scrolling row (`scrollableRow.stretchToViewport`). */
private fun stretchesInScroll(child: DocumentNode): Boolean = validWeight(child) == null && child.style?.get("width") == null && rowWeight(child) != null

/** A row (or a container with `direction: row`): natural widths, weights share the rest, `justify` distributes what stays. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RowWidget(node: DocumentNode, context: RenderContext, depth: Int, stretch: Boolean = true, fills: Boolean = false) {
    val isContainer = node.type == "Container"
    val gap = context.resolver.spacing(node.props["gap"] ?: JsonValue.Str(if (isContainer) "none" else "sm")) ?: 0.0
    // An unknown word acts as without the field: `start` on the main axis, `center` across.
    val justify = JustifyWord.of(node.props["justify"]?.string) ?: JustifyWord.Start
    val align = AlignWord.of(node.props["align"]?.string) ?: AlignWord.Center
    val scrollable = node.props["scrollable"]?.bool == true
    val wrap = node.props["wrap"]?.bool == true && !scrollable
    // A row of natural width (in another row) is as wide as its children, never greedy.
    val base = if (stretch) Modifier.fillMaxWidth() else Modifier
    val space = hostSpace(node, context)
    val siblings = ListSiblings(node.children, context)
    when {
        scrollable -> BoxWithConstraints(base) {
            val viewport = maxWidth.takeIf { it != Dp.Infinity }
            AlignedRow(gap, justify, align, space.block, fills, Modifier.horizontalScroll(rememberScrollState())) {
                for ((index, child) in node.children.withIndex()) {
                    var modifier: Modifier = Modifier
                    if (viewport != null && rowKind(child) != RowKind.Named) {
                        modifier = if (stretchesInScroll(child)) Modifier.width(viewport) else Modifier.widthIn(max = viewport)
                    }
                    NodeView(child, context.at(siblings, index), Placement.Natural, depth + 1, node.type, modifier.then(FlowChild(kind = rowKind(child))))
                }
            }
        }
        wrap -> FlowRow(
            base,
            horizontalArrangement = when (justify) {
                JustifyWord.Center -> Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally)
                JustifyWord.End -> Arrangement.spacedBy(gap.dp, Alignment.End)
                JustifyWord.Between -> SpaceBetween(gap.toFloat())
                JustifyWord.Start -> Arrangement.spacedBy(gap.dp, Alignment.Start)
            },
            verticalArrangement = Arrangement.spacedBy(gap.dp),
            itemVerticalAlignment = when (align) {
                AlignWord.Start -> Alignment.Top
                AlignWord.End -> Alignment.Bottom
                AlignWord.Center -> Alignment.CenterVertically
            },
        ) {
            for ((index, child) in node.children.withIndex()) NodeView(child, context.at(siblings, index), Placement.Natural, depth + 1, node.type)
        }
        else -> AlignedRow(gap, justify, align, space.block, fills, base) {
            for ((index, child) in node.children.withIndex()) {
                val weight = rowWeight(child)
                val pull = pullOf(child, node, context)
                NodeView(child, context.at(siblings, index), if (weight == null) Placement.Natural else Placement.Stretched, depth + 1, node.type, FlowChild(pull = pull, weight = weight, kind = rowKind(child)))
            }
        }
    }
}
