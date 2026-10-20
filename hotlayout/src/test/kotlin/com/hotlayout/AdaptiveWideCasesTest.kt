package com.hotlayout

import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidthClass
import com.hotlayout.model.WidthRules
import com.hotlayout.render.adaptive.NavPlace
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.adaptive.wideNavMetrics
import com.hotlayout.render.screen.AnatomyInput
import com.hotlayout.render.screen.AnatomyZones
import com.hotlayout.render.screen.AppBarRules
import com.hotlayout.render.screen.BottomZoneInput
import com.hotlayout.render.screen.ScreenAnatomy
import com.hotlayout.render.screen.TopZoneOwner
import com.hotlayout.render.screen.ZoneBox
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wide places of the adaptive table (step 2): where the bar of navigation stands, the items at the top, the zones with
 * the bar at the top, a sheet that becomes a window. The rows run through the rules; the geometry also through the real
 * layouts in `AdaptiveLayoutTest` and `AdaptiveWideLayoutTest`.
 */
class AdaptiveWideCasesTest {
    companion object {
        private fun rows(group: String) = AdaptiveCasesTest.rows(group)

        fun number(value: JsonValue?): Double = value?.number ?: 0.0

        val defaults: JsonObject get() = AdaptiveCasesTest.table["wideDefaults"]?.obj ?: JsonObject()

        internal val navMetrics: WideChrome.NavMetrics
            get() = defaults["nav"].let { WideChrome.NavMetrics(number(it?.get("paddingInline")), number(it?.get("iconWidth")), number(it?.get("gap")), number(it?.get("between"))) }

        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = TableJson.obj(*pairs)
        fun num(value: Double) = JsonValue.Num(value)

        /** Every key the table names must be there with that value (numbers within a hundredth). */
        fun assertSubset(actual: JsonValue?, expected: JsonValue, label: String) {
            val fields = expected.obj
            when {
                fields != null -> for ((key, value) in fields) assertSubset(actual?.get(key), value, "$label.$key")
                expected is JsonValue.Num -> assertEquals(label, expected.value, actual?.number ?: Double.NaN, 0.01)
                else -> assertEquals(label, expected, actual ?: JsonValue.Null)
            }
        }

        /** The same structure and the same words; numbers within a thousandth. */
        fun assertSame(expected: JsonValue?, actual: JsonValue?, label: String) {
            when {
                expected is JsonValue.Obj -> {
                    assertEquals("$label: keys", expected.fields.keys.toSet(), (actual?.obj?.keys ?: emptyList()).toSet())
                    for ((key, value) in expected.fields) assertSame(value, actual?.get(key), "$label.$key")
                }
                expected is JsonValue.Arr -> {
                    assertEquals("$label: size", expected.items.size, actual?.array?.size)
                    expected.items.zip(actual?.array.orEmpty()).forEachIndexed { i, (a, b) -> assertSame(a, b, "$label[$i]") }
                }
                expected is JsonValue.Num -> assertEquals(label, expected.value, actual?.number ?: Double.NaN, 0.001)
                else -> assertEquals(label, expected ?: JsonValue.Null, actual ?: JsonValue.Null)
            }
        }

        internal fun appBarInput(row: JsonObject, width: Double): WideChrome.WideAppBarInput {
            val bar = row["bar"]?.obj ?: JsonObject()
            val d = defaults["bar"]?.obj ?: JsonObject()
            return WideChrome.WideAppBarInput(
                AppBarRules.ZonesInput(
                    width, bar["showBack"]?.bool == true, number(d["backSide"]), bar["actionSides"]?.array.orEmpty().mapNotNull { it.number }, number(d["gap"]),
                    number(d["actionGap"]), number(bar["titleNatural"]), bar["leadingNatural"]?.number, bar["titleAlign"],
                ),
                row["labels"]?.array.orEmpty().mapNotNull { it.number }, navMetrics,
            )
        }

        /** The box of the content of the app bar on the screen: from the left edge. */
        internal fun barBox(row: JsonObject): WidthRules.InlineRect {
            val padding = row["padding"]?.obj ?: JsonObject()
            return WidthRules.contentRects(
                number(row["width"]), number(padding["start"]), number(padding["end"]), WidthRules.contentMaxWidth(row["contentMaxWidth"]),
                rightToLeft = row["rtl"]?.bool == true,
            ).appBar
        }

        /** A part of the row from its start in reading order, as a rectangle from the left edge of the screen. */
        internal fun fromLeft(box: WidthRules.InlineRect, start: Double, width: Double, rtl: Boolean): JsonValue =
            obj("x" to num(if (rtl) box.x + box.width - start - width else box.x + start), "width" to num(width))

        internal fun zoneChildren(row: JsonObject): List<DocumentNode> {
            val has = row["has"]?.obj ?: JsonObject()
            val nodes = ArrayList<DocumentNode>()
            if (has["appBar"]?.bool == true) nodes.add(DocumentNode("bar", "AppBar"))
            nodes.add(DocumentNode("list", "Column"))
            if (has["pinned"]?.bool == true) nodes.add(DocumentNode("cta", "Button", fields = JsonObject.of("pin" to "bottom")))
            if (has["navBar"]?.bool == true) {
                val props = row["widePlacement"]?.let { JsonObject.of("widePlacement" to it) } ?: JsonObject()
                nodes.add(DocumentNode("nav", "NavBar", props = props))
            }
            return nodes
        }

        internal fun zoneInput(row: JsonObject, place: NavPlace?): Pair<AnatomyInput, Double?> {
            val zone = defaults["zone"]?.obj ?: JsonObject()
            val has = row["has"]?.obj ?: JsonObject()
            val padding = zone["padding"]?.obj ?: JsonObject()
            val body = row["bodyContentHeight"]
            val bodyHeight = if (body == JsonValue.Null) null else (body?.number ?: number(zone["bodyContentHeight"]))
            val pinned = if (has["pinned"]?.bool == true) number(zone["pinnedHeight"]) else null
            val navHeight = when (place) {
                NavPlace.Bottom -> number(zone["navBarBottomHeight"])
                NavPlace.Top -> number(zone["navBarTopHeight"])
                else -> null
            }
            val input = AnatomyInput(
                height = number(zone["height"]), appBarHeight = if (has["appBar"]?.bool == true) number(zone["appBarHeight"]) else null,
                paddingTop = number(padding["top"]), paddingBottom = number(padding["bottom"]), gap = number(zone["gap"]),
                bodyContentHeight = bodyHeight, scrollTop = row["scrollTop"]?.number,
                bottom = BottomZoneInput(pinned, null, number(zone["safeBottom"])),
            )
            return input to navHeight
        }

        private fun box(box: ZoneBox?): JsonValue = box?.let { obj("top" to num(it.top), "height" to num(it.height)) } ?: JsonValue.Null

        internal fun zonesJson(zones: AnatomyZones): JsonValue {
            val appBar = zones.appBar?.let { obj("top" to num(it.top), "height" to num(it.height), "contentTop" to num(zones.appBarContentTop ?: 0.0)) } ?: JsonValue.Null
            val bottomZone = zones.bottom?.let { zone ->
                val nav = zone.navBar?.let { obj("top" to num(it.top), "height" to num(it.height), "contentHeight" to num(it.contentHeight)) } ?: JsonValue.Null
                obj("top" to num(zone.top), "height" to num(zone.height), "pinned" to box(zone.pinned), "navBar" to nav, "safeBottom" to num(zone.safeBottom))
            } ?: JsonValue.Null
            return obj(
                "height" to num(zones.height), "appBar" to appBar, "body" to box(zones.body), "contentTop" to num(zones.contentTop),
                "contentHeight" to num(zones.contentHeight), "maxScroll" to num(zones.maxScroll), "scrollTop" to num(zones.scrollTop),
                "scrolls" to JsonValue.Bool(zones.scrolls), "bottom" to bottomZone, "float" to JsonValue.Bool(zones.float),
                "bodyAtScreenTop" to JsonValue.Bool(zones.bodyAtScreenTop), "safeTop" to num(zones.safeTop),
            )
        }

        fun layoutRule(dotted: String): JsonValue? {
            var value = AdaptiveCasesTest.schema["x-style"]?.get("layout")
            for (key in dotted.split(".")) value = value?.get(key)
            return value
        }
    }

