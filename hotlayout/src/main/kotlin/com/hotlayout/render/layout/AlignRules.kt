package com.hotlayout.render.layout

import com.hotlayout.json.JsonObject

/** The main axis of a host of alignment: `block` runs top to bottom, `inline` along the line of the script. */
internal enum class MainAxis { Block, Inline }

/** The words of the cross axis (`align`). */
internal enum class AlignWord(val word: String) {
    Start("start"), Center("center"), End("end");

    companion object {
        fun of(word: String?): AlignWord? = entries.firstOrNull { it.word == word }
    }
}

/** The words of the main axis (`justify`): the words of a row. */
internal enum class JustifyWord(val word: String) {
    Start("start"), Center("center"), End("end"), Between("between");

    companion object {
        fun of(word: String?): JustifyWord? = entries.firstOrNull { it.word == word }
    }
}

/** What gives a column a height larger than its content (`x-style.layout.alignment.main.column`). */
internal enum class HeightSource(val word: String) { Height("style.height"), MinHeight("style.minHeight"), Fill("fill") }

/** The two words that act on a host and the main axis they belong to. */
internal data class AlignmentRead(val mainAxis: MainAxis, val align: AlignWord, val justify: JustifyWord)

/** A child of a host with its own margins included; a column stretches `stretch` children (their width is then not read). */
internal data class AlignedChild(val width: Double, val height: Double, val stretch: Boolean = false)

/** A placed child: `x` is from the LEFT edge of the content box in both directions of the script. */
internal data class AlignedRect(val x: Double, val y: Double, val width: Double, val height: Double)

internal data class AlignedBoxInput(
    val axis: MainAxis,
    /** The width of the content box. */
    val inline: Double,
    /** The named height of the content box (`style.height`, a layer with `fill`). */
    val block: Double? = null,
    /** `style.minHeight` of the content box; read on the block axis only. */
    val minBlock: Double? = null,
    val gap: Double,
    val align: AlignWord,
    val justify: JustifyWord,
    val rightToLeft: Boolean,
    val children: List<AlignedChild>,
)

internal data class AlignedBox(val block: Double, val free: Double, val rects: List<AlignedRect>)

/**
 * The alignment rules of `x-style.layout.alignment` as pure functions: which node is a host, which
 * words act, how far a child moves along the main axis, where a child stands on the cross axis.
 * The layouts read these and nothing else.
 */
internal object AlignRules {
    /**
     * The host of a node (`Screen`, `Column`, a `Container`); `null` for a node that has no fields of
     * 9.15.0. A `Row` is not a host: its fields are older and have schema defaults. No field or an
     * unknown word acts as without the field: `start`, and `center` across a row.
     */
    fun read(type: String, props: JsonObject): AlignmentRead? {
        val axis = when (type) {
            "Screen", "Column" -> MainAxis.Block
            "Container" -> if (props["direction"]?.string == "row") MainAxis.Inline else MainAxis.Block
            else -> return null
        }
        val align = AlignWord.of(props["align"]?.string) ?: if (axis == MainAxis.Block) AlignWord.Start else AlignWord.Center
        return AlignmentRead(axis, align, JustifyWord.of(props["justify"]?.string) ?: JustifyWord.Start)
    }

    /** A layer fills its stack: `fill` is read as `true` on a direct child of a `Stack` only. */
    fun fills(fields: JsonObject, parent: String?): Boolean = parent == "Stack" && fields["fill"]?.bool == true

    /** What gives a column host a height larger than its content; `null` — none, and no free space. */
    fun heightSource(fields: JsonObject, parent: String?, ownHeight: Boolean, ownMinHeight: Boolean): HeightSource? = when {
        fills(fields, parent) -> HeightSource.Fill
        ownHeight -> HeightSource.Height
        ownMinHeight -> HeightSource.MinHeight
        else -> null
    }

    /** Whether the main axis of this host CAN have free space; `null` — not a host. */
    fun hasFreeSpace(type: String, props: JsonObject, fields: JsonObject, parent: String?, ownHeight: Boolean, ownMinHeight: Boolean): Boolean? {
        val host = read(type, props) ?: return null
        return if (type == "Screen" || host.mainAxis == MainAxis.Inline) true else heightSource(fields, parent, ownHeight, ownMinHeight) != null
    }

    /**
     * How far each of [count] children moves along the main axis from the place it has with `start`.
     * `free` ≤ 0 is no free space; the gap is not part of it; the numbers are not rounded; `between`
     * with one child is `start`.
     */
    fun shifts(free: Double, count: Int, justify: JustifyWord): List<Double> {
        val space = if (free.isFinite()) maxOf(0.0, free) else 0.0
        return List(maxOf(0, count)) { index ->
            when (justify) {
                JustifyWord.Center -> space / 2
                JustifyWord.End -> space
                JustifyWord.Between -> if (count > 1) space * index / (count - 1) else 0.0
                JustifyWord.Start -> 0.0
            }
        }
    }

