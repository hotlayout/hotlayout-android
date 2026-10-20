package com.hotlayout.render.screen

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode

/** A band of a screen: its top edge from the top of the screen and its height. */
internal data class ZoneBox(val top: Double, val height: Double)

/** The navigation bar in the bottom zone: [height] includes the safe inset under the content, [contentHeight] does not. */
internal data class NavZoneBox(val top: Double, val height: Double, val contentHeight: Double)

/** The heights the bottom zone is made of (`x-style.layout.bottomZone`). */
internal data class BottomZoneInput(val pinnedHeight: Double? = null, val navBarHeight: Double? = null, val safeBottom: Double? = null)

/** The bottom zone with its parts measured from its own top. */
internal data class BottomZoneBox(val height: Double, val pinned: ZoneBox?, val navBar: NavZoneBox?, val safeBottom: Double)

/** The bottom zone in the coordinates of the screen. */
internal data class AnatomyBottom(val top: Double, val height: Double, val pinned: ZoneBox?, val navBar: NavZoneBox?, val safeBottom: Double)

/** What decides the zones of one screen. Every number is in logical pixels. */
internal data class AnatomyInput(
    /** The height the host gave the screen; for a modal layer, the available height. */
    val height: Double,
    /** A modal layer is as tall as its content, not taller than [height]. */
    val shrink: Boolean = false,
    /** The height of the box of the app bar WITHOUT the top safe inset; `null` — no app bar zone. */
    val appBarHeight: Double? = null,
    val paddingTop: Double = 0.0,
    val paddingBottom: Double = 0.0,
    /** The gap of the screen (`Screen.props.gap`). */
    val gap: Double = 0.0,
    /** The flow of the visible children of the body, the pull of the first one not counted; `null` — the body has no visible child. */
    val bodyContentHeight: Double? = null,
    val firstPull: Double? = null,
    val scrollTop: Double? = null,
    val bottom: BottomZoneInput? = null,
    /** The navigation bar floats over the body (`NavBar.float`). */
    val navFloat: Boolean = false,
    /** The field `Screen.props.bodyTop` as it stands in the document. */
    val bodyTop: JsonValue? = null,
    /** The top inset the host gave. */
    val safeTop: Double? = null,
    /** The height of the screen keyboard over the bottom edge of the screen. */
    val keyboard: Double = 0.0,
)

internal data class AnatomyZones(
    val height: Double,
    /** The box of the app bar WITH the inset inside it; `null` — no zone. */
    val appBar: ZoneBox? = null,
    /** Where the content of the bar starts (below the inset). */
    val appBarContentTop: Double? = null,
    val body: ZoneBox,
    val contentTop: Double,
    val contentHeight: Double,
    val maxScroll: Double,
    val scrollTop: Double,
    val scrolls: Boolean,
    val bottom: AnatomyBottom? = null,
    val float: Boolean = false,
    val bodyAtScreenTop: Boolean = false,
    val safeTop: Double = 0.0,
) {
    /** The same zones, moved down: the inset the reader accounts for lies above them. */
    fun lowered(distance: Double): AnatomyZones {
        if (distance == 0.0) return this
        return copy(
            height = height + distance,
            body = body.copy(top = body.top + distance),
            contentTop = contentTop + distance,
            bottom = bottom?.let { zone ->
                zone.copy(top = zone.top + distance, pinned = zone.pinned?.let { it.copy(top = it.top + distance) }, navBar = zone.navBar?.let { it.copy(top = it.top + distance) })
            },
        )
    }
}

/** Who owns the zone at the top of the screen (`topZoneOwnerOf`). */
internal enum class TopZoneOwner(val word: String) { AppBar("appBar"), NavBar("navBar"), None("none") }

/** The zone that takes a press at a point. */
internal enum class PressZone(val word: String) { AppBar("appBar"), Body("body"), Bottom("bottom"), None("none") }

/**
 * The zones of a screen: app bar, body, bottom zone, and who takes a press where
 * (`x-style.layout.screen`, `bottomZone`, `bodyTop`, `safeTop`, `navFloat`, `topZone`).
 */
internal object ScreenAnatomy {
    const val BOTTOM_PIN = "bottom"
    const val TOP_PIN = "top"
    const val SCREEN_TOP_WORD = "screenTop"

    /** The height the column of the body has for `justify` of the screen: the visible area minus what lies above and below the children. */
    fun columnRoom(area: Double, lead: Double, tail: Double): Double = maxOf(0.0, area - lead - tail)

    /** The free space of the body on the main axis: the height of the area minus the length of what scrolls. */
    fun bodyFreeSpace(zones: AnatomyZones): Double = maxOf(0.0, zones.body.height - zones.contentHeight)