    // Who stands where

    @Test fun chromeCases() {
        for (row in rows("chromeCases")) {
            val width = AdaptiveCasesTest.widthClass(row["widthClass"])
            val scope = AdaptiveCasesTest.met(row["met"])
            val children = row["children"]?.array.orEmpty().map { AdaptiveCasesTest.node(it) }
            val chrome = WideChrome.screenChrome(children, width) { WidthRules.isShown(it, width, scope) }
            fun id(node: DocumentNode?): JsonValue = node?.let { JsonValue.Str(it.id) } ?: JsonValue.Null
            val expect = row["expect"]
            assertEquals(row.name(), expect?.get("navPlace") ?: JsonValue.Null, chrome.navPlace?.let { JsonValue.Str(it.word) } ?: JsonValue.Null)
            assertEquals(row.name(), expect?.get("appBar") ?: JsonValue.Null, id(chrome.zones.appBar))
            assertEquals(row.name(), expect?.get("pinned") ?: JsonValue.Null, id(chrome.zones.pinned))
            assertEquals(row.name(), expect?.get("navBar") ?: JsonValue.Null, id(chrome.zones.navBar))
            assertEquals(row.name(), expect?.get("body")?.array, chrome.zones.body.map { JsonValue.Str(it.id) })
            // A bar shown on other classes is drawn nowhere here.
            val navShown = expect?.get("navShown")?.bool
            val nav = chrome.zones.navBar
            if (navShown != null && nav != null) assertEquals(row.name(), navShown, WidthRules.isShown(nav, width, scope))
            // The editor keeps the hidden app bar a bar; a reader that knows the data does not.
            expect?.get("editor")?.string?.let { editor ->
                assertEquals(row.name(), editor, WideChrome.screenChrome(children, width).navPlace?.word)
            }
        }
    }

