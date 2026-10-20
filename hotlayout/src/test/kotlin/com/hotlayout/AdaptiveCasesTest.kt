package com.hotlayout

import com.hotlayout.actions.ScreenForm
import com.hotlayout.data.Conditions
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidthClass
import com.hotlayout.model.WidthRules
import com.hotlayout.model.inWidthClass
import com.hotlayout.render.screen.AppBarRules
import com.hotlayout.render.screen.NavBarRules
import com.hotlayout.render.screen.ScreenAnatomy
import com.hotlayout.render.widgets.ListMarkerRules
import com.hotlayout.render.widgets.TabsRules
import com.hotlayout.render.widgets.gridColumns
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.name
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive table by width (`fixtures/adaptive/adaptive-cases.json`) as functions of the rules: the class of a width,
 * the layers of a class, the style and the props of a node in a class, who is shown, who is drawn and counted, the zones,
 * the form, the data block of the artifact and the explicit «nothing» of a layer (`unset`). The geometry rows run again
 * through the real screen in `AdaptiveLayoutTest`; the wide places are in `AdaptiveWideCasesTest`.
 */
class AdaptiveCasesTest {
    companion object {
        val table: JsonValue get() = Contract.json("fixtures/adaptive/adaptive-cases.json")
        val schema: JsonValue get() = Contract.json("screen-schema.json")

        fun rows(group: String) = Contract.rows(table, group)

        internal fun widthClass(value: JsonValue?): WidthClass = WidthClass.named(value?.string) ?: WidthClass.Compact

        /** The data in which every listed path holds (`isTrue`), as the tables say «met». */
        fun met(value: JsonValue?): DataScope = value?.array.orEmpty().mapNotNull { it.string }.associateWith { JsonValue.Bool(true) as JsonValue }

        /** A node of a row; the type and the id are given when the row does not name them. */
        internal fun node(value: JsonValue?, type: String = "Container", id: String = "node"): DocumentNode {
            var obj = value?.obj ?: JsonObject()
            if (obj["id"] == null) obj = obj.with("id", JsonValue.Str(id))
            if (obj["type"] == null) obj = obj.with("type", JsonValue.Str(type))
            return ScreenSim.node(JsonValue.Obj(obj))
        }
    }

    @Test fun tableShape() {
        val counts = mapOf(
            "classCases" to 20, "layerCases" to 3, "styleCases" to 22, "propsCases" to 27, "showCases" to 22, "drawnCases" to 7, "numberCases" to 5, "tabCases" to 4,
            "weightCases" to 5, "zoneCases" to 5, "formCases" to 6, "validityCases" to 39, "contentCases" to 22, "gridCases" to 12, "boxCases" to 12, "ruleCases" to 20,
            "chromeCases" to 35, "wideNavItemCases" to 7, "wideNavHeightCases" to 3, "wideAppBarCases" to 23, "wideNavBarCases" to 7, "wideZoneCases" to 27,
            "wideModalCases" to 14, "wideModalRadiusCases" to 9, "wideSectionCases" to 10, "wideModalContentCases" to 10, "olderReaderShowOnCases" to 10,
            "wideRuleCases" to 21, "unsetStyleCases" to 14, "unsetStateCases" to 3, "unsetPropsCases" to 7, "unsetDocumentCases" to 12, "unsetScreenCases" to 5,
        )
        for ((group, count) in counts) {
            assertEquals(group, count, rows(group).size)
            assertEquals("$group: names repeat", count, rows(group).map { it.name() }.toSet().size)
        }
        assertEquals(448, counts.values.sum())
    }

    /** Every group of the table is run by a reader test or named here with the reason. */
    @Test fun everyGroupIsRunOrNamed() {
        val run = setOf(
            "classCases", "layerCases", "styleCases", "propsCases", "showCases", "drawnCases", "numberCases", "tabCases", "weightCases", "zoneCases",
            "formCases", "validityCases", "contentCases", "gridCases", "boxCases", "ruleCases", "chromeCases", "wideNavItemCases", "wideNavHeightCases",
            "wideAppBarCases", "wideNavBarCases", "wideZoneCases", "wideModalCases", "wideModalRadiusCases", "wideModalContentCases", "wideRuleCases",
            "unsetStyleCases", "unsetStateCases", "unsetPropsCases",
        )
        val notRun = setOf("wideSectionCases", "olderReaderShowOnCases", "unsetDocumentCases", "unsetScreenCases")
        assertEquals(Contract.groups(table), run + notRun)
        assertTrue(run.intersect(notRun).isEmpty())
    }

