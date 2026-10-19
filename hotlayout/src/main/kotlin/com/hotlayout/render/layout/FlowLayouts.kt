package com.hotlayout.render.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.RenderContext
import com.hotlayout.render.takesNaturalWidth
import com.hotlayout.render.widgets.ListSiblings
import com.hotlayout.render.widgets.at

/**
 * A column: children stretch to its width, except those of natural width, which stand where
 * `align` puts them (`column.naturalWidthAlign`), [gap] apart.
 *
 * Wave «alignment» extends this: `justify` with a source of free height, `pull`.
 */
@Composable
internal fun ColumnWidget(children: List<DocumentNode>, gap: Double, node: DocumentNode?, context: RenderContext, depth: Int, stretch: Boolean = true) {
    val align = when (node?.props?.get("align")?.string) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
    // A column of natural width is as wide as its widest child, and the others stretch to that.
    val width = if (stretch) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)
    Column(width, verticalArrangement = Arrangement.spacedBy(gap.dp), horizontalAlignment = align) {
        val siblings = ListSiblings(children, context)
        for ((index, child) in children.withIndex()) {
            val natural = takesNaturalWidth(child)
            NodeView(child, context.at(siblings, index), if (natural) Placement.Natural else Placement.Stretched, depth + 1, node?.type)
        }
    }
}

/** The weight of a row child: a positive whole number; a child that stretches without one takes a share of one. */
internal fun rowWeight(child: DocumentNode): Double? {
    val weight = child.fields["weight"]?.number
    if (weight != null && weight >= 1 && weight == Math.rint(weight)) return weight
    if (child.style?.get("width") != null) return null
    return when (child.type) {
        "Button" -> if (child.props["fullWidth"]?.bool != false) 1.0 else null
        "Divider", "Spacer" -> if (child.props["orientation"]?.string == "vertical") null else 1.0
        "ProgressBar" -> 1.0
        else -> null
    }
}

/** `between` with a smallest gap: free room shared between the children, never less than the gap. */
private class SpaceBetween(private val gap: Float) : Arrangement.Horizontal {
    override val spacing = gap.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        val gapPx = gap.dp.roundToPx()
        val used = sizes.sum()
        val between = if (sizes.size > 1) maxOf(gapPx, (totalSize - used) / (sizes.size - 1)) else 0
        var x = 0
        val order = if (layoutDirection == LayoutDirection.Rtl) sizes.indices.reversed() else sizes.indices
        for (index in order) {
            outPositions[index] = if (layoutDirection == LayoutDirection.Rtl) totalSize - x - sizes[index] else x
            x += sizes[index] + between
        }
    }
}

/** A row (or a container with `direction: row`): natural widths, weights share the rest. */
@Composable
internal fun RowWidget(node: DocumentNode, context: RenderContext, depth: Int, stretch: Boolean = true) {
    val isContainer = node.type == "Container"
    val gap = context.resolver.spacing(node.props["gap"] ?: com.hotlayout.json.JsonValue.Str(if (isContainer) "none" else "sm")) ?: 0.0
    // An unknown word acts as without the field: `start` on the main axis, `center` across.
    val arrangement: Arrangement.Horizontal = when (node.props["justify"]?.string) {
        "center" -> Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(gap.dp, Alignment.End)
        "between" -> SpaceBetween(gap.toFloat())
        else -> Arrangement.spacedBy(gap.dp, Alignment.Start)
    }
    val vertical = when (node.props["align"]?.string) {
        "start" -> Alignment.Top
        "end" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
    val scrollable = node.props["scrollable"]?.bool == true
    // A row of natural width (in another row) is as wide as its children, never greedy.
    val base = if (stretch) Modifier.fillMaxWidth() else Modifier
    val modifier = if (scrollable) base.horizontalScroll(rememberScrollState()) else base
    Row(modifier, horizontalArrangement = arrangement, verticalAlignment = vertical) {
        val siblings = ListSiblings(node.children, context)
        for ((index, child) in node.children.withIndex()) {
            val weight = if (scrollable) null else rowWeight(child)
            val childModifier = if (weight != null) Modifier.weight(weight.toFloat(), fill = true) else Modifier
            NodeView(child, context.at(siblings, index), if (weight == null) Placement.Natural else Placement.Stretched, depth + 1, node.type, childModifier)
        }
    }
}