    /** The word of `Screen.props.bodyTop`; an unknown word and a value that is not a string are no field. */
    fun bodyTopWord(value: JsonValue?): String? = if (value?.string == SCREEN_TOP_WORD) SCREEN_TOP_WORD else null

    /** The word of the `pin` field of a node, as the bottom zone reads it. */
    fun pinOf(node: DocumentNode): String? = if (node.fields["pin"]?.string == BOTTOM_PIN) BOTTOM_PIN else null

    fun topPinOf(node: DocumentNode): Boolean = node.fields["pin"]?.string == TOP_PIN

    /** `statusBarIcons`: a word the reader knows or nothing. */
    fun statusBarIcons(value: JsonValue?): String? = value?.string?.takeIf { it == "light" || it == "dark" }

    /** Where a modal layer lies: `center` or `bottom`; anything else is `bottom`. */
    fun modalPlacement(value: JsonValue?): String = if (value?.string == "center") "center" else "bottom"

    // Who is in which zone

    internal class ChildrenByZone(
        val appBar: DocumentNode?,
        val topPinned: List<DocumentNode>,
        val body: List<DocumentNode>,
        val pinned: DocumentNode?,
        val navBar: DocumentNode?,
    )

    /** The app bar zone: the first child of the root by the DOCUMENT, if it is an `AppBar`. */
    fun appBarOf(children: List<DocumentNode>): DocumentNode? = children.firstOrNull()?.takeIf { it.type == "AppBar" }

    /** The navigation bar: the last child of the root, if it is a `NavBar`. */
    fun navBarOf(children: List<DocumentNode>): DocumentNode? = children.lastOrNull()?.takeIf { it.type == "NavBar" }

    /** The pinned block: the last child not counting the navigation bar, if its `pin` is `bottom`; never an app bar or a navigation bar. */
    fun pinnedOf(children: List<DocumentNode>): DocumentNode? {
        val end = if (navBarOf(children) == null) children.size else children.size - 1
        val index = end - 1
        if (index < 0 || index >= children.size) return null
        val candidate = children[index]
        if (pinOf(candidate) != BOTTOM_PIN || candidate.type == "NavBar") return null
        if (index == 0 && candidate.type == "AppBar") return null
        return candidate
    }

    /** The run at the start, right after the app bar zone, whose `pin` is `top`. Not read with `bodyTop: screenTop`. */
    fun topPinnedOf(children: List<DocumentNode>, rootProps: JsonObject): List<DocumentNode> {
        if (bodyTopWord(rootProps["bodyTop"]) != null) return emptyList()
        val run = ArrayList<DocumentNode>()
        for (child in children.drop(if (children.firstOrNull()?.type == "AppBar") 1 else 0)) {
            if (!topPinOf(child) || child.type == "AppBar" || child.type == "NavBar") break
            run.add(child)
        }
        return run
    }

    fun childrenByZone(children: List<DocumentNode>, rootProps: JsonObject = JsonObject()): ChildrenByZone {
        val appBar = appBarOf(children)
        val top = topPinnedOf(children, rootProps)
        val nav = navBarOf(children)
        val pinned = pinnedOf(children)
        val from = (if (appBar == null) 0 else 1) + top.size
        val to = children.size - (if (nav == null) 0 else 1) - (if (pinned == null) 0 else 1)
        return ChildrenByZone(appBar, top, if (from < to) children.subList(from, to) else emptyList(), pinned, nav)
    }

    // The top of the screen

    /** The top inset the reader accounts for: a finite positive number from the host, at most the height of the screen; else zero. */
    fun safeTop(value: Double?, screenHeight: Double): Double {
        if (value == null || !value.isFinite() || value <= 0) return 0.0
        return minOf(value, maxOf(0.0, screenHeight))
    }

    /** Whether the body starts at the top edge of the screen: the word is named and the zone is not an own navigation strip. */
    fun bodyStartsAtScreenTop(bodyTop: JsonValue?, owner: TopZoneOwner): Boolean = bodyTopWord(bodyTop) != null && owner != TopZoneOwner.NavBar

    // The bottom zone

