package com.hotlayout.render.adaptive

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.ScreenPresentation
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidthClass
import com.hotlayout.render.modal.ModalRules
import com.hotlayout.render.screen.AnatomyInput
import com.hotlayout.render.screen.AnatomyZones
import com.hotlayout.render.screen.AppBarRules
import com.hotlayout.render.screen.BottomZoneInput
import com.hotlayout.render.screen.ScreenAnatomy
import com.hotlayout.render.screen.TopZoneOwner
import com.hotlayout.render.screen.ZoneBox

/** Where the navigation bar stands on a screen (`x-style.layout.wideNav.place`). */
internal enum class NavPlace(val word: String) {
    /** The bottom zone, as on a phone. */
    Bottom("bottom"),

    /** The items stand inside the app bar, in one row with the title. */
    AppBar("appBar"),

    /** A bar of its own in the app bar zone (the screen has no shown app bar). */
    Top("top"),
}

/** The children of the root of a screen by zone in a width class, and where the navigation bar stands there. `navPlace` is `null` when the screen has no navigation bar. */
internal class ScreenChrome(val zones: ScreenAnatomy.ChildrenByZone, val navPlace: NavPlace?)

/**
 * The wide places (`x-style.layout.wideNav`, `wideModal`): where the navigation bar stands and what a modal sheet
 * becomes on a medium or an expanded class. The document does not change: the place is chosen by the reader from
 * the width class of the place the screen is shown in.
 */
internal object WideChrome {
    // Who stands where

    /** The word of `NavBar.widePlacement`: `bottom` keeps the bar down on every width; any other word, and no field, is `top`. */
    fun keepsBottom(props: JsonObject): Boolean = props["widePlacement"]?.string == "bottom"

    /**
     * Who stands where in a class — THE one function that answers it (`screenChromeIn`). The zones are the document's;
     * a class does not move children between them. New is the place of the bar: down on compact and with
     * `widePlacement: bottom`, otherwise up — into the app bar when the screen has one that is SHOWN (class and
     * condition), else as a bar of its own. A hidden app bar is no zone, and items left in it would vanish with it.
     */
    fun screenChrome(
        children: List<DocumentNode>,
        widthClass: WidthClass,
        rootProps: JsonObject = JsonObject(),
        isShown: (DocumentNode) -> Boolean = { true },
    ): ScreenChrome {
        val zones = ScreenAnatomy.childrenByZone(children, rootProps)
        val nav = zones.navBar ?: return ScreenChrome(zones, null)
        return ScreenChrome(zones, navPlace(widthClass, keepsBottom(nav.props), zones.appBar?.let(isShown) ?: false))
    }

    /** The place of a bar the screen has: down on compact and with `widePlacement: bottom`, otherwise up. */
    fun navPlace(widthClass: WidthClass, keepsBottom: Boolean, appBarShown: Boolean): NavPlace = when {
        !widthClass.isWide || keepsBottom -> NavPlace.Bottom
        appBarShown -> NavPlace.AppBar
        else -> NavPlace.Top
    }

    /** Who owns the top zone of the screen in a class. */
    fun topZoneOwner(navPlace: NavPlace?, appBarShown: Boolean): TopZoneOwner = when {
        navPlace == NavPlace.Top -> TopZoneOwner.NavBar
        appBarShown -> TopZoneOwner.AppBar
        else -> TopZoneOwner.None
    }

    // Navigation items at the top

    /** The most a title keeps while the labels of the items are still shown. */
    const val TITLE_KEEPS = 120.0

    /** The widest label of an item at the top; a longer one is cut with an ellipsis. */
    const val LABEL_MAX = 160.0

    enum class NavMode(val word: String) { Labels("labels"), Icons("icons") }

