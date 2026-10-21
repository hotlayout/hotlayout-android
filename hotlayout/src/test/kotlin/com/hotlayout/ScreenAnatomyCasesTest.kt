package com.hotlayout

import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.actions.ListInstance
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.adaptive.NavPlace
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.screen.AnatomyInput
import com.hotlayout.render.screen.AnatomyZones
import com.hotlayout.render.screen.AppBarRules
import com.hotlayout.render.screen.BottomZoneInput
import com.hotlayout.render.screen.GlyphGeometry
import com.hotlayout.render.screen.NavBarRules
import com.hotlayout.render.screen.ScreenAnatomy
import com.hotlayout.render.screen.ZoneBox
import com.hotlayout.support.Contract
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared tables of the anatomy of a screen as functions of the rules: the zones of the screen
 * (`media-chrome`, `bottom-zone`), the top zone (`top-zone`), the app bar, the navigation bar and
 * the dot on its items. The same zones are proved on the real screen in `ScreenAnatomyWiringTest`.
 */
class ScreenAnatomyCasesTest {
    private val chrome = Contract.json("fixtures/media-chrome/media-chrome-cases.json")
    private val bottom = Contract.json("fixtures/bottom-zone/bottom-zone-cases.json")
    private val top = Contract.json("fixtures/top-zone/top-zone-cases.json")
    private val schema = Contract.json("screen-schema.json")

    private fun number(value: JsonValue?): Double = value?.number ?: 0.0

    private fun input(row: JsonObject): AnatomyInput {
        val padding = row["padding"]?.obj ?: JsonObject()
        val bottomRow = row["bottom"]?.obj
        return AnatomyInput(
            height = number(row["height"]), shrink = row["shrink"]?.bool == true, appBarHeight = row["appBarHeight"]?.number,
            paddingTop = number(padding["top"]), paddingBottom = number(padding["bottom"]), gap = number(row["gap"]),
            bodyContentHeight = row["bodyContentHeight"]?.number, firstPull = row["firstPull"]?.number, scrollTop = row["scrollTop"]?.number,
            bottom = bottomRow?.let { BottomZoneInput(it["pinnedHeight"]?.number, it["navBarHeight"]?.number, it["safeBottom"]?.number) },
            navFloat = row["navFloat"]?.bool == true, bodyTop = row["bodyTop"], safeTop = row["safeTop"]?.number,
        )
    }

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = TableJson.obj(*pairs)
    private fun num(value: Double) = JsonValue.Num(value)

    private fun box(box: ZoneBox?): JsonValue = box?.let { obj("top" to num(it.top), "height" to num(it.height)) } ?: JsonValue.Null