    /** The owner of the top zone follows the place of the bar: only an own bar at the top is the owner. */
    @Test fun topZoneOwnerFollowsThePlace() {
        for (row in rows("chromeCases")) {
            val width = AdaptiveCasesTest.widthClass(row["widthClass"])
            val children = row["children"]?.array.orEmpty().map { AdaptiveCasesTest.node(it) }
            val chrome = WideChrome.screenChrome(children, width) { WidthRules.isShown(it, width, emptyMap()) }
            val barShown = chrome.zones.appBar?.let { WidthRules.isShown(it, width, emptyMap()) } ?: false
            val owner = WideChrome.topZoneOwner(chrome.navPlace, barShown)
            assertEquals(row.name(), owner == TopZoneOwner.NavBar, chrome.navPlace == NavPlace.Top)
        }
    }

    // Items at the top

    @Test fun navItemAndHeightCases() {
        for (row in rows("wideNavItemCases")) {
            val mode = if (row["mode"]?.string == "icons") WideChrome.NavMode.Icons else WideChrome.NavMode.Labels
            assertEquals(row.name(), number(row["expect"]), WideChrome.navItemWidth(number(row["labelWidth"]), navMetrics, mode), 1e-9)
        }
        for (row in rows("wideNavHeightCases")) {
            val args = listOf("paddingBlock", "iconBox", "labelLine").map { number(row[it]) }
            assertEquals(row.name(), number(row["expect"]?.get("item")), WideChrome.navItemHeight(args[0], args[1], args[2]), 1e-9)
            assertEquals(row.name(), number(row["expect"]?.get("bar")), WideChrome.navBarHeight(args[0], args[1], args[2], number(row["borderBottom"])), 1e-9)
        }
    }