    /** Spacing and widths of an item at the top, in logical pixels of the theme. */
    data class NavMetrics(
        /** `item.paddingInline`. */
        val paddingInline: Double,
        /** The width of the box of the glyph. */
        val iconWidth: Double,
        /** The gap between the glyph and the label. */
        val gap: Double,
        /** The gap between items. */
        val between: Double,
    ) {
        /** What an item takes besides its label: padding, glyph, gap. */
        val fixed: Double get() = 2 * paddingInline + iconWidth + gap
    }

    /** The width of one item: padding, glyph, (gap, label at most [LABEL_MAX]), padding. */
    fun navItemWidth(labelWidth: Double, metrics: NavMetrics, mode: NavMode): Double {
        val box = metrics.paddingInline + metrics.iconWidth + metrics.paddingInline
        if (mode == NavMode.Icons) return box
        return box + metrics.gap + minOf(maxOf(0.0, labelWidth), LABEL_MAX)
    }

    /** The width of the group of items with the gaps; without items, zero. */
    fun navGroupWidth(labels: List<Double>, metrics: NavMetrics, mode: NavMode): Double {
        if (labels.isEmpty()) return 0.0
        return labels.sumOf { navItemWidth(it, metrics, mode) } + (labels.size - 1) * metrics.between
    }

    fun navItemHeight(paddingBlock: Double, iconBox: Double, labelLine: Double): Double = paddingBlock + maxOf(iconBox, labelLine) + paddingBlock

    fun navBarHeight(paddingBlock: Double, iconBox: Double, labelLine: Double, borderBottom: Double): Double =
        navItemHeight(paddingBlock, iconBox, labelLine) + borderBottom

    private class ItemBoxes(val starts: List<Double>, val widths: List<Double>)

    private fun itemStarts(from: Double, labels: List<Double>, metrics: NavMetrics, mode: NavMode): ItemBoxes {
        var cursor = from
        val widths = labels.map { navItemWidth(it, metrics, mode) }
        val starts = widths.map { width -> cursor.also { cursor += width + metrics.between } }
        return ItemBoxes(starts, widths)
    }

    class WideAppBarInput(
        val bar: AppBarRules.ZonesInput,
        /** Natural widths of the labels of the DRAWN items, in document order. */
        val navLabels: List<Double>,
        val nav: NavMetrics,
    )

    class WideAppBarZones(
        val zones: AppBarRules.Zones,
        val navMode: NavMode,
        /** The offset of the group from the START of the row (reading order) and its width. */
        val navStart: Double,
        val navWidth: Double,
        val navItemStarts: List<Double>,
        val navItemWidths: List<Double>,
        /** Even with icons alone the row is wider than the bar: the end edge cuts the rest. */
        val clipped: Boolean,
    )

    private fun AppBarRules.ZonesInput.with(
        actionSides: List<Double> = this.actionSides,
        titleNatural: Double = this.titleNatural,
        leadingNatural: Double? = this.leadingNatural,
    ) = AppBarRules.ZonesInput(width, showBack, backSide, actionSides, gap, actionGap, titleNatural, leadingNatural, titleAlign)

