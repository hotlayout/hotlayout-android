package com.hotlayout.render.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.ListInstance
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.RenderContext
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.takesNaturalWidth
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * `Repeater`: one template drawn once per record of a collection. An empty list is an empty space:
 * what to show instead is the document's business (a neighbour with `isEmpty`).
 *
 * The scope of a record is laid over the outer scope; the filter and the limit decide which records
 * exist at all; a header (the second child) goes before each group of `groupBy` in a column.
 */
@Composable
internal fun RepeaterWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val plan = RepeaterPlan(node, context.scope) { template, scope -> context.isShown(template, scope) }
    if (plan.instances.isEmpty() || node.children.isEmpty()) return
    val template = node.children[0]
    val columns = RepeaterPlan.columns(node.props, overridden = false)
    val gap = context.resolver.spacing(node.props["gap"] ?: com.hotlayout.json.JsonValue.Str("sm")) ?: 0.0
    val numbered = template.type == "Text" && template.props["marker"]?.string == "number"
    val item = node.props["item"]?.string ?: ""
    val source = node.props["source"]?.string ?: ""

    fun itemContext(index: Int): RenderContext {
        val instance = plan.instances[index]
        return context.copy(
            scope = instance.scope,
            instances = context.instances + ListInstance(item, instance.fields, source),
            listNumber = if (numbered) instance.number?.let { ListNumber(it, plan.instances.size) } else null,
        )
    }

    val isCarousel = node.props["carousel"] != null
    val isRow = node.props["direction"]?.string == "row" || isCarousel
    val header = node.children.getOrNull(RecordGroups.HEADER_CHILD)?.takeIf { RepeaterPlan.groupBy(node.props) != null }
    // Groups exist in a column only.
    val grouped = columns == null && !isRow && header != null
    val depth = call.depth + 1
    val records = plan.instances.indices.filter { plan.instances[it].shown }

    when {
        columns != null -> GridCells(columns, gap, if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier) {
            for (index in records) TightSlot(template, itemContext(index), depth, node.type)
        }
        isRow -> RepeaterRow(isCarousel, gap, call.placement) {
            for (index in records) NodeView(template, itemContext(index), Placement.Natural, depth, node.type)
        }
        else -> Column(
            if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            for ((index, instance) in plan.instances.withIndex()) {
                // A header hidden by its own condition takes no place and no gap.
                if (grouped && instance.headerBefore && header != null && context.isShown(header, instance.scope)) {
                    NodeView(header, itemContext(index).copy(listNumber = null), Placement.Stretched, depth, node.type)
                }
                if (instance.shown) InstanceInColumn(template, itemContext(index), depth, node.type)
            }
        }
    }
}

/**
 * The instances of a list laid in a row. A plain row of natural widths; a list with `carousel` is
 * drawn in a horizontal scroll. This is the place the carousel family takes over: it replaces the
 * body with cards of the width its rules compute, stops and dots.
 */
@Composable
internal fun RepeaterRow(carousel: Boolean, gap: Double, placement: Placement, cards: @Composable () -> Unit) {
    val modifier = if (carousel) Modifier.horizontalScroll(rememberScrollState()) else if (placement.stretchWidth) Modifier.fillMaxWidth() else Modifier
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) { cards() }
}

/** An instance in a column is stretched to the column, except a node with its own width, which stands at the logical start. */
@Composable
private fun InstanceInColumn(template: DocumentNode, context: RenderContext, depth: Int, parentType: String) {
    if (template.style?.get("width") != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) { NodeView(template, context, Placement.Natural, depth, parentType) }
    } else {
        NodeView(template, context, Placement.Stretched, depth, parentType)
    }
}

/**
 * A child in a slot that sets its size itself (a `Grid` cell, an instance of a `Repeater` with
 * `columns`): a node of natural width, or with an own width, takes its natural width and stands at
 * the logical start, at the top; every other node takes the width of the cell.
 */
@Composable
internal fun TightSlot(node: DocumentNode, context: RenderContext, depth: Int, parentType: String?) {
    if (takesNaturalWidth(node)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) { NodeView(node, context, Placement.Natural, depth, parentType) }
    } else {
        NodeView(node, context, Placement.Stretched, depth, parentType)
    }
}

/**
 * Equal columns with the number of columns named by the document, cells at the top: a cell is
 * `(width − gaps) ÷ columns` wide, so the cells missing from the last row keep the width of the
 * others, and the height of a row is its tallest cell.
 */
@Composable
internal fun GridCells(columns: Int, gap: Double, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.dp.roundToPx()
        val total = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            (measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0) * columns + gapPx * (columns - 1)
        }
        val cell = GridGeometry.cellWidth(total.toDouble(), columns, gapPx.toDouble())
        val cellPx = floor(cell).toInt().coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(cellPx, cellPx, 0, Constraints.Infinity)) }
        val heights = placeables.chunked(columns).map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val height = heights.sum() + gapPx * maxOf(0, heights.size - 1)
        layout(total, height) {
            var top = 0
            placeables.chunked(columns).forEachIndexed { row, cells ->
                cells.forEachIndexed { column, placeable -> placeable.placeRelative(((cell + gapPx) * column).roundToInt(), top) }
                top += heights[row] + gapPx
            }
        }
    }
}

/** `Grid`: the visible children in rows of `columns` cells. */
@Composable
internal fun GridWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val columns = gridColumns(node.props["columns"], overridden = false) ?: 2
    val gap = context.resolver.spacing(node.props["gap"] ?: com.hotlayout.json.JsonValue.Str("sm")) ?: 0.0
    val cells = node.children.filter { context.isShown(it) }
    GridCells(columns, gap, if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier) {
        for (child in cells) TightSlot(child, context, call.depth + 1, node.type)
    }
}
