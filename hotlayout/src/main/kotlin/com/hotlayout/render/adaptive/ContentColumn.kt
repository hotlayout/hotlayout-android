package com.hotlayout.render.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import com.hotlayout.model.WidthRules
import kotlin.math.roundToInt

/**
 * The content of a zone in a column of at most [maxWidth] logical pixels (`Screen.contentMaxWidth`), centred in the
 * width it is given. Half of the remainder stands on each side, not rounded, so the column is in the same place in
 * both writing directions. A box narrower than the number, and no number, change nothing; the box of the zone itself
 * (its background) is not touched — only what stands in it.
 */
internal fun Modifier.contentColumn(maxWidth: Double?): Modifier {
    if (maxWidth == null) return this
    return layout { measurable, constraints ->
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val inner = minOf(available, (maxWidth * density).roundToInt())
        val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, inner), maxWidth = inner))
        val width = if (constraints.hasBoundedWidth) available else placeable.width
        val start = WidthRules.contentBox(width / density.toDouble(), maxWidth).start
        layout(width, placeable.height) { placeable.place((start * density).roundToInt(), 0) }
    }
}
