package com.hotlayout

import com.hotlayout.render.widgets.DrawnMarker
import com.hotlayout.render.widgets.ListMarkerRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared table of a list item (`fixtures/list-marker/list-marker-cases.json`): the column, what the marker says, the number among the siblings and the boxes of the item. */
@RunWith(RobolectricTestRunner::class)
class ListMarkerCasesTest {
    private val table get() = Contract.json("fixtures/list-marker/list-marker-cases.json")

    private fun marker(row: JsonObject) = ListMarkerRules.drawn(row["marker"]) ?: DrawnMarker.Bullet
    private fun int(row: JsonObject, key: String) = row[key]?.number?.toInt()

    @Test fun everyGroupIsRun() {
        assertEquals(setOf("geometryCases", "textCases", "numberCases", "instanceCases", "layoutCases", "naturalCases"), Contract.groups(table))
        val counts = mapOf("geometryCases" to 18, "textCases" to 10, "numberCases" to 17, "instanceCases" to 5, "layoutCases" to 16, "naturalCases" to 3)
        for ((group, count) in counts) assertEquals(group, count, Contract.rows(table, group).size)
    }

    @Test fun geometryCases() {
        for (row in Contract.rows(table, "geometryCases")) {
            val geometry = ListMarkerRules.geometry(marker(row), row["fontSize"]?.number ?: 0.0, int(row, "groupSize") ?: 1)
            val expect = row["expect"]
            assertEquals("column: ${row.name()}", expect?.get("column")?.number ?: -1.0, geometry.column, 1e-9)
            assertEquals("gap: ${row.name()}", expect?.get("gap")?.number ?: -1.0, geometry.gap, 1e-9)
            assertEquals("indent: ${row.name()}", expect?.get("indent")?.number ?: -1.0, geometry.indent, 1e-9)
        }
    }

    @Test fun textCases() {
        for (row in Contract.rows(table, "textCases")) {
            assertEquals(row.name(), row["expect"]?.string, ListMarkerRules.text(marker(row), int(row, "number") ?: 1, row["formattingLocale"]?.string))
        }
    }

    @Test fun numberCases() {
        for (row in Contract.rows(table, "numberCases")) {
            val siblings = row["siblings"]?.array.orEmpty().mapNotNull { it.obj }
            val markers = siblings.map { if (it["type"]?.string == "Text") ListMarkerRules.drawn(it["marker"]) else null }
            val visible = siblings.map { it["visible"]?.bool != false }
            val numbers = markers.indices.map { ListMarkerRules.number(markers, it, visible) }
            val sizes = markers.indices.map { ListMarkerRules.groupSize(markers, it) }
            assertEquals("numbers: ${row.name()}", row["expect"]?.get("numbers")?.array.orEmpty().map { it.number?.toInt() }, numbers)
            assertEquals("group sizes: ${row.name()}", row["expect"]?.get("groupSizes")?.array.orEmpty().map { it.number?.toInt() }, sizes)
        }
    }

    @Test fun instanceCases() {
        for (row in Contract.rows(table, "instanceCases")) {
            val visible = row["templateVisible"]?.array.orEmpty().map { it.bool ?: false }
            assertEquals(row.name(), int(row, "records"), visible.size)
            assertEquals("numbers: ${row.name()}", row["expect"]?.get("numbers")?.array.orEmpty().map { it.number?.toInt() }, com.hotlayout.render.widgets.RepeaterPlan.numbers(visible))
            assertEquals("group size: ${row.name()}", row["expect"]?.get("groupSize")?.number?.toInt(), visible.size)
        }
    }

    @Test fun layoutCases() {
        for (row in Contract.rows(table, "layoutCases")) {
            val layout = ListMarkerRules.layout(
                marker(row), row["fontSize"]?.number ?: 0.0, int(row, "groupSize") ?: 1, row["placeInline"]?.number ?: 0.0, row["direction"]?.string == "rtl",
                int(row, "lines"), int(row, "maxLines"), row["lineBlock"]?.number,
            )
            val expect = row["expect"]
            val name = row.name()
            assertEquals("marker from: $name", expect?.get("markerBox")?.get("from")?.number ?: -1.0, layout.markerFrom, 1e-9)
            assertEquals("marker to: $name", expect?.get("markerBox")?.get("to")?.number ?: -1.0, layout.markerTo, 1e-9)
            assertEquals("text from: $name", expect?.get("textBox")?.get("from")?.number ?: -1.0, layout.textFrom, 1e-9)
            assertEquals("text to: $name", expect?.get("textBox")?.get("to")?.number ?: -1.0, layout.textTo, 1e-9)
            assertEquals("text width: $name", expect?.get("textInline")?.number ?: -1.0, layout.textInline, 1e-9)
            assertEquals("marker edge: $name", expect?.get("markerEdge")?.string, if (layout.markerOnRight) "right" else "left")
            assertEquals("hanging indent: $name", expect?.get("secondLineFrom")?.number ?: -1.0, layout.textFrom, 1e-9)
            expect?.get("block")?.number?.let { assertEquals("block: $name", it, layout.block ?: -1.0, 1e-9) }
            expect?.get("markerDrawn")?.bool?.let { assertEquals("marker drawn: $name", it, layout.markerDrawn) }
        }
    }

    @Test fun naturalCases() {
        for (row in Contract.rows(table, "naturalCases")) {
            val natural = ListMarkerRules.naturalInline(marker(row), row["fontSize"]?.number ?: 0.0, int(row, "groupSize") ?: 1, row["textNaturalInline"]?.number ?: 0.0)
            assertEquals(row.name(), row["expect"]?.get("naturalInline")?.number ?: -1.0, natural, 1e-9)
        }
    }
}