    /** The offset of a child of [size] from the START of a cross axis of [available]. */
    fun crossOffset(available: Double, size: Double, align: AlignWord): Double {
        val space = maxOf(0.0, available - size)
        return when (align) {
            AlignWord.Center -> space / 2
            AlignWord.End -> space
            AlignWord.Start -> 0.0
        }
    }

    /** Where the children of a host stand in its content box. Numbers are not rounded; a child larger than the box keeps its size. */
    fun alignedChildRects(input: AlignedBoxInput): AlignedBox {
        val children = input.children
        val inline = input.inline
        val gaps = input.gap * maxOf(0, children.size - 1)
        fun fromLeft(start: Double, width: Double) = if (input.rightToLeft) inline - start - width else start
        if (input.axis == MainAxis.Block) {
            val content = children.sumOf { it.height } + gaps
            val block = input.block ?: maxOf(input.minBlock ?: 0.0, content)
            val free = maxOf(0.0, block - content)
            val shifts = shifts(free, children.size, input.justify)
            var cursor = 0.0
            val rects = children.mapIndexed { index, child ->
                val width = if (child.stretch) inline else minOf(child.width, inline)
                val y = cursor + shifts[index]
                cursor += child.height + input.gap
                AlignedRect(fromLeft(crossOffset(inline, width, input.align), width), y, width, child.height)
            }
            return AlignedBox(block, free, rects)
        }
        val content = children.sumOf { it.width } + gaps
        val block = input.block ?: children.fold(0.0) { tallest, child -> maxOf(tallest, child.height) }
        val free = maxOf(0.0, inline - content)
        val shifts = shifts(free, children.size, input.justify)
        var cursor = 0.0
        val rects = children.mapIndexed { index, child ->
            val start = cursor + shifts[index]
            cursor += child.width + input.gap
            AlignedRect(fromLeft(start, child.width), crossOffset(block, child.height, input.align), child.width, child.height)
        }
        return AlignedBox(block, free, rects)
    }

    // --- the body of a screen -------------------------------------------------------------

    /** The free space of the body on the main axis: the height of the area minus the length of what scrolls. */
    fun screenBodyFreeSpace(zones: ScreenZones): Double = maxOf(0.0, zones.bodyHeight - zones.contentHeight)

    /**
     * The zones of a screen (`x-style.layout.screen`): the body is the area between the app bar and
     * the bottom zone and the only part that scrolls. With a pinned block the bottom padding of the
     * root went under the block and the body ends with the gap of the screen.
     */
    fun screenZones(input: ScreenZonesInput): ScreenZones {
        val hasBar = input.appBarHeight != null
        val barZone = if (hasBar) input.paddingTop + maxOf(0.0, input.appBarHeight ?: 0.0) else 0.0
        val empty = input.bodyContentHeight == null
        val lead = if (hasBar) (if (empty) 0.0 else input.gap) else input.paddingTop
        val hasPinned = input.pinnedHeight != null
        val hasNav = input.navBarHeight != null
        val pinnedRoom = if (hasPinned) maxOf(0.0, input.pinnedHeight ?: 0.0) + maxOf(0.0, input.paddingBottom) else 0.0
        val zoneHeight = if (hasPinned || hasNav) pinnedRoom + maxOf(0.0, input.navBarHeight ?: 0.0) else 0.0
        val tail = if (hasPinned) (if (empty) 0.0 else input.gap) else input.paddingBottom
        val contentHeight = maxOf(0.0, barZone + lead + (input.bodyContentHeight ?: 0.0) + tail - barZone)
        val natural = barZone + contentHeight + zoneHeight
        val height = if (input.shrink) minOf(natural, maxOf(0.0, input.height)) else maxOf(0.0, input.height)
        val zoneTop = maxOf(barZone, height - zoneHeight)
        val areaHeight = maxOf(0.0, zoneTop - barZone)
        return ScreenZones(barZone, areaHeight, contentHeight, barZone + lead, contentHeight - areaHeight > 0)
    }
}

/** The input of the zones of a screen. */
internal data class ScreenZonesInput(
    val height: Double,
    /** A modal layer is as tall as its content, not taller than [height]. */
    val shrink: Boolean = false,
    val appBarHeight: Double? = null,
    val paddingTop: Double,
    val paddingBottom: Double,
    val gap: Double,
    /** The height of the flow of the visible children of the body; `null` — none. */
    val bodyContentHeight: Double? = null,
    val pinnedHeight: Double? = null,
    val navBarHeight: Double? = null,
)

internal data class ScreenZones(
    val bodyTop: Double,
    val bodyHeight: Double,
    /** The length of what scrolls, from the top of the body area, with the paddings. */
    val contentHeight: Double,
    val contentTop: Double,
    val scrolls: Boolean,
)
