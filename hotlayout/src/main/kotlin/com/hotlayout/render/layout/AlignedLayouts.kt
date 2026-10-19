package com.hotlayout.render.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** What a host of a flow reads from each of its children: the pull, the weight, how the child gets its width. */
internal data class FlowChild(
    /** Logical pixels toward the start of the main axis. */
    val pull: Double = 0.0,
    val weight: Double? = null,
    val kind: RowKind = RowKind.Natural,
    /** A column stretches this child to its width. */
    val stretch: Boolean = false,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@FlowChild
}

private val IntrinsicMeasurable.flow: FlowChild get() = parentData as? FlowChild ?: FlowChild()

/** `between` with a smallest gap: free room shared between the children, never less than the gap. */
internal class SpaceBetween(private val gap: Float) : Arrangement.Horizontal {
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

/**
 * The widths of the children of a row (`rowChildWidths`): named widths as they are; natural ones not wider than the
 * content of the row; what does not fit shrinks the natural ones in proportion; weights share what is left; no
 * [available] width (the parent gave none tightly) — natural widths, no shares.
 */
internal fun rowChildWidths(
    natural: List<Double>,
    kinds: List<RowKind>,
    weights: List<Double?>,
    gap: Double,
    pullTotal: Double,
    available: Double?,
): List<Double> {
    val gaps = gap * maxOf(0, natural.size - 1)
    val content = available?.let { maxOf(0.0, it - gaps + pullTotal) }
    val widths = DoubleArray(natural.size) { index ->
        when {
            available != null && weights[index] != null -> 0.0
            kinds[index] == RowKind.Named -> natural[index]
            content != null -> minOf(natural[index], content)
            else -> natural[index]
        }
    }
    if (content == null) return widths.toList()
    val shrinking = natural.indices.filter { weights[it] == null && kinds[it] == RowKind.Natural }
    val sumShrinking = shrinking.sumOf { widths[it] }
    val room = maxOf(0.0, content - (widths.sum() - sumShrinking))
    if (sumShrinking > room && sumShrinking > 0) for (index in shrinking) widths[index] = widths[index] * room / sumShrinking
    val totalWeight = weights.sumOf { it ?: 0.0 }
    if (totalWeight > 0) {
        val remainder = maxOf(0.0, content - widths.sum())
        for (index in natural.indices) weights[index]?.let { widths[index] = remainder * it / totalWeight }
    }
    return widths.toList()
}

@Composable
internal fun AlignedColumn(
    gap: Double,
    align: AlignWord,
    justify: JustifyWord,
    block: Double?,
    minBlock: Double?,
    fills: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val policy = remember(gap, align, justify, block, minBlock, fills) { ColumnPolicy(gap, align, justify, block, minBlock, fills) }
    Layout(content, modifier, policy)
}

private class ColumnPolicy(
    val gap: Double,
    val align: AlignWord,
    val justify: JustifyWord,
    val block: Double?,
    val minBlock: Double?,
    val fills: Boolean,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gapPx = gap * density
        val inline = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(constraints.minWidth, measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0)
        val placeables = measurables.map { child ->
            child.measure(if (child.flow.stretch) Constraints(minWidth = inline, maxWidth = inline) else Constraints(maxWidth = inline))
        }
        val pulls = measurables.map { it.flow.pull * density }
        val content = maxOf(0.0, placeables.sumOf { it.height.toDouble() } + gapPx * maxOf(0, placeables.size - 1) - pulls.sum())
        val named = when {
            fills && constraints.hasBoundedHeight -> maxOf(constraints.maxHeight.toDouble(), content)
            else -> block?.times(density)
        }
        val box = AlignRules.alignedChildRects(
            AlignedBoxInput(
                axis = MainAxis.Block, inline = inline.toDouble(), block = named, minBlock = minBlock?.times(density), gap = gapPx,
                align = align, justify = justify, rightToLeft = false,
                children = placeables.mapIndexed { index, placeable -> AlignedChild(placeable.width.toDouble(), placeable.height - pulls[index], measurables[index].flow.stretch) },
            ),
        )
        return layout(constraints.constrainWidth(inline), constraints.constrainHeight(box.block.roundToInt())) {
            placeables.forEachIndexed { index, placeable ->
                val rect = box.rects[index]
                placeable.placeRelative(rect.x.roundToInt(), (rect.y - pulls[index]).roundToInt())
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val content = measurables.sumOf { it.maxIntrinsicHeight(width) - (it.flow.pull * density).roundToInt() } + (gap * density * maxOf(0, measurables.size - 1)).roundToInt()
        return maxOf(content, block?.times(density)?.roundToInt() ?: 0, minBlock?.times(density)?.roundToInt() ?: 0)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = maxIntrinsicHeight(measurables, width)
}

@Composable
internal fun AlignedRow(
    gap: Double,
    justify: JustifyWord,
    align: AlignWord,
    block: Double?,
    fills: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val policy = remember(gap, justify, align, block, fills) { RowPolicy(gap, justify, align, block, fills) }
    Layout(content, modifier, policy)
}

private class RowPolicy(
    val gap: Double,
    val justify: JustifyWord,
    val align: AlignWord,
    val block: Double?,
    val fills: Boolean,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gapPx = gap * density
        val flows = measurables.map { it.flow }
        val pulls = flows.map { it.pull * density }
        // Shares exist only where the parent gave the width tightly; otherwise the row is as wide as its content.
        val available = if (constraints.hasFixedWidth) constraints.maxWidth.toDouble() else null
        val natural = measurables.mapIndexed { index, child ->
            if (available != null && flows[index].weight != null) 0.0 else child.maxIntrinsicWidth(Constraints.Infinity).toDouble()
        }
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth.toDouble() else null
        val widths = if (available != null) {
            rowChildWidths(natural, flows.map { it.kind }, flows.map { it.weight }, gapPx, pulls.sum(), available)
        } else {
            natural.mapIndexed { index, width -> if (flows[index].kind == RowKind.Named || limit == null) width else minOf(width, limit) }
        }
        val placeables = measurables.mapIndexed { index, child ->
            val width = widths[index].roundToInt()
            when {
                available != null && flows[index].weight != null -> child.measure(Constraints.fixedWidth(width))
                // A child that was not shrunk is only bound by the row (content that is not its natural width, such as an overlay, must not be cut to it).
                widths[index] >= natural[index] - 0.5 && flows[index].kind != RowKind.Named -> child.measure(Constraints(maxWidth = limit?.roundToInt() ?: Constraints.Infinity))
                else -> child.measure(Constraints(maxWidth = width))
            }
        }
        val hasShares = available != null && flows.any { it.weight != null }
        val contentWidth = placeables.sumOf { it.width.toDouble() } + gapPx * maxOf(0, placeables.size - 1) - pulls.sum()
        val inline = available ?: maxOf(0.0, contentWidth)
        val tallest = placeables.maxOfOrNull { it.height }?.toDouble() ?: 0.0
        val named = if (fills && constraints.hasBoundedHeight) maxOf(constraints.maxHeight.toDouble(), tallest) else block?.times(density)
        val box = AlignRules.alignedChildRects(
            AlignedBoxInput(
                axis = MainAxis.Inline, inline = inline, block = named, gap = gapPx, align = align,
                justify = if (hasShares) JustifyWord.Start else justify, rightToLeft = false,
                children = placeables.mapIndexed { index, placeable -> AlignedChild(placeable.width - pulls[index], placeable.height.toDouble()) },
            ),
        )
        return layout(constraints.constrainWidth(inline.roundToInt()), constraints.constrainHeight(box.block.roundToInt())) {
            placeables.forEachIndexed { index, placeable ->
                val rect = box.rects[index]
                placeable.placeRelative((rect.x - pulls[index]).roundToInt(), rect.y.roundToInt())
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        (measurables.sumOf { it.maxIntrinsicWidth(height).toDouble() - it.flow.pull * density } + gap * density * maxOf(0, measurables.size - 1)).roundToInt().coerceAtLeast(0)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        maxOf(measurables.maxOfOrNull { it.maxIntrinsicHeight(it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)) } ?: 0, block?.times(density)?.roundToInt() ?: 0)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = maxIntrinsicHeight(measurables, width)
}