    /**
     * The zones of an app bar with the navigation items. Items and actions are ONE block of the trailing zone —
     * «items, the gap of the bar, actions» — and the rest is the rule of the bar as it was; new is the look of the
     * items and their places inside the block. The labels are shown when everything fits together: the leading zone,
     * the title no narrower than [TITLE_KEEPS] (or than itself, when shorter) and the trailing zone with labels. Only
     * natural widths are read, which do not depend on the chosen look: a measure must not change what it measures.
     */
    fun appBarZones(input: WideAppBarInput): WideAppBarZones {
        val bar = input.bar
        val actions = bar.actionSides.sum() + maxOf(0, bar.actionSides.size - 1) * bar.actionGap
        fun block(mode: NavMode): Double {
            val group = navGroupWidth(input.navLabels, input.nav, mode)
            return group + (if (group > 0 && actions > 0) bar.gap else 0.0) + actions
        }
        val plain = AppBarRules.zones(bar.with(actionSides = emptyList(), titleNatural = 0.0))
        val need = minOf(maxOf(0.0, bar.titleNatural), TITLE_KEEPS)
        fun trailing(block: Double) = if (block > 0) block + bar.gap else 0.0
        val mode = if (plain.leading + need + trailing(block(NavMode.Labels)) <= bar.width) NavMode.Labels else NavMode.Icons
        val total = block(mode)
        val tail = trailing(total)
        // The node of the leading zone shrinks last of what shrinks: when the title has nothing left to give it takes what the items and actions left.
        val leadingRoom = maxOf(0.0, bar.width - tail - bar.gap)
        val leadingNatural = bar.leadingNatural?.let { minOf(it, leadingRoom) }
        var zones = AppBarRules.zones(bar.with(actionSides = if (total > 0) listOf(total) else emptyList(), leadingNatural = leadingNatural))
        val navWidth = navGroupWidth(input.navLabels, input.nav, mode)
        // The block stands at the END of the row; when it does not fit it starts right after the leading zone and the end edge cuts the rest.
        val navStart = maxOf(bar.width - total, zones.leading)
        val items = itemStarts(navStart, input.navLabels, input.nav, mode)
        var cursor = navStart + total - actions
        val actionStarts = bar.actionSides.map { side -> cursor.also { cursor += side + bar.actionGap } }
        zones = zones.copy(actionStarts = actionStarts)
        return WideAppBarZones(zones, mode, navStart, navWidth, items.starts, items.widths, zones.leading + tail > bar.width)
    }

    class NavBarBox(val navMode: NavMode, val navWidth: Double, val navItemStarts: List<Double>, val navItemWidths: List<Double>, val clipped: Boolean)

    /** The items of a bar of its own: from the start; with labels when they fit, else icons; if even those do not fit the end edge cuts the rest. */
    fun navBar(width: Double, labels: List<Double>, metrics: NavMetrics): NavBarBox {
        val mode = if (navGroupWidth(labels, metrics, NavMode.Labels) <= width) NavMode.Labels else NavMode.Icons
        val total = navGroupWidth(labels, metrics, mode)
        val items = itemStarts(0.0, labels, metrics, mode)
        return NavBarBox(mode, total, items.starts, items.widths, total > width)
    }

    // Zones with the bar at the top

    class WideZones(val zones: AnatomyZones, /** The bar of its own at the top; `null`: the bar is down, in the app bar or absent. */ val topNav: ZoneBox?)

    /**
     * The zones of a screen with the place of the bar: `bottom` — the numbers of a phone; `appBar` — there is no bar in
     * the bottom zone (it is only the pinned block, and without that block no zone and no safe inset); `top` — the bar
     * takes the top of the screen for its height, and everything else is counted in what is left under it, the top
     * padding of the root staying with the body.
     */
    fun zones(input: AnatomyInput, navPlace: NavPlace?, navBarHeight: Double?): WideZones {
        if (navPlace != NavPlace.Top) {
            val pinned = input.bottom?.pinnedHeight
            val nav = if (navPlace == NavPlace.Bottom) navBarHeight else null
            val bottom = if (pinned == null && nav == null) null else BottomZoneInput(pinned, nav, input.bottom?.safeBottom)
            return WideZones(ScreenAnatomy.zones(input.copy(bottom = bottom)), null)
        }
        val bar = maxOf(0.0, navBarHeight ?: 0.0)
        val pinned = input.bottom?.pinnedHeight
        val bottom = input.bottom?.let { if (pinned == null) null else BottomZoneInput(pinned, null, it.safeBottom) }
        val under = ScreenAnatomy.zones(input.copy(appBarHeight = null, height = maxOf(0.0, input.height - bar), bottom = bottom)).lowered(bar)
        return WideZones(under, ZoneBox(0.0, bar))
    }

    // Modal layer in a wide place