    /** The numbers of the table are the numbers of the default theme: the reader reads them from the theme. */
    @Test fun defaultsAreTheThemeOfTheReader() {
        val theme = HotlayoutThemeSet.builtIn.theme(null)
        assertEquals(navMetrics, wideNavMetrics(theme))
        val bar = defaults["bar"]?.obj ?: JsonObject()
        assertEquals(number(bar["gap"]), theme.spacing[AppBarRules.GAP_TOKEN] ?: Double.NaN, 0.0)
        assertEquals(number(bar["actionGap"]), theme.spacing[AppBarRules.ACTION_GAP_TOKEN] ?: Double.NaN, 0.0)
        assertEquals(number(bar["backSide"]), theme.spacing[AppBarRules.BACK_SIDE_TOKEN] ?: Double.NaN, 0.0)
        assertEquals(number(defaults["modalMargin"]), theme.spacing["xl"] ?: Double.NaN, 0.0)
        assertEquals(number(layoutRule("wideNav.item.labelMaxWidth")), WideChrome.LABEL_MAX, 0.0)
        assertEquals(number(layoutRule("wideNav.overflow.titleKeeps")), WideChrome.TITLE_KEEPS, 0.0)
        assertEquals(number(layoutRule("wideModal.window.maxWidth")), WideChrome.WINDOW_MAX_WIDTH, 0.0)
        assertEquals(number(layoutRule("wideModal.window.maxHeightShare")), WideChrome.WINDOW_MAX_HEIGHT_SHARE, 0.0)
    }

    @Test fun appBarWithNavItemsCases() {
        for (row in rows("wideAppBarCases")) {
            val rtl = row["rtl"]?.bool == true
            val box = barBox(row)
            val input = appBarInput(row, box.width)
            val result = WideChrome.appBarZones(input)
            val zones = result.zones
            fun rect(start: Double, width: Double) = fromLeft(box, start, width, rtl)
            val actual = obj(
                "mode" to JsonValue.Str(result.navMode.word), "clipped" to JsonValue.Bool(result.clipped), "truncated" to JsonValue.Bool(zones.truncated),
                "centered" to JsonValue.Bool(zones.centered),
                "leading" to if (zones.leadingShown == AppBarRules.LeadingShown.None) JsonValue.Null else rect(0.0, zones.leadingWidth),
                "title" to rect(zones.titleStart, zones.titleWidth),
                "items" to JsonValue.Arr(result.navItemStarts.indices.map { rect(result.navItemStarts[it], result.navItemWidths[it]) }),
                "actions" to JsonValue.Arr(zones.actionStarts.indices.map { rect(zones.actionStarts[it], input.bar.actionSides[it]) }),
            )
            assertSame(row["expect"], actual, row.name())
        }
    }

    /** Without items the bar is exactly the bar of before the wide places. */
    @Test fun barWithoutItemsIsTheOldBar() {
        for (row in rows("wideAppBarCases")) {
            val input = appBarInput(row, barBox(row).width)
            val bare = WideChrome.WideAppBarInput(input.bar, emptyList(), input.nav)
            val plain = AppBarRules.zones(input.bar)
            val wide = WideChrome.appBarZones(bare)
            assertEquals(row.name(), plain, wide.zones)
        }
    }

    @Test fun ownBarCases() {
        for (row in rows("wideNavBarCases")) {
            val rtl = row["rtl"]?.bool == true
            val inset = row["navBarInset"]?.obj ?: JsonObject()
            val rects = WidthRules.contentRects(
                number(row["width"]), 0.0, 0.0, WidthRules.contentMaxWidth(row["contentMaxWidth"]),
                navBarInset = number(inset["start"]) to number(inset["end"]), rightToLeft = rtl,
            )
            val box = rects.navBar
            val labels = row["labels"]?.array.orEmpty().mapNotNull { it.number }
            val result = WideChrome.navBar(box.width, labels, navMetrics)
            val actual = obj(
                "mode" to JsonValue.Str(result.navMode.word), "clipped" to JsonValue.Bool(result.clipped),
                "items" to JsonValue.Arr(result.navItemStarts.indices.map { fromLeft(box, result.navItemStarts[it], result.navItemWidths[it], rtl) }),
            )
            assertSame(row["expect"], actual, row.name())
        }
    }

    // Zones

    @Test fun wideZoneCases() {
        for (row in rows("wideZoneCases")) {
            val width = AdaptiveCasesTest.widthClass(row["widthClass"])
            val chrome = WideChrome.screenChrome(zoneChildren(row), width)
            val (input, navHeight) = zoneInput(row, chrome.navPlace)
            val result = WideChrome.zones(input, chrome.navPlace, navHeight)
            val base = zonesJson(result.zones).obj ?: JsonObject()
            var actual = base.with("navPlace", chrome.navPlace?.let { JsonValue.Str(it.word) } ?: JsonValue.Null)
                .with("topNav", result.topNav?.let { obj("top" to num(it.top), "height" to num(it.height)) } ?: JsonValue.Null)
            result.zones.appBar?.let { actual = actual.with("appBar", obj("top" to num(it.top), "height" to num(it.height))) }
            assertSubset(JsonValue.Obj(actual), row["expect"] ?: JsonValue.Null, row.name())
            assertEquals(row.name(), number(defaults["zone"]?.get("height")), result.zones.height, 0.001)
        }
    }