    // Class and cascade

    @Test fun classCases() {
        for (row in rows("classCases")) assertEquals(row.name(), row["expect"]?.string, WidthClass.of(row["width"]?.number).word)
        for (width in WidthClass.entries) {
            assertEquals(width, WidthClass.of(width.from))
            if (width.from > 0) assertTrue(WidthClass.of(width.from - 0.001) != width)
        }
        assertEquals(WidthClass.Compact, WidthClass.of(Double.NaN))
        assertEquals(WidthClass.Compact, WidthClass.of(Double.POSITIVE_INFINITY))
        assertEquals(WidthClass.Compact, WidthClass.of(-1.0))
        assertEquals(WidthClass.Compact, WidthClass.of(null))
    }

    @Test fun layerCases() {
        for (row in rows("layerCases")) {
            assertEquals(row.name(), row["expect"]?.array.orEmpty().mapNotNull { it.string }, widthClass(row["widthClass"]).layers.map { it.word })
        }
    }

    // Style and props

    @Test fun styleCases() {
        for (row in rows("styleCases")) {
            val node = node(row["node"]).inWidthClass(widthClass(row["widthClass"]))
            assertEquals(row.name(), row["expect"]?.obj ?: JsonObject(), NodeStyle.effective(node, met(row["met"])))
        }
    }

    @Test fun propsCases() {
        for (row in rows("propsCases")) {
            val node = node(row["node"]).inWidthClass(widthClass(row["widthClass"]))
            assertEquals(row.name(), row["expect"]?.obj, node.props)
        }
    }

    /** The tree is resolved: no override is left in a resolved node, and a document without any override is the same tree in every class. */
    @Test fun resolvedTreeCarriesNoOverrides() {
        val node = node(
            JsonValue.parse(
                """{"type":"Grid","props":{"columns":2},"propsOn":{"expanded":{"columns":4}},"style":{"padding":"md"},"styleOn":{"expanded":{"padding":"xl"}},
                   "children":[{"id":"k","type":"Column","props":{"gap":"sm"},"propsOn":{"medium":{"gap":"lg"}}}]}""",
            ),
        )
        val wide = node.inWidthClass(WidthClass.Expanded)
        assertNull(wide.fields["propsOn"])
        assertNull(wide.fields["styleOn"])
        assertNull(wide.children[0].fields["propsOn"])
        assertEquals(JsonValue.Num(4.0), wide.props["columns"])
        assertEquals(JsonValue.Str("lg"), wide.children[0].props["gap"])
        assertTrue(wide.columnsFromOverride)
        assertEquals(node, node.inWidthClass(WidthClass.Compact))
        val plain = node(JsonValue.parse("""{"type":"Column","props":{"gap":"sm"},"style":{"padding":"md"}}"""))
        for (width in WidthClass.entries) {
            assertEquals(plain.props, plain.inWidthClass(width).props)
            assertEquals(plain.style, plain.inWidthClass(width).style)
        }
    }

    /** The columns of the base keep their bounds 2…4, a number of a layer is read as 1…6. */
    @Test fun columnsBounds() {
        fun columns(json: String, width: WidthClass): Int? {
            val node = node(JsonValue.parse(json), "Grid").inWidthClass(width)
            return gridColumns(node.props["columns"], node.columnsFromOverride)
        }
        assertEquals(4, columns("""{"type":"Grid","props":{"columns":9}}""", WidthClass.Expanded))
        assertEquals(2, columns("""{"type":"Grid","props":{"columns":1}}""", WidthClass.Compact))
        val layered = """{"type":"Grid","props":{"columns":2},"propsOn":{"medium":{"columns":1},"expanded":{"columns":99}}}"""
        assertEquals(2, columns(layered, WidthClass.Compact))
        assertEquals(1, columns(layered, WidthClass.Medium))
        assertEquals(6, columns(layered, WidthClass.Expanded))
    }