    /** The widest window a sheet becomes on a wide place. */
    const val WINDOW_MAX_WIDTH = 560.0

    /** The largest share of the height of the area such a window takes. */
    const val WINDOW_MAX_HEIGHT_SHARE = 0.85

    enum class ModalShape(val word: String) { Sheet("sheet"), Window("window"), SheetWindow("sheetWindow") }

    /** What shows a layer: `center` is a window on every width; any other word, and no field, is a sheet on compact and a window in a wider place. [areaWidth] is the box the host gave the screen. */
    fun modalShape(placement: JsonValue?, areaWidth: Double): ModalShape = when {
        ModalRules.isWindow(placement) -> ModalShape.Window
        WidthClass.of(areaWidth).isWide -> ModalShape.SheetWindow
        else -> ModalShape.Sheet
    }

    /** The width of the layer is known before its content is measured, and the class of the width of the content comes from it. */
    fun layerWidth(shape: ModalShape, areaWidth: Double, margin: Double): Double =
        if (shape == ModalShape.SheetWindow) maxOf(0.0, minOf(areaWidth - 2 * margin, WINDOW_MAX_WIDTH)) else ModalRules.width(shape == ModalShape.Window, areaWidth, margin)

    fun modalLayerWidth(placement: JsonValue?, areaWidth: Double, margin: Double): Double = layerWidth(modalShape(placement, areaWidth), areaWidth, margin)

    /** The most height a layer of this shape has in an area [height] tall. */
    fun layerRoom(shape: ModalShape, height: Double, margin: Double): Double = when (shape) {
        ModalShape.Sheet -> height
        ModalShape.Window -> maxOf(0.0, height - 2 * margin)
        ModalShape.SheetWindow -> maxOf(0.0, minOf(height * WINDOW_MAX_HEIGHT_SHARE, height - 2 * margin))
    }

    class LayerBox(val box: ModalRules.Box, val shape: ModalShape, val contentWidthClass: WidthClass)

    fun layerBox(shape: ModalShape, width: Double, height: Double, contentHeight: Double, margin: Double): LayerBox {
        if (shape != ModalShape.SheetWindow) {
            val box = ModalRules.box(shape == ModalShape.Window, width, height, contentHeight, margin)
            return LayerBox(box, shape, WidthClass.of(box.width))
        }
        val layerWidth = layerWidth(shape, width, margin)
        val available = layerRoom(shape, height, margin)
        val layerHeight = minOf(maxOf(0.0, contentHeight), available)
        val box = ModalRules.Box((width - layerWidth) / 2, (height - layerHeight) / 2, layerWidth, layerHeight, contentHeight > available)
        return LayerBox(box, shape, WidthClass.of(layerWidth))
    }

    fun modalLayerBox(placement: JsonValue?, width: Double, height: Double, contentHeight: Double, margin: Double): LayerBox =
        layerBox(modalShape(placement, width), width, height, contentHeight, margin)

    /**
     * The radius of the root of a sheet shown as a window: a bottom corner the style did NOT name takes the radius of
     * the top corner of its side; a named one (zero too) stays; a single value for all corners and no radius stay as they are.
     */
    fun sheetWindowRadius(radius: JsonValue?): JsonValue? {
        var corners = radius?.obj ?: return radius
        (corners["bottomStart"] ?: corners["topStart"])?.let { corners = corners.with("bottomStart", it) }
        (corners["bottomEnd"] ?: corners["topEnd"])?.let { corners = corners.with("bottomEnd", it) }
        return JsonValue.Obj(corners)
    }

    /** The width class of the content of a screen in a place of class [area]: a page takes the class of the place, a modal layer is always compact (its box is narrower than 600 anywhere). */
    fun contentWidthClass(presentation: ScreenPresentation, area: WidthClass): WidthClass =
        if (presentation == ScreenPresentation.Modal) WidthClass.Compact else area
}