    /** The zones as JSON, in the shape of the table. */
    private fun json(zones: AnatomyZones): JsonValue {
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

    /** Every key the table names must be there with that value (numbers within a hundredth). */
    private fun assertSubset(actual: JsonValue?, expected: JsonValue, label: String) {
        val fields = expected.obj
        when {
            fields != null -> for ((key, value) in fields) assertSubset(actual?.get(key), value, "$label.$key")
            expected is JsonValue.Num -> assertEquals(label, expected.value, actual?.number ?: Double.NaN, 0.01)
            else -> assertEquals(label, expected, actual ?: JsonValue.Null)
        }
    }

    private fun node(json: JsonValue): DocumentNode {
        val fields0 = json.obj ?: JsonObject()
        val fields = ArrayList<Pair<String, JsonValue>>()
        fields0["pin"]?.let { fields.add("pin" to it) }
        return DocumentNode(fields0["id"]?.string ?: "", fields0["type"]?.string ?: "", fields = JsonObject(fields))
    }

    /** A dotted path from `x-style.layout` of the artifact. */
    private fun layoutRule(dotted: String): JsonValue? {
        var value = schema["x-style"]?.get("layout")
        for (key in dotted.split(".")) value = value?.get(key)
        return value
    }

    private fun rule(block: String, path: String): JsonValue? = layoutRule(if (path.isEmpty()) block else "$block.$path")

    private fun rows(table: JsonValue, group: String) = Contract.rows(table, group)

    @Test fun tableShape() {
        val expected = listOf(
            chrome to mapOf(
                "screenAppBarCases" to 7, "zoneCases" to 19, "appBarCases" to 15, "appBarDrawnCases" to 7, "appBarLeadingCases" to 48, "appBarLeadingShownCases" to 6,
                "appBarChildrenCases" to 10, "screenRuleCases" to 16, "placementCases" to 7, "statusBarCases" to 7, "shownCases" to 11, "bodyTopCases" to 7,
                "topZoneCases" to 11, "screenTopZoneCases" to 22, "screenTopPressCases" to 25, "safeTopValueCases" to 10, "scrolledThresholdCases" to 9,
                "scrolledLayerCases" to 4, "screenTopRuleCases" to 27, "appBar916RuleCases" to 19, "glyphCases" to 32,
            ),
            bottom to mapOf(
                "childrenCases" to 14, "pinCases" to 8, "zoneCases" to 18, "navHeightCases" to 4, "navShareCases" to 12, "activeCases" to 8, "sectionCases" to 5,
                "ruleCases" to 45, "floatZoneCases" to 9, "floatPlaceCases" to 7, "navBadgeCases" to 9, "navBadgeBoxCases" to 4, "nav922RuleCases" to 12,
                "navBadgeMotionCases" to 6, "navBadgeAppearScaleCases" to 6, "navBadgeDisappearOpacityCases" to 4,
            ),
            top to mapOf("childrenCases" to 10, "pinCases" to 5, "geometryCases" to 6, "documentCases" to 5, "ruleCases" to 8),
        )
        for ((table, groups) in expected) for ((group, count) in groups) assertEquals(group, count, rows(table, group).size)
    }

    // Who is in which zone

    private fun childrenJson(zones: ScreenAnatomy.ChildrenByZone, withTop: Boolean): JsonValue {
        fun ids(nodes: List<DocumentNode>) = JsonValue.Arr(nodes.map { JsonValue.Str(it.id) })
        fun id(node: DocumentNode?) = node?.let { JsonValue.Str(it.id) } ?: JsonValue.Null
        val pairs = arrayListOf("appBar" to id(zones.appBar), "body" to ids(zones.body), "pinned" to id(zones.pinned), "navBar" to id(zones.navBar))
        if (withTop) pairs.add("topPinned" to ids(zones.topPinned))
        return obj(*pairs.toTypedArray())
    }

    @Test fun bottomChildrenCases() {
        for (row in rows(bottom, "childrenCases")) {
            val zones = ScreenAnatomy.childrenByZone(row["children"]?.array.orEmpty().map(::node))
            assertSubset(childrenJson(zones, false), row["expect"] ?: JsonValue.Null, row.name())
        }
    }

    @Test fun topChildrenCases() {
        for (row in rows(top, "childrenCases")) {
            val zones = ScreenAnatomy.childrenByZone(row["children"]?.array.orEmpty().map(::node), row["rootProps"]?.obj ?: JsonObject())
            assertSubset(childrenJson(zones, true), row["expect"] ?: JsonValue.Null, row.name())
        }
    }

    @Test fun pinCases() {
        for (row in rows(bottom, "pinCases")) {
            val fields = row["pin"]?.let { JsonObject(listOf("pin" to it)) } ?: JsonObject()
            val word = ScreenAnatomy.pinOf(DocumentNode("n", "Text", fields = fields))
            assertEquals(row.name(), row["expect"] ?: JsonValue.Null, word?.let { JsonValue.Str(it) } ?: JsonValue.Null)
        }
        for (row in rows(top, "pinCases")) {
            val node = DocumentNode("n", "Text", fields = row["node"]?.obj ?: JsonObject())
            assertEquals(row.name(), row["expect"]?.bool, ScreenAnatomy.topPinOf(node))
        }
    }

    /** A node in the run is a node of the top zone; a word after a child without it is an ordinary child (the validator refuses to publish it). */
    @Test fun topDocumentCases() {
        for (row in rows(top, "documentCases")) {
            val zones = ScreenAnatomy.childrenByZone(row["children"]?.array.orEmpty().map(::node), row["rootProps"]?.obj ?: JsonObject())
            val id = row["node"]?.string ?: ""
            // The zone exists only among the children of a screen root; the word anywhere else is not read.
            val inRun = (row["parentType"]?.string ?: "Screen") == "Screen" && zones.topPinned.any { it.id == id }
            assertEquals(row.name(), row["expect"]?.isNull != false, inRun)
        }
    }

    // Zones

    @Test fun zoneCases() {
        val all = rows(chrome, "zoneCases") + rows(bottom, "zoneCases") + rows(bottom, "floatZoneCases") + rows(chrome, "screenTopZoneCases")
        assertEquals(19 + 18 + 9 + 22, all.size)
        for (row in all) assertSubset(json(ScreenAnatomy.zones(input(row))), row["expect"] ?: JsonValue.Null, row.name())
    }

    @Test fun topZoneGeometry() {
        for (row in rows(top, "geometryCases")) {
            val input = row["input"]?.obj ?: JsonObject()
            val zone = ScreenAnatomy.topZone(
                ScreenAnatomy.TopZoneInput(
                    input["appBarHeight"]?.number,
                    input["pinned"]?.array.orEmpty().mapNotNull { it.obj }.map { number(it["height"]) to it["pull"]?.number },
                    number(input["rootPaddingTop"]), number(input["gap"]), input["safeTop"]?.number,
                ),
            )
            val actual = obj(
                "height" to num(zone.height), "appBar" to box(zone.appBar), "pinned" to JsonValue.Arr(zone.pinned.map { box(it) }), "bodyTop" to num(zone.bodyTop),
            )
            assertSubset(actual, row["expect"] ?: JsonValue.Null, row.name())
        }
    }

    @Test fun pressCases() {
        for (row in rows(chrome, "screenTopPressCases")) {
            val screen = row["screen"]?.obj ?: JsonObject()
            val side = screen["sidePadding"]?.obj ?: JsonObject()
            val rtl = screen["direction"]?.string == "rtl"
            val zones = ScreenAnatomy.zones(input(screen))
            val point = row["point"]?.obj ?: JsonObject()
            val inline = number(if (rtl) side["end"] else side["start"]) to (number(screen["width"]) - number(side["start"]) - number(side["end"]))
            val zone = ScreenAnatomy.pressZone(zones, point["x"]?.number, number(point["y"]), inline)
            assertEquals(row.name(), row["expect"]?.string, zone.word)
        }
    }

    @Test fun bodyTopAndSafeTopCases() {
        for (row in rows(chrome, "bodyTopCases")) {
            assertEquals(row.name(), row["expect"] ?: JsonValue.Null, ScreenAnatomy.bodyTopWord(row["value"])?.let { JsonValue.Str(it) } ?: JsonValue.Null)
        }
        for (row in rows(chrome, "safeTopValueCases")) {
            assertEquals(row.name(), number(row["effective"]), ScreenAnatomy.safeTop(row["safeTop"]?.number, number(row["height"])), 1e-9)
        }
        assertEquals(600.0, ScreenAnatomy.safeTop(1.0e300, 600.0), 0.0)
        assertEquals(0.0, ScreenAnatomy.safeTop(Double.POSITIVE_INFINITY, 600.0), 0.0)
        assertEquals(0.0, ScreenAnatomy.safeTop(Double.NaN, 600.0), 0.0)
    }

    @Test fun topZoneOwnerCases() {
        for (row in rows(chrome, "topZoneCases")) {
            val types = row["children"]?.array.orEmpty().mapNotNull { it.string }
            val hidden = row["hidden"]?.array.orEmpty().mapNotNull { it.number?.toInt() }.toSet()
            val appBarShown = types.firstOrNull() == "AppBar" && 0 !in hidden
            val navBar = types.lastOrNull() == "NavBar" && (types.size - 1) !in hidden
            val place = NavBarRules.place(row["widthClass"]?.string ?: "compact", row["widePlacement"], appBarShown)
            val owner = WideChrome.topZoneOwner(if (navBar && place == "top") NavPlace.Top else null, appBarShown)
            assertEquals(row.name(), row["owner"]?.string, owner.word)
            assertEquals(row.name(), row["atScreenTop"]?.bool, ScreenAnatomy.bodyStartsAtScreenTop(JsonValue.Str("screenTop"), owner))
        }
    }

    @Test fun screenAppBarCases() {
        for (row in rows(chrome, "screenAppBarCases")) {
            val nodes = row["children"]?.array.orEmpty().mapNotNull { it.string }.mapIndexed { index, type -> DocumentNode("n$index", type) }
            val bar = ScreenAnatomy.appBarOf(nodes)
            val index = bar?.let { found -> nodes.indexOfFirst { it.id == found.id } }
            assertEquals(row.name(), row["expect"] ?: JsonValue.Null, index?.let { num(it.toDouble()) } ?: JsonValue.Null)
        }
    }

    // Status bar, placement, the shown event

    @Test fun placementAndStatusBarCases() {
        for (row in rows(chrome, "placementCases")) assertEquals(row.name(), row["expect"]?.string, ScreenAnatomy.modalPlacement(row["value"]))
        for (row in rows(chrome, "statusBarCases")) {
            assertEquals(row.name(), row["expect"] ?: JsonValue.Null, ScreenAnatomy.statusBarIcons(row["value"])?.let { JsonValue.Str(it) } ?: JsonValue.Null)
        }
    }

    /** The event of a shown screen carries the request about the status bar icons and, for a layer, where it lies. */
    @Test fun shownCases() {
        for (row in rows(chrome, "shownCases")) {
            val stack = row["stack"]?.array.orEmpty().mapNotNull { it.obj }
            fun entry(item: JsonObject) = FlowDocument.Screen(
                item["screenId"]?.string ?: "", if (item["presentation"]?.string == "modal") ScreenPresentation.Modal else ScreenPresentation.Page,
                item["modalPlacement"]?.string, ScreenAnatomy.statusBarIcons(item["statusBarIcons"]),
            )
            val screens = ArrayList(stack.map(::entry))
            val transition = row["transition"]?.string
            // A closed layer leaves the page beneath: the table lists the stack AFTER the pop.
            if (transition == "pop") screens.add(FlowDocument.Screen("layer", ScreenPresentation.Modal))
            val flow = Flow(FlowDocument("d", screens, screens.firstOrNull()?.screenId, emptyMap()), null)
            val shown = when (transition) {
                "start" -> flow.shownEvent(ScreenShownCause.Entry)
                "push" -> {
                    val target = stack.last()
                    val instances = target["record"]?.obj?.let { record ->
                        val fields = JsonObject(record.map { (key, value) -> key.substringAfter('.') to value })
                        listOf(ListInstance(record.first().first.substringBefore('.'), fields))
                    }.orEmpty()
                    flow.press(JsonObject.of("type" to "navigate", "params" to mapOf("screenId" to (target["screenId"]?.string ?: ""))), instances).outcome.shown
                }
                "pop" -> {
                    flow.press(JsonObject.of("type" to "navigate", "params" to mapOf("screenId" to "layer")))
                        .flow.press(JsonObject.of("type" to "navigate.back")).outcome.shown
                }
                else -> error("unknown transition: ${row.name()}")
            }
            assertEquals(row.name(), row["expect"], TableJson.shown(shown))
        }
    }

    // The app bar

    private fun zonesInput(row: JsonObject) = AppBarRules.ZonesInput(
        width = number(row["width"]), showBack = row["showBack"]?.bool == true, backSide = 24.0,
        actionSides = row["actionSides"]?.array.orEmpty().map { number(it) }, gap = 8.0, actionGap = 4.0,
        titleNatural = number(row["titleNatural"]), leadingNatural = row["leadingNatural"]?.number, titleAlign = row["titleAlign"],
    )

    @Test fun appBarZoneCases() {
        for (row in rows(chrome, "appBarCases") + rows(chrome, "appBarLeadingCases")) {
            val zones = AppBarRules.zones(zonesInput(row))
            val actual = obj(
                "leading" to num(zones.leading), "trailing" to num(zones.trailing), "titleRoom" to num(zones.titleRoom), "titleWidth" to num(zones.titleWidth),
                "titleStart" to num(zones.titleStart), "centered" to JsonValue.Bool(zones.centered), "truncated" to JsonValue.Bool(zones.truncated),
                "actionStarts" to JsonValue.Arr(zones.actionStarts.map { num(it) }), "leadingShown" to JsonValue.Str(zones.leadingShown.word),
                "leadingWidth" to num(zones.leadingWidth),
            )
            for ((key, value) in row["expect"]?.obj ?: JsonObject()) {
                val items = value.array
                if (items != null) {
                    val got = actual[key]?.array.orEmpty()
                    assertEquals("${row.name()}.$key", items.size, got.size)
                    for ((a, b) in got.zip(items)) assertSubset(a, b, "${row.name()}.$key")
                } else {
                    assertSubset(actual[key], value, "${row.name()}.$key")
                }
            }
        }
    }

    @Test fun appBarDrawnCases() {
        for (row in rows(chrome, "appBarDrawnCases")) {
            val visible = row["visible"]?.array.orEmpty().map { it.bool == true }
            val drawn = AppBarRules.drawn(visible)
            assertEquals(row.name(), row["expect"]?.get("drawn")?.array.orEmpty().map { it.bool == true }, drawn)
            val sides = drawn.filter { it }.map { number(row["actionSide"]) }
            val room = AppBarRules.zones(
                AppBarRules.ZonesInput(number(row["width"]), row["showBack"]?.bool == true, 24.0, sides, 8.0, 4.0, number(row["titleNatural"])),
            ).titleRoom
            assertEquals(row.name(), number(row["expect"]?.get("titleRoom")), room, 0.01)
        }
    }

    @Test fun appBarLeadingShownAndChildrenCases() {
        for (row in rows(chrome, "appBarLeadingShownCases")) {
            assertEquals(row.name(), row["expect"]?.string, AppBarRules.leadingShown(row["nodeVisible"]?.bool, row["showBack"]?.bool == true).word)
        }
        for (row in rows(chrome, "appBarChildrenCases")) {
            val nodes = row["children"]?.array.orEmpty().mapNotNull { it.obj }.map { child ->
                DocumentNode("n", child["type"]?.string ?: "", fields = child["zone"]?.let { JsonObject(listOf("zone" to it)) } ?: JsonObject())
            }
            assertEquals(row.name(), row["leading"] ?: JsonValue.Null, AppBarRules.leadingIndex(nodes)?.let { num(it.toDouble()) } ?: JsonValue.Null)
            assertEquals(row.name(), row["actions"]?.array.orEmpty(), AppBarRules.actionIndexes(nodes).map { num(it.toDouble()) })
        }
    }

    // Scrolled

    @Test fun scrolledCases() {
        for (row in rows(chrome, "scrolledThresholdCases")) {
            assertEquals(row.name(), number(row["threshold"]), AppBarRules.scrolledThreshold(number(row["barBottom"]), number(row["bodyTop"])), 0.0)
            assertEquals(row.name(), row["expected"]?.bool, AppBarRules.isScrolled(number(row["scrollTop"]), number(row["barBottom"]), number(row["bodyTop"])))
        }
        assertFalse(AppBarRules.isScrolled(Double.NaN, 0.0, 0.0))
        val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())
        for (row in rows(chrome, "scrolledLayerCases")) {
            var base = row["base"]?.obj ?: JsonObject()
            // The state of `styleWhen` is laid over the base before the layer.
            for ((key, value) in row["state"]?.obj ?: JsonObject()) base = base.with(key, value)
            val node = DocumentNode("b", "AppBar", fields = JsonObject(listOf("styleScrolled" to (row["scrolled"] ?: JsonValue.Null))))
            val result = AppBarRules.withScrolledLayer(base, AppBarRules.scrolledStyle(node), resolver)
            assertEquals(row.name(), row["expected"], JsonValue.Obj(result))
        }
    }