    @Test fun screenContentWidth() {
        val screen = node(JsonValue.parse("""{"type":"Screen","props":{"gap":"sm"},"propsOn":{"expanded":{"contentMaxWidth":1120}}}"""), "Screen")
        assertNull(screen.inWidthClass(WidthClass.Medium).props["contentMaxWidth"])
        assertEquals(JsonValue.Num(1120.0), screen.inWidthClass(WidthClass.Expanded).props["contentMaxWidth"])
        // Outside the bounds it is clamped; not a whole number, it is no field.
        val cases: List<Pair<JsonValue?, Double?>> = listOf(
            JsonValue.Num(100.0) to 320.0, JsonValue.Num(5000.0) to 1920.0, JsonValue.Num(900.5) to null, JsonValue.Str("900") to null,
            null to null, JsonValue.Null to null, JsonValue.Num(1120.0) to 1120.0,
        )
        for ((value, want) in cases) assertEquals("$value", want, WidthRules.contentMaxWidth(value))
    }

    // Visibility

    @Test fun showCases() {
        for (row in rows("showCases")) {
            val node = node(row["node"])
            val width = widthClass(row["widthClass"])
            val scope = met(row["met"])
            assertEquals(row.name(), row["expect"]?.bool, WidthRules.isShown(node, width, scope))
            // The two halves are independent: a class never shows what a condition hid, and vice versa.
            val byClass = WidthRules.showOn(node.fields)?.contains(width) ?: true
            val byData = Conditions.isVisible(node.visibleIf, scope)
            assertEquals(row.name(), row["expect"]?.bool, byClass && byData)
        }
    }

    @Test fun drawnCases() {
        for (row in rows("drawnCases")) {
            val width = widthClass(row["widthClass"])
            val scope = met(row["met"])
            val visible = row["items"]?.array.orEmpty().map { WidthRules.isShown(node(it), width, scope) }
            val drawn = if (row["kind"]?.string == "appBarActions") AppBarRules.drawn(visible) else NavBarRules.drawn(visible)
            assertEquals(row.name(), row["expect"]?.array.orEmpty().map { it.bool }, drawn)
        }
    }

    @Test fun numberCases() {
        for (row in rows("numberCases")) {
            val width = widthClass(row["widthClass"])
            val scope = met(row["met"])
            val items = row["items"]?.array.orEmpty().map { item ->
                val marker = item["marker"]
                val obj = (item.obj ?: JsonObject()).with("marker", null)
                    .with("props", JsonValue.Obj(JsonObject.of("content" to mapOf("kind" to "literal", "value" to "x"), "marker" to (marker ?: JsonValue.Null))))
                node(JsonValue.Obj(obj), "Text")
            }
            val markers = items.map { ListMarkerRules.drawn(it.props["marker"]) }
            val visible = items.map { WidthRules.isShown(it, width, scope) }
            val numbers = items.indices.map { ListMarkerRules.number(markers, it, visible) }
            assertEquals(row.name(), row["expect"]?.array.orEmpty().map { it.number?.toInt() }, numbers)
        }
    }

    @Test fun tabCases() {
        for (row in rows("tabCases")) {
            val width = widthClass(row["widthClass"])
            val scope = met(row["met"])
            val visible = row["tabs"]?.array.orEmpty().map { WidthRules.isShown(node(it, "Tab"), width, scope) }
            assertEquals(row.name(), row["expect"]?.number?.toInt(), TabsRules.openTabIndex(row["initialTab"], visible))
        }
    }