    /** The bottom zone: pinned block, then the navigation bar, then the safe inset. `null` when there is neither. */
    fun bottomZone(input: BottomZoneInput, rootPaddingBottom: Double): BottomZoneBox? {
        val hasPinned = input.pinnedHeight != null
        val hasNav = input.navBarHeight != null
        if (!hasPinned && !hasNav) return null
        val safe = maxOf(0.0, input.safeBottom ?: 0.0)
        val pinnedHeight = maxOf(0.0, input.pinnedHeight ?: 0.0)
        val pinnedRoom = if (hasPinned) pinnedHeight + maxOf(0.0, rootPaddingBottom) else 0.0
        val navContent = maxOf(0.0, input.navBarHeight ?: 0.0)
        return BottomZoneBox(
            height = pinnedRoom + (if (hasNav) navContent else 0.0) + safe,
            pinned = if (hasPinned) ZoneBox(0.0, pinnedHeight) else null,
            navBar = if (hasNav) NavZoneBox(pinnedRoom, navContent + safe, navContent) else null,
            safeBottom = safe,
        )
    }

    // Zones

    /** The zones of a screen in coordinates from its PHYSICAL top. */
    fun zones(input: AnatomyInput): AnatomyZones {
        val safeTop = safeTop(input.safeTop, input.height)
        val hasBar = input.appBarHeight != null
        val barBox = if (hasBar) ZoneBox(input.paddingTop, safeTop + maxOf(0.0, input.appBarHeight ?: 0.0)) else null
        if (bodyStartsAtScreenTop(input.bodyTop, if (hasBar) TopZoneOwner.AppBar else TopZoneOwner.None)) {
            val bare = input.copy(appBarHeight = null, paddingTop = if (hasBar) 0.0 else input.paddingTop)
            val natural = base(bare)
            val floor = barBox?.let { minOf(it.top + it.height, maxOf(0.0, input.height)) } ?: 0.0
            var body = natural
            if (input.shrink && natural.height < floor) body = base(bare.copy(shrink = false, height = floor))
            return body.copy(
                appBar = barBox,
                appBarContentTop = if (barBox == null) null else input.paddingTop + safeTop,
                bodyAtScreenTop = true,
                safeTop = safeTop,
            )
        }
        if (barBox != null) {
            return base(input.copy(appBarHeight = barBox.height)).copy(appBar = barBox, appBarContentTop = input.paddingTop + safeTop, safeTop = safeTop)
        }
        return base(input.copy(height = maxOf(0.0, input.height - safeTop))).lowered(safeTop).copy(safeTop = safeTop)
    }

    /** The zones without the top inset and the word `bodyTop`: the base rule of the screen, with a floating navigation bar and the keyboard. */
    fun base(input: AnatomyInput): AnatomyZones {
        val hasBar = input.appBarHeight != null
        val barZone = if (hasBar) input.paddingTop + maxOf(0.0, input.appBarHeight ?: 0.0) else 0.0
        val empty = input.bodyContentHeight == null
        // What lies above the first child INSIDE the scroll: the gap under the app bar, or the top padding without one.
        val lead = if (hasBar) (if (empty) 0.0 else input.gap) else input.paddingTop
        val pull = if (empty) 0.0 else maxOf(0.0, input.firstPull ?: 0.0)
        // Without an app bar a pulled first child goes into the top padding and beyond: nothing to raise.
        val raise = if (hasBar) minOf(maxOf(0.0, pull - lead), barZone) else 0.0
        val areaTop = barZone - raise
        val restTop = barZone + lead - pull
        var bottomInput = input.bottom
        if (input.keyboard > 0 && bottomInput?.navBarHeight == null) bottomInput = bottomInput?.copy(safeBottom = 0.0)
        val zone = bottomInput?.let { bottomZone(it, input.paddingBottom) }
        val tail = if (zone?.pinned != null) (if (empty) 0.0 else input.gap) else input.paddingBottom
        val contentHeight = maxOf(0.0, restTop + (input.bodyContentHeight ?: 0.0) + tail - areaTop)
        val zoneHeight = zone?.height ?: 0.0
        val lowHeight = zone?.navBar?.height ?: 0.0
        val lift = if (input.shrink) 0.0 else keyboardLift(input.keyboard, lowHeight)
        val natural = areaTop + contentHeight + zoneHeight
        val height = if (input.shrink) minOf(natural, maxOf(0.0, input.height)) else maxOf(0.0, input.height)
        val zoneTop = maxOf(areaTop, height - zoneHeight - lift)
        val areaHeight = maxOf(0.0, zoneTop - areaTop)
        val maxScroll = maxOf(0.0, contentHeight - areaHeight)
        val scrollTop = minOf(maxOf(0.0, input.scrollTop ?: 0.0), maxScroll)
        var result = AnatomyZones(
            height = height,
            appBar = if (hasBar) ZoneBox(input.paddingTop, maxOf(0.0, input.appBarHeight ?: 0.0)) else null,
            body = ZoneBox(areaTop, areaHeight),
            contentTop = restTop - scrollTop,
            contentHeight = contentHeight,
            maxScroll = maxScroll,
            scrollTop = scrollTop,
            scrolls = maxScroll > 0,
            bottom = zone?.let {
                AnatomyBottom(
                    zoneTop, zoneHeight,
                    it.pinned?.let { box -> ZoneBox(zoneTop + box.top, box.height) },
                    it.navBar?.let { box -> NavZoneBox(zoneTop + box.top, box.height, box.contentHeight) },
                    it.safeBottom,
                )
            },
        )
        val bar = result.bottom?.navBar
        if (input.navFloat && input.keyboard == 0.0 && bar != null) result = floated(result, input, bar)
        return result
    }

