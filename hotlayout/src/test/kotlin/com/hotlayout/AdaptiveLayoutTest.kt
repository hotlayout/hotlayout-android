package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hotlayout.model.WidthClass
import com.hotlayout.support.AdaptiveRender
import com.hotlayout.support.AdaptiveRender.obj
import com.hotlayout.support.ScreenStage
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The geometry rows of the adaptive table through the REAL screen: the document is drawn at a width, the class comes from the
 * box, and the boxes stand where the table says. One pixel to a logical pixel; a box that stands at a fraction of a pixel is
 * found within two. The wide places are in `AdaptiveWideWiringTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w2000dp-h900dp-mdpi")
class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stage by lazy { ScreenStage(compose) }

    private fun rows(group: String) = AdaptiveCasesTest.rows(group)
    private fun number(value: JsonValue?) = value?.number ?: 0.0

    private fun assertRect(found: IntArray?, x: Double, y: Double? = null, width: Double, height: Double? = null, label: String) {
        assertNotNull("$label: the box is not drawn", found)
        found!!
        assertEquals("$label: x", x, found[0].toDouble(), 2.01)
        assertEquals("$label: width", width, (found[2] - found[0]).toDouble(), 2.01)
        if (y != null) assertEquals("$label: y", y, found[1].toDouble(), 2.01)
        if (height != null) assertEquals("$label: height", height, (found[3] - found[1]).toDouble(), 2.01)
    }

    /** A host of the row with the children of the table; the screen root has 16 of padding on every side. */
    private fun hostRows(group: String, key: String) {
        for (row in rows(group)) {
            val frame = number(row["frame"])
            val expected = row["expect"]?.obj ?: JsonObject()
            assertEquals(row.name(), expected["widthClass"]?.string, WidthClass.of(frame).word)
            val specs = row[key]?.array.orEmpty().mapNotNull { it.obj }
            var host = (row["node"]?.obj ?: JsonObject()).with("id", JsonValue.Str("host"))
                .with("style", JsonValue.Obj(obj("background" to AdaptiveRender.hostBackground)))
                .with("children", JsonValue.Arr(specs.mapIndexed { i, spec -> AdaptiveRender.cell(i, spec) }))
            val document = AdaptiveRender.document(AdaptiveRender.screenRoot(listOf(JsonValue.Obj(host)), style = JsonValue.of(mapOf("padding" to 16))))
            val picture = stage.draw(AdaptiveRender.Spec(document, frame, 900.0, row["rtl"]?.bool == true))
            val boxes = AdaptiveRender.boxes(picture, listOf(AdaptiveRender.hostColor) + specs.indices.map(AdaptiveRender::colour))
            for ((index, rect) in expected["rects"]?.array.orEmpty().withIndex()) {
                val found = boxes[index + 1]
                if (rect.isNull) {
                    assertNull("${row.name()}: cell $index is hidden", found)
                    continue
                }
                found?.let { it[0] -= 16; it[2] -= 16; it[1] -= 16; it[3] -= 16 }
                assertRect(found, number(rect["x"]), number(rect["y"]), number(rect["width"]), number(rect["height"]), "${row.name()}: cell $index")
            }
        }
    }

    @Test fun gridCases() = hostRows("gridCases", "cells")

    @Test fun boxCases() = hostRows("boxCases", "children")

    // Weights of a row

    @Test fun weightCasesThroughTheRealRow() {
        for (row in rows("weightCases")) {
            val inline = number(row["inline"])
            assertEquals(row.name(), AdaptiveCasesTest.widthClass(row["widthClass"]), WidthClass.of(inline + 32))
            val specs = row["children"]?.array.orEmpty().mapNotNull { it.obj }
            val host = JsonValue.Obj(
                obj(
                    "id" to "host", "type" to "Row", "props" to mapOf("gap" to (row["gap"] ?: JsonValue.Num(0.0)), "align" to "start", "justify" to "start", "wrap" to false),
                    "children" to JsonValue.Arr(specs.mapIndexed { i, spec -> AdaptiveRender.cell(i, spec) }),
                ),
            )
            val document = AdaptiveRender.document(AdaptiveRender.screenRoot(listOf(host), style = JsonValue.of(mapOf("padding" to 16))))
            val picture = stage.draw(AdaptiveRender.Spec(document, inline + 32, 80.0, data = AdaptiveCasesTest.met(row["met"])))
            val boxes = AdaptiveRender.boxes(picture, specs.indices.map(AdaptiveRender::colour))
            for ((index, want) in row["expect"]?.array.orEmpty().withIndex()) {
                if (want.isNull) {
                    assertNull("${row.name()}: child $index is hidden", boxes[index])
                    continue
                }
                assertRect(boxes[index], (boxes[index]?.get(0) ?: 0).toDouble(), width = number(want), label = "${row.name()}: child $index")
            }
        }
    }

    // Content column

    @Test fun contentCasesThroughTheRealScreen() {
        for (row in rows("contentCases")) {
            val rtl = row["rtl"]?.bool == true
            val width = number(row["width"])
            val padding = row["padding"] ?: JsonValue.Obj(JsonObject())
            // The bar is drawn with a button at each end of its content, in colours of their own.
            var bar = obj(
                "id" to "bar", "type" to "AppBar", "props" to mapOf("title" to mapOf("kind" to "literal", "value" to "T"), "showBack" to false),
                "children" to listOf(
                    mapOf(
                        "id" to "lead", "type" to "Button", "zone" to "leading",
                        "props" to mapOf("label" to mapOf("kind" to "literal", "value" to "L"), "fullWidth" to false, "action" to mapOf("type" to "none")),
                        "style" to mapOf("background" to AdaptiveRender.background(3)),
                    ),
                    mapOf(
                        "id" to "act", "type" to "IconButton",
                        "props" to mapOf("icon" to "house", "label" to mapOf("kind" to "literal", "value" to "A"), "action" to mapOf("type" to "none")),
                        "style" to mapOf("background" to AdaptiveRender.background(4)),
                    ),
                ),
            )
            row["appBarInset"]?.let { bar = bar.with("style", JsonValue.of(mapOf("padding" to it))) }
            var nav = obj(
                "id" to "nav", "type" to "NavBar", "props" to mapOf("widePlacement" to "bottom"),
                "children" to listOf("a", "b", "c").mapIndexed { index, id ->
                    mapOf(
                        "id" to id, "type" to "NavItem",
                        "props" to mapOf("icon" to "house", "label" to mapOf("kind" to "literal", "value" to id), "action" to mapOf("type" to "navigate.section", "params" to mapOf("screenId" to id))),
                        "style" to mapOf("background" to AdaptiveRender.background(5 + index)),
                    )
                },
            )
            row["navBarInset"]?.let { nav = nav.with("style", JsonValue.of(mapOf("padding" to it))) }
            val children = listOf(
                JsonValue.Obj(bar), AdaptiveRender.cell(1, obj("height" to 60)),
                JsonValue.of(mapOf("id" to "pin", "type" to "Container", "props" to mapOf("direction" to "column"), "style" to mapOf("background" to AdaptiveRender.background(2), "height" to 40), "pin" to "bottom")),
                JsonValue.Obj(nav),
            )
            val props = row["contentMaxWidth"]?.let { obj("contentMaxWidth" to it) } ?: JsonObject()
            val document = AdaptiveRender.document(AdaptiveRender.screenRoot(children, props, JsonValue.of(mapOf("padding" to padding))))
            val picture = stage.draw(AdaptiveRender.Spec(document, width, 700.0, rtl))
            val boxes = AdaptiveRender.boxes(picture, (1..7).map(AdaptiveRender::colour))
            val expected = row["expect"]
            fun check(zone: String, left: Int, right: Int) {
                val want = expected?.get(zone)
                assertEquals("${row.name()}: $zone: x", number(want?.get("x")), left.toDouble(), 2.01)
                assertEquals("${row.name()}: $zone: width", number(want?.get("width")), (right - left).toDouble(), 2.01)
            }
            val body = boxes[0] ?: error("${row.name()}: body")
            check("body", body[0], body[2])
            val pinned = boxes[1] ?: error("${row.name()}: pinned")
            check("pinned", pinned[0], pinned[2])
            // The content of the bar: from the edge of the node at its start to the edge of the one at its end.
            val lead = boxes[2] ?: error("${row.name()}: leading node")
            val act = boxes[3] ?: error("${row.name()}: action")
            check("appBar", if (rtl) act[0] else lead[0], if (rtl) lead[2] else act[2])
            // The shares of the items of the bar: from the start of the first to the end of the last.
            val items = listOf(boxes[4], boxes[5], boxes[6]).filterNotNull()
            assertEquals(row.name(), 3, items.size)
            check("navBar", items.minOf { it[0] }, items.maxOf { it[2] })
        }
    }

    /** The background of the root runs edge to edge, whatever the column of the content. */
    @Test fun backgroundsRunEdgeToEdge() {
        val root = AdaptiveRender.screenRoot(
            listOf(AdaptiveRender.cell(1, obj("height" to 60))), obj("contentMaxWidth" to 600), JsonValue.of(mapOf("background" to AdaptiveRender.hostBackground)),
        )
        val picture = stage.draw(AdaptiveRender.Spec(AdaptiveRender.document(root), 1280.0, 300.0))
        val boxes = AdaptiveRender.boxes(picture, listOf(AdaptiveRender.hostColor, AdaptiveRender.colour(1)))
        assertEquals(1280, boxes[0]!![2] - boxes[0]!![0])
        assertRect(boxes[1], 340.0, width = 600.0, label = "the column")
    }

    // A class is of the box, not of the window

    @Test fun classFollowsTheBox() {
        val wide = AdaptiveRender.cell(1, obj("height" to 20, "showOn" to listOf("expanded")))
        val phone = AdaptiveRender.cell(2, obj("height" to 20, "showOn" to listOf("compact")))
        val document = AdaptiveRender.document(AdaptiveRender.screenRoot(listOf(wide, phone)))
        for ((width, shown) in listOf(400.0 to 2, 1100.0 to 1, 700.0 to 0, 320.0 to 2)) {
            val picture = stage.draw(AdaptiveRender.Spec(document, width, 100.0))
            val boxes = AdaptiveRender.boxes(picture, listOf(AdaptiveRender.colour(1), AdaptiveRender.colour(2)))
            assertEquals("wide cell at $width", shown == 1, boxes[0] != null)
            assertEquals("phone cell at $width", shown == 2, boxes[1] != null)
        }
    }

    /** A hidden child takes no place and no gap, whether the class or the data hid it. */
    @Test fun hiddenChildTakesNoRoomInAColumnAndARow() {
        for (type in listOf("Column", "Row")) {
            val specs = listOf(obj("height" to 10, "width" to 20), obj("height" to 10, "width" to 20, "showOn" to listOf("expanded")), obj("height" to 10, "width" to 20))
            val host = JsonValue.Obj(
                obj("id" to "host", "type" to type, "props" to mapOf("gap" to 10, "align" to "start"), "children" to JsonValue.Arr(specs.mapIndexed { i, spec -> AdaptiveRender.cell(i, spec) })),
            )
            val document = AdaptiveRender.document(AdaptiveRender.screenRoot(listOf(host)))
            val picture = stage.draw(AdaptiveRender.Spec(document, 393.0, 120.0))
            val boxes = AdaptiveRender.boxes(picture, (0..2).map(AdaptiveRender::colour))
            assertNull(type, boxes[1])
            val first = boxes[0]!!
            val third = boxes[2]!!
            // One gap between the two that are drawn, not two.
            assertEquals(type, 10.0, (if (type == "Column") third[1] - first[3] else third[0] - first[2]).toDouble(), 2.01)
        }
    }

    // A document of the table is drawn at every width

    private fun withDefaults(spec: JsonObject, id: String): JsonValue {
        val type = spec["type"]?.string ?: ""
        var props = when (type) {
            "Grid" -> obj("columns" to 2, "gap" to "sm")
            "Container" -> obj("direction" to "column", "gap" to "sm")
            "Column" -> obj("gap" to "sm", "align" to "start")
            "Row" -> obj("gap" to "sm", "align" to "center", "justify" to "start", "wrap" to false)
            "Repeater" -> obj("source" to "tasks", "item" to "task", "gap" to "sm")
            "Divider" -> obj("orientation" to "horizontal")
            "Text" -> obj("content" to mapOf("kind" to "literal", "value" to "x"))
            else -> JsonObject()
        }
        for ((key, value) in spec["props"]?.obj ?: JsonObject()) props = props.with(key, value)
        var result = spec.with("id", JsonValue.Str(id)).with("props", JsonValue.Obj(props))
        spec["children"]?.array?.let { children ->
            result = result.with("children", JsonValue.Arr(children.mapIndexed { i, child -> withDefaults(child.obj ?: JsonObject(), "$id-$i") }))
        }
        return JsonValue.Obj(result)
    }

    /** The reader has no validator: a document the validator would refuse is still read, and drawn at every width. */
    @Test fun validityCasesDrawAtEveryWidth() {
        var drawn = 0
        for (row in rows("validityCases")) {
            val rootFields = row["root"]?.obj ?: JsonObject()
            var props = obj("gap" to "none")
            for ((key, value) in rootFields["props"]?.obj ?: JsonObject()) props = props.with(key, value)
            val children = row["children"]?.array.orEmpty().mapIndexed { i, child -> withDefaults(child.obj ?: JsonObject(), "n$i") }
            var rootObject = AdaptiveRender.screenRoot(children, props).obj ?: JsonObject()
            for ((key, value) in rootFields) if (key != "props") rootObject = rootObject.with(key, value)
            val document = AdaptiveRender.document(JsonValue.Obj(rootObject))
            for (width in listOf(393.0, 768.0, 1280.0)) {
                assertNotNull("${row.name()} at $width", stage.draw(AdaptiveRender.Spec(document, width, 600.0)))
                drawn += 1
            }
        }
        assertEquals(39 * 3, drawn)
    }

    /** A field of the wrong shape does not stop the screen: drawn at every width. */
    @Test fun hostileShapesDrawAtEveryWidth() {
        val hostile = listOf(
            """{"showOn":"expanded"}""", """{"showOn":[1,null,{}]}""", """{"showOn":[]}""", """{"styleOn":5}""", """{"propsOn":"wide"}""", """{"propsOn":{"expanded":7}}""",
            """{"propsOn":{"expanded":{"gap":{"a":1},"columns":"many","direction":3}}}""", """{"propsOn":{"__proto__":{"gap":"xl"},"constructor":{"gap":"xl"}}}""",
            """{"propsOn":{"unset":{"expanded":"columns"}}}""",
        )
        for (extra in hostile) {
            var grid = obj("id" to "g", "type" to "Grid", "props" to mapOf("columns" to 2), "children" to listOf(AdaptiveRender.cell(0, obj("height" to 10))))
            for ((key, value) in JsonValue.parse(extra).obj ?: JsonObject()) grid = grid.with(key, value)
            val document = AdaptiveRender.document(AdaptiveRender.screenRoot(listOf(JsonValue.Obj(grid))))
            for (width in listOf(393.0, 768.0, 1280.0)) assertNotNull("$extra at $width", stage.draw(AdaptiveRender.Spec(document, width, 300.0)))
        }
    }
}