    @Test fun zoneCases() {
        for (row in rows("zoneCases")) {
            val width = widthClass(row["widthClass"])
            val scope = met(row["met"])
            val children = row["children"]?.array.orEmpty().map { node(it) }
            val zones = ScreenAnatomy.childrenByZone(children)
            fun id(node: DocumentNode?): JsonValue = node?.let { JsonValue.Str(it.id) } ?: JsonValue.Null
            fun shown(node: DocumentNode?): JsonValue = node?.let { JsonValue.Bool(WidthRules.isShown(it, width, scope)) } ?: JsonValue.Null
            val actual = JsonValue.Obj(
                JsonObject(
                    listOf(
                        "appBar" to id(zones.appBar), "pinned" to id(zones.pinned), "navBar" to id(zones.navBar),
                        "body" to JsonValue.Arr(zones.body.map { JsonValue.Str(it.id) }),
                        "shown" to JsonValue.Obj(
                            JsonObject(
                                listOf(
                                    "appBar" to shown(zones.appBar), "pinned" to shown(zones.pinned), "navBar" to shown(zones.navBar),
                                    "body" to JsonValue.Arr(zones.body.map { shown(it) }),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(row.name(), row["expect"], actual)
        }
    }

    @Test fun formCases() {
        for (row in rows("formCases")) {
            val width = widthClass(row["widthClass"])
            var values = met(row["met"])
            for ((path, value) in row["values"]?.obj ?: JsonObject()) values = values + (path to value)
            val fields = row["fields"]?.array.orEmpty().map { value ->
                var obj = value.obj ?: JsonObject()
                val id = obj["id"] ?: JsonValue.Str("?")
                val path = obj["path"] ?: JsonValue.Str("?")
                val under = obj["under"]
                obj = obj.with("path", null).with("under", null).with("type", JsonValue.Str("TextField"))
                    .with("props", JsonValue.Obj(JsonObject.of("label" to mapOf("kind" to "literal", "value" to "x"), "value" to mapOf("kind" to "binding", "path" to path), "required" to true)))
                if (under == null) {
                    JsonValue.Obj(obj)
                } else {
                    val wrapper = (under.obj ?: JsonObject()).with("id", JsonValue.Str((id.string ?: "") + "-wrap")).with("type", JsonValue.Str("Column"))
                        .with("props", JsonValue.Obj(JsonObject())).with("children", JsonValue.Arr(listOf(JsonValue.Obj(obj))))
                    JsonValue.Obj(wrapper)
                }
            }
            val root = ScreenSim(fields).root
            val counted = ScreenForm.requiredFields(root, width).filter { !it.hiddenByClass }.map { it.nodeId }
            assertEquals(row.name(), row["expect"]?.get("required")?.array.orEmpty().mapNotNull { it.string }, counted)
            assertEquals(row.name(), row["expect"]?.get("filled")?.bool, ScreenForm.isFilled(root, values, width))
        }
    }

    // The data block of the artifact

    private fun widthBlock(path: String): JsonValue? {
        var value = schema["x-style"]?.get("layout")?.get("widthClass")
        for (part in path.split(".")) {
            val index = part.toIntOrNull()
            value = if (index != null) value?.array?.getOrNull(index) else value?.get(part)
        }
        return value
    }

    @Test fun ruleCases() {
        for (row in rows("ruleCases")) assertEquals(row.name(), row["expect"], widthBlock(row["rule"]?.string ?: ""))
        // The numbers of the reader are the numbers of the artifact.
        for (entry in widthBlock("classes")?.array.orEmpty()) assertEquals(WidthClass.named(entry["class"]?.string)?.from, entry["from"]?.number)
        assertEquals(widthBlock("contentMaxWidth.min")?.number, WidthRules.CONTENT_MAX_MIN)
        assertEquals(widthBlock("contentMaxWidth.max")?.number, WidthRules.CONTENT_MAX_MAX)
        assertEquals(1.0, widthBlock("props.columns.min")?.number)
        assertEquals(6.0, widthBlock("props.columns.max")?.number)
        val listed = widthBlock("props.byWidget")?.array.orEmpty().mapNotNull { it.obj }.associate { entry ->
            (entry["type"]?.string ?: "") to entry["props"]?.array.orEmpty().mapNotNull { it.string }
        }
        assertEquals(listed, WidthRules.responsiveProps)
        assertEquals(listOf("base") + WidthClass.entries.drop(1).map { it.word }, widthBlock("cascade.order")?.array?.mapNotNull { it.string })
        val features = schema["x-compatibility"]?.get("features")
        for (key in listOf("widthClassVisibility", "widthClassStyle", "widthClassProps", "screenContentMaxWidth")) assertEquals(key, "9.18.0", features?.get(key)?.string)
    }

    // Explicit «nothing» in a layer

    @Test fun unsetStyleCases() {
        for (row in rows("unsetStyleCases")) {
            val node = node(row["node"])
            for (width in WidthClass.entries) {
                // `null` in the table is «no style».
                assertEquals("${row.name()}: $width", row["expected"]?.get(width.word) ?: JsonValue.Null, node.inWidthClass(width).style ?: JsonValue.Null)
            }
        }
    }

    @Test fun unsetStateCases() {
        for (row in rows("unsetStateCases")) {
            val node = node(row["node"]).inWidthClass(widthClass(row["widthClass"]))
            val scope: DataScope = if (row["met"]?.bool == true) mapOf("task.completed" to JsonValue.Bool(true)) else emptyMap()
            assertEquals(row.name(), row["expected"]?.obj, NodeStyle.effective(node, scope))
        }
    }

    @Test fun unsetPropsCases() {
        for (row in rows("unsetPropsCases")) {
            val node = node(row["node"], row["node"]?.get("type")?.string ?: "Screen")
            val prop = row["prop"]?.string ?: ""
            for (width in WidthClass.entries) {
                assertEquals("${row.name()}: $width", row["expected"]?.get(width.word) ?: JsonValue.Null, node.inWidthClass(width).props[prop] ?: JsonValue.Null)
            }
        }
    }

    /** The names of `unset` are read as the reader reads them: not strings are skipped, a field outside the closed list of the widget is not removed. */
    @Test fun unsetReading() {
        val carrier = JsonValue.parse("""{"unset":{"expanded":["padding",3,null,"background"],"medium":"padding"}}""")
        assertEquals(listOf("padding", "background"), WidthRules.unsetNames(carrier, WidthClass.Expanded))
        assertEquals(emptyList<String>(), WidthRules.unsetNames(carrier, WidthClass.Medium))
        assertEquals(emptyList<String>(), WidthRules.unsetNames(null, WidthClass.Expanded))
        val removed = WidthRules.effectiveProps("Column", JsonObject.of("gap" to "sm"), JsonValue.parse("""{"unset":{"expanded":["gap"]}}"""), WidthClass.Expanded)
        assertEquals(JsonValue.Str("sm"), removed.props["gap"])
    }

    /** A field of the wrong shape is read as a missing field and never stops the tree. */
    @Test fun hostileShapesDoNotBreakTheResolution() {
        val hostile = listOf(
            """{"showOn":"expanded"}""", """{"showOn":[1,null,{}]}""", """{"showOn":[]}""", """{"styleOn":5}""",
            """{"styleOn":{"expanded":"x","medium":null,"huge":{"padding":"xl"}}}""", """{"propsOn":"wide"}""", """{"propsOn":{"expanded":7}}""",
            """{"propsOn":{"expanded":{"gap":{"a":1},"columns":"many","direction":3}}}""", """{"propsOn":{"unset":3}}""",
            """{"propsOn":{"unset":{"expanded":"gap"}}}""", """{"styleOn":{"unset":{"expanded":[1,null,"padding"]}}}""",
        )
        for (extra in hostile) {
            var obj = JsonObject.of("id" to "g", "type" to "Grid", "props" to mapOf("columns" to 2))
            for ((key, value) in JsonValue.parse(extra).obj ?: JsonObject()) obj = obj.with(key, value)
            val node = ScreenSim.node(JsonValue.Obj(obj))
            for (width in WidthClass.entries) {
                val resolved = node.inWidthClass(width)
                assertTrue(extra, gridColumns(resolved.props["columns"], resolved.columnsFromOverride) != null)
            }
        }
    }

    /** The `styleOn` rows of the glass table: the field reads in a layer like any other field of the style. */
    @Test fun glassInAWiderClass() {
        val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme(null))
        val row = Contract.rows(Contract.json("fixtures/motion/motion-cases.json"), "glassCases").first { it.name() == "glass on a wider class" }
        assertEquals("styleOn", row["where"]?.string)
        val node = node(
            JsonValue.Obj(JsonObject.of("style" to mapOf("padding" to "md"), "styleOn" to mapOf("medium" to mapOf("backdropBlur" to (row["value"] ?: JsonValue.Null))))),
        )
        for (width in WidthClass.entries) {
            val look = resolver.box(NodeStyle.effective(node.inWidthClass(width), emptyMap()), emptyMap())
            assertEquals(width.word, if (width == WidthClass.Compact) null else row["value"]?.number?.toInt(), look.backdropBlur)
        }
        assertFalse(WidthRules.showsOn(node, WidthClass.Compact).not())
    }
}