    /** A floating navigation bar: the body area reaches the bottom of the screen and the content scrolls under the zone, which adds its height to the end. */
    private fun floated(base: AnatomyZones, input: AnatomyInput, bar: NavZoneBox): AnatomyZones {
        val zone = base.bottom ?: return base
        val restTop = base.contentTop + base.scrollTop
        val bodyHeight = maxOf(0.0, base.height - base.body.top)
        val contentHeight = base.contentHeight + zone.height
        val maxScroll = maxOf(0.0, contentHeight - bodyHeight)
        val scrollTop = minOf(maxOf(0.0, input.scrollTop ?: 0.0), maxScroll)
        return base.copy(
            body = ZoneBox(base.body.top, bodyHeight),
            contentTop = restTop - scrollTop,
            contentHeight = contentHeight,
            maxScroll = maxScroll,
            scrollTop = scrollTop,
            scrolls = maxScroll > 0,
            bottom = zone.copy(navBar = NavZoneBox(bar.top, bar.contentHeight, bar.contentHeight)),
            float = true,
        )
    }

    // Who takes a press

    /** The zone that takes a press at a point (y from the top). With the body from the top edge the bar lies over it and takes the presses of its own box only. */
    fun pressZone(zones: AnatomyZones, x: Double? = null, y: Double, barInline: Pair<Double, Double>? = null): PressZone {
        if (y < 0 || y >= zones.height) return PressZone.None
        zones.bottom?.let { if (y >= it.top) return PressZone.Bottom }
        var inBar = false
        zones.appBar?.let { bar ->
            var inline = true
            if (x != null && barInline != null) inline = x >= barInline.first && x < barInline.first + barInline.second
            inBar = y >= bar.top && y < bar.top + bar.height && inline
        }
        val inBody = y >= zones.body.top && y < zones.body.top + zones.body.height
        if (zones.bodyAtScreenTop) return if (inBar) PressZone.AppBar else if (inBody) PressZone.Body else PressZone.None
        if (inBody) return PressZone.Body
        return if (inBar) PressZone.AppBar else PressZone.None
    }

    // The top zone

    internal class TopZoneInput(
        val appBarHeight: Double?,
        val pinned: List<Pair<Double, Double?>>,
        val rootPaddingTop: Double,
        val gap: Double,
        val safeTop: Double?,
    )

    internal class TopZoneBox(val height: Double, val appBar: ZoneBox?, val pinned: List<ZoneBox>, val bodyTop: Double)

    /** The top zone: root padding, the bar, then the nodes with the gap of the screen between them; the first node's pull is not read. */
    fun topZone(input: TopZoneInput): TopZoneBox {
        val gap = maxOf(0.0, input.gap)
        val padding = maxOf(0.0, input.rootPaddingTop)
        val hasBar = input.appBarHeight != null
        val barHeight = maxOf(0.0, input.appBarHeight ?: 0.0)
        val safe = if (hasBar) 0.0 else maxOf(0.0, input.safeTop ?: 0.0)
        val appBar = if (hasBar) ZoneBox(padding, barHeight) else null
        var cursor = if (hasBar) padding + barHeight else safe
        val boxes = ArrayList<ZoneBox>()
        for ((index, entry) in input.pinned.withIndex()) {
            val lead = if (index == 0) (if (hasBar) gap else padding) else gap - maxOf(0.0, entry.second ?: 0.0)
            val top = cursor + lead
            val height = maxOf(0.0, entry.first)
            cursor = top + height
            boxes.add(ZoneBox(top, height))
        }
        return TopZoneBox(cursor, appBar, boxes, cursor)
    }

    /** How far the keyboard lifts the bottom zone: only by the part by which it is taller than the navigation bar. */
    fun keyboardLift(keyboard: Double, navBarHeight: Double): Double = maxOf(0.0, keyboard - maxOf(0.0, navBarHeight))
}