    /** A place at the bottom and a screen without a bar are exactly the zones of before: the numbers do not change. */
    @Test fun bottomPlaceIsTheOldZones() {
        for (row in rows("wideZoneCases")) {
            val word = row["expect"]?.get("navPlace")?.string
            if (word != null && word != "bottom") continue
            val place = word?.let { w -> NavPlace.entries.first { it.word == w } }
            val (input, navHeight) = zoneInput(row, place)
            val pinned = input.bottom?.pinnedHeight
            val old = input.copy(bottom = if (pinned == null && navHeight == null) null else BottomZoneInput(pinned, navHeight, input.bottom?.safeBottom))
            assertEquals(row.name(), ScreenAnatomy.zones(old), WideChrome.zones(input, place, navHeight).zones)
        }
    }

    // Modal layer

    @Test fun wideModalCases() {
        val margin = number(defaults["modalMargin"])
        for (row in rows("wideModalCases")) {
            val viewport = row["viewport"]?.obj ?: JsonObject()
            val layer = WideChrome.modalLayerBox(row["placement"], number(viewport["width"]), number(viewport["height"]), number(row["contentHeight"]), margin)
            val actual = obj(
                "shape" to JsonValue.Str(layer.shape.word), "x" to num(layer.box.x), "y" to num(layer.box.y), "width" to num(layer.box.width), "height" to num(layer.box.height),
                "scrolls" to JsonValue.Bool(layer.box.scrolls), "contentWidthClass" to JsonValue.Str(layer.contentWidthClass.word),
            )
            assertSubset(actual, row["expect"] ?: JsonValue.Null, row.name())
            assertEquals(row.name(), layer.box.width, WideChrome.modalLayerWidth(row["placement"], number(viewport["width"]), margin), 1e-9)
        }
    }

    @Test fun wideModalRadiusCases() {
        for (row in rows("wideModalRadiusCases")) assertEquals(row.name(), row["expect"] ?: JsonValue.Null, WideChrome.sheetWindowRadius(row["radius"]) ?: JsonValue.Null)
    }

    @Test fun wideModalContentCases() {
        for (row in rows("wideModalContentCases")) {
            val presentation = if (row["presentation"]?.string == "modal") ScreenPresentation.Modal else ScreenPresentation.Page
            val area = WidthClass.of(number(row["areaWidth"]))
            assertEquals(row.name(), row["areaClass"]?.string, area.word)
            val expect = row["expect"]
            assertEquals(row.name(), expect?.get("contentWidthClass")?.string, WideChrome.contentWidthClass(presentation, area).word)
            assertEquals(row.name(), expect?.get("alwaysCompact")?.bool, presentation == ScreenPresentation.Modal)
        }
    }

    // Data of the artifact

    @Test fun wideRuleCases() {
        for (row in rows("wideRuleCases")) assertEquals(row.name(), row["expect"], layoutRule(row["rule"]?.string ?: ""))
        val wide = layoutRule("wideNav")
        assertEquals(listOf("top", "bottom"), wide?.get("values")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(WidthClass.entries.filter { it.isWide }.map { it.word }, wide?.get("classes")?.array.orEmpty().mapNotNull { it.string })
        assertEquals("top", wide?.get("default")?.string)
    }

    @Test fun placementWords() {
        assertTrue(WideChrome.keepsBottom(JsonObject.of("widePlacement" to "bottom")))
        for (props in listOf(JsonObject(), JsonObject.of("widePlacement" to "top"), JsonObject.of("widePlacement" to "side"), JsonObject.of("widePlacement" to 3), JsonObject.of("widePlacement" to null))) {
            assertFalse(WideChrome.keepsBottom(props))
        }
        assertNotNull(WideChrome.navPlace(WidthClass.Medium, keepsBottom = false, appBarShown = true))
    }
}
