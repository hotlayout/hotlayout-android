package com.hotlayout.render.screen

import com.hotlayout.JsonValue
import com.hotlayout.render.adaptive.WideChrome

/**
 * The bottom safe inset of a modal sheet (`x-style.layout.sheetSafeBottom`).
 *
 * A sheet reaches the physical bottom of its area, and the gesture bar or the navigation buttons lie over
 * it. The reader adds the inset of the platform INSIDE the box of the sheet, under its content: the
 * background and the radius of the root reach the bottom edge, the last node stands above the bar. The inset
 * is added once: with a shown pinned block it goes under the content of that zone (as on a page), without one
 * it is the bottom padding of the root. A window (`center`), a sheet shown as a window in a wider place and a
 * page get nothing new. A host that has accounted for the inset itself gives zero.
 */
internal object SheetSafeBottom {
    /** Where the inset goes: the bottom padding of the root, or the room under the content of the bottom zone. Never both. */
    data class Place(val root: Double, val zone: Double)

    private val NONE = Place(0.0, 0.0)

    /** The inset that counts: not a number, not finite and negative are zero. */
    fun inset(value: JsonValue?): Double = value?.number?.takeIf { it > 0 } ?: 0.0

    /** For a layer already known to be a sheet: under the zone when there is one, in the root otherwise. */
    fun placeOf(inset: Double, bottomZone: Boolean): Place = when {
        inset <= 0 -> NONE
        bottomZone -> Place(0.0, inset)
        else -> Place(inset, 0.0)
    }

    /** Where the reader adds the bottom inset of a screen shown as [presentation] with [placement] in an area [areaWidth] wide. */
    fun of(presentation: JsonValue?, placement: JsonValue?, areaWidth: Double, safeBottom: JsonValue?, bottomZone: Boolean): Place {
        val value = inset(safeBottom)
        if (presentation?.string != "modal") return if (bottomZone && value > 0) Place(0.0, value) else NONE
        if (WideChrome.modalShape(placement, areaWidth) != WideChrome.ModalShape.Sheet) return NONE
        return placeOf(value, bottomZone)
    }

    /** The box of a sheet, in logical pixels from the top of the area. */
    data class Box(val y: Double, val height: Double, val contentBottom: Double, val scrolls: Boolean)

    /** The box with the inset inside: content plus the inset, no taller than the area; taller content scrolls above the inset. */
    fun box(areaHeight: Double, contentHeight: Double, safeBottom: JsonValue?): Box {
        val area = maxOf(0.0, areaHeight)
        val inset = minOf(inset(safeBottom), area)
        val natural = maxOf(0.0, contentHeight) + inset
        val height = minOf(natural, area)
        return Box(area - height, height, height - inset, natural > area)
    }
}