    @Test fun scrolledLayerKeepsWhatItCannotRead() {
        val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())
        val style = JsonObject.of("color" to "noSuchToken", "shadow" to "low", "padding" to "md")
        val layer = AppBarRules.scrolledStyle(DocumentNode("b", "AppBar", fields = JsonObject.of("styleScrolled" to style.toMap())))
        assertNotNull(layer)
        assertNull("a box key is not a key of the layer", layer!!["padding"])
        val result = AppBarRules.withScrolledLayer(JsonObject.of("color" to "onSurface"), layer, resolver)
        assertEquals("a token the theme does not know is dropped and the field under it stays", JsonValue.Str("onSurface"), result["color"])
        assertEquals(JsonValue.Str("low"), result["shadow"])
        assertNull(AppBarRules.scrolledStyle(DocumentNode("b", "AppBar", fields = JsonObject.of("styleScrolled" to emptyMap<String, Any>()))))
    }

    // The navigation bar

    @Test fun navHeightAndShareCases() {
        for (row in rows(bottom, "navHeightCases")) {
            val m = row["metrics"]?.obj ?: JsonObject()
            val metrics = NavBarRules.Metrics(number(m["paddingBlock"]), number(m["iconBox"]), number(m["gap"]), number(m["labelLine"]), number(m["borderTop"]))
            assertEquals(row.name(), number(row["expect"]), NavBarRules.height(metrics), 1e-9)
        }
        for (row in rows(bottom, "navShareCases")) {
            val shares = NavBarRules.shares(number(row["width"]), row["visible"]?.array.orEmpty().map { it.bool == true }, row["rtl"]?.bool == true)
            val expected = row["expect"]?.array.orEmpty()
            assertEquals(row.name(), expected.size, shares.size)
            for ((share, want) in shares.zip(expected)) {
                if (want.isNull) assertNull(row.name(), share) else {
                    assertEquals(row.name(), want["start"]?.number, share?.start)
                    assertEquals(row.name(), want["width"]?.number, share?.width)
                }
            }
        }
    }

    @Test fun activeAndSectionCases() {
        for (row in rows(bottom, "activeCases")) {
            assertEquals(row.name(), row["expect"]?.bool, NavBarRules.isActive(row["action"]?.obj, row["shown"]?.string ?: ""))
        }
        for (row in rows(bottom, "sectionCases")) {
            val stack = row["stack"]?.array.orEmpty().mapNotNull { it.string }
            val target = row["target"]?.string ?: ""
            val result = NavBarRules.sectionStack(stack, target)
            assertEquals(row.name(), row["expect"] ?: JsonValue.Null, result?.let { JsonValue.Arr(it.map { id -> JsonValue.Str(id) }) } ?: JsonValue.Null)
            // The flow does the same: one screen, cause `switch`, no event when nothing changes.
            val screens = (stack + target).distinct().sorted()
            var flow = Flow(FlowDocument("d", screens.map { FlowDocument.Screen(it, ScreenPresentation.Page) }, stack.first(), emptyMap()), null)
            for (screen in stack.drop(1)) flow = flow.press(JsonObject.of("type" to "navigate", "params" to mapOf("screenId" to screen))).flow
            val (switched, outcome) = flow.press(JsonObject.of("type" to "navigate.section", "params" to mapOf("screenId" to target)))
            assertEquals(row.name(), result ?: stack, switched.stack.map { it.screenId })
            assertEquals(row.name(), result == null, outcome.shown == null)
            if (result != null) assertEquals(row.name(), ScreenShownCause.Switch, outcome.shown?.cause)
        }
    }

    @Test fun floatPlaceCases() {
        for (row in rows(bottom, "floatPlaceCases")) {
            assertEquals(row.name(), row["expect"]?.bool, NavBarRules.floats(row["navPlace"]?.string ?: "", row["props"]?.obj ?: JsonObject()))
        }
    }

    @Test fun badgeCases() {
        for (row in rows(bottom, "navBadgeCases")) {
            assertEquals(row.name(), row["expect"]?.bool, NavBarRules.badgeShown(row["props"]?.obj ?: JsonObject(), Contract.scope(row["data"])))
        }
        for (row in rows(bottom, "navBadgeBoxCases")) {
            val glyph = row["glyph"]?.obj ?: JsonObject()
            val box = NavBarRules.badgeBox(number(glyph["left"]), number(glyph["top"]), number(glyph["size"]), row["rtl"]?.bool == true)
            val expected = row["expect"]
            assertEquals(row.name(), listOf(number(expected?.get("left")), number(expected?.get("top")), number(expected?.get("size"))), listOf(box.left, box.top, box.size))
        }
        for (row in rows(bottom, "navBadgeMotionCases")) {
            val input = row["input"]?.obj ?: JsonObject()
            val change = NavBarRules.badgeChange(input["shownBefore"]?.bool == true, input["before"]?.bool == true, input["after"]?.bool == true, input["reducedMotion"]?.bool == true)
            assertEquals(row.name(), row["expect"]?.string, change.word)
        }
        for (row in rows(bottom, "navBadgeAppearScaleCases")) assertEquals(row.name(), number(row["expect"]), NavBarRules.appearScale(number(row["t"])), 1e-9)
        for (row in rows(bottom, "navBadgeDisappearOpacityCases")) assertEquals(row.name(), number(row["expect"]), NavBarRules.disappearOpacity(number(row["t"])), 1e-9)
    }

    // The own glyph size

    @Test fun glyphCases() {
        for (row in rows(chrome, "glyphCases")) {
            val typography = row["typography"]
            val pad = number(row["padding"])
            val fontSize = number(typography?.get("fontSize"))
            val line = fontSize * number(typography?.get("lineHeight"))
            val button = row["widget"]?.string == "IconButton"
            val result = GlyphGeometry.of(
                GlyphGeometry.Input(
                    glyphSize = row["glyphSize"]?.number, fontSize = fontSize, paddingInline = 2 * pad, paddingBlock = 2 * pad, borderWidth = number(row["border"]),
                    width = row["style"]?.get("width")?.number, height = row["style"]?.get("height")?.number,
                    wordWidth = if (button) line else fontSize, wordHeight = line,
                ),
            )
            val expect = row["expect"]
            assertEquals("glyph: ${row.name()}", number(expect?.get("glyph")), result.glyph, 0.01)
            assertEquals("content inline: ${row.name()}", number(expect?.get("content")?.get("inline")), result.contentWidth, 0.01)
            assertEquals("content block: ${row.name()}", number(expect?.get("content")?.get("block")), result.contentHeight, 0.01)
            assertEquals("box inline: ${row.name()}", number(expect?.get("box")?.get("inline")), result.boxWidth, 0.01)
            assertEquals("box block: ${row.name()}", number(expect?.get("box")?.get("block")), result.boxHeight, 0.01)
        }
    }

    // The data of the artifact

    @Test fun ruleCases() {
        for (row in rows(chrome, "screenRuleCases")) assertEquals(row.name(), row["expect"], rule("screen", row["rule"]?.string ?: ""))
        for (row in rows(chrome, "screenTopRuleCases") + rows(chrome, "appBar916RuleCases")) assertEquals(row.name(), row["expect"], layoutRule(row["rule"]?.string ?: ""))
        for (row in rows(bottom, "ruleCases") + rows(bottom, "nav922RuleCases")) {
            assertEquals(row.name(), row["expect"], rule(row["block"]?.string ?: "", row["rule"]?.string ?: ""))
        }
        for (row in rows(top, "ruleCases")) assertEquals(row.name(), row["expect"], rule("topZone", row["rule"]?.string ?: ""))
    }

    /** The numbers the reader keeps are the numbers of the artifact. */
    @Test fun constantsMirrorTheArtifact() {
        assertEquals(rule("appBarLeading", "maxShare")?.number, AppBarRules.LEADING_MAX_SHARE)
        assertEquals(rule("appBar", "maxDrawnActions")?.number, AppBarRules.MAX_DRAWN_ACTIONS.toDouble())
        assertEquals(rule("navBar", "items.max")?.number, NavBarRules.MAX_ITEMS.toDouble())
        assertEquals(rule("navBadge", "diameter")?.number, NavBarRules.DOT_DIAMETER)
        assertEquals(rule("navBadgeMotion", "appear.durationMs")?.number, NavBarRules.APPEAR_MS)
        assertEquals(rule("navBadgeMotion", "disappear.durationMs")?.number, NavBarRules.DISAPPEAR_MS)
        assertEquals(rule("appBar", "backSide")?.string, AppBarRules.BACK_SIDE_TOKEN)
        assertEquals(rule("appBar", "gap")?.string, AppBarRules.GAP_TOKEN)
        assertEquals(rule("appBar", "actionGap")?.string, AppBarRules.ACTION_GAP_TOKEN)
    }

    /** Groups of these tables this reader does not run are named with the reason: a new group fails until somebody decides. */
    @Test fun everyGroupIsRunOrNamed() {
        val chromeRun = setOf(
            "screenAppBarCases", "zoneCases", "appBarCases", "appBarDrawnCases", "appBarLeadingCases", "appBarLeadingShownCases", "appBarChildrenCases",
            "screenRuleCases", "placementCases", "statusBarCases", "bodyTopCases", "topZoneCases", "screenTopZoneCases", "screenTopPressCases",
            "safeTopValueCases", "scrolledThresholdCases", "scrolledLayerCases", "screenTopRuleCases", "appBar916RuleCases", "shownCases", "glyphCases",
        )
        val chromeOther = mapOf(
            "ratioCases" to "images", "imageBoxCases" to "images", "paintCases" to "images", "radiusCases" to "images", "circleCases" to "images",
            "ratioDocumentCases" to "validator", "modalCases" to "modals", "modalAreaCases" to "modals", "modalRuleCases" to "modals",
            "documentCases" to "validator", "appBarPlaceCases" to "editor", "appBar916DocumentCases" to "validator",
            "screenTopDocumentCases" to "validator", "scrolledDocumentCases" to "validator",
        )
        val bottomRun = setOf(
            "childrenCases", "pinCases", "zoneCases", "navHeightCases", "navShareCases", "activeCases", "sectionCases", "ruleCases", "floatZoneCases",
            "floatPlaceCases", "navBadgeCases", "navBadgeBoxCases", "nav922RuleCases", "navBadgeMotionCases", "navBadgeAppearScaleCases", "navBadgeDisappearOpacityCases",
        )
        val topRun = setOf("childrenCases", "pinCases", "geometryCases", "documentCases", "ruleCases")
        val checks = listOf(Triple("media-chrome", chrome, chromeRun to chromeOther.keys), Triple("bottom-zone", bottom, bottomRun to setOf("documentCases")), Triple("top-zone", top, topRun to emptySet()))
        for ((name, table, pair) in checks) {
            val (run, other) = pair
            for (key in table.obj!!.keys.filter { it.endsWith("Cases") }) {
                assertTrue("$name.$key is neither run nor named", key in run || key in other)
            }
            for (key in run + other) assertNotNull("$name.$key is named but not in the table", table[key])
        }
    }
}
