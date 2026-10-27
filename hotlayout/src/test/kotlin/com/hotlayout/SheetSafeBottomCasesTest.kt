package com.hotlayout

import com.hotlayout.render.screen.SheetSafeBottom
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bottom safe inset of a modal sheet against the shared table: where the reader adds it (the box of the
 * root or the room under the pinned block, never both), the box of the sheet with the inset inside, and the data
 * of the artifact. `SheetSafeBottomWiringTest` shows that the real screen follows these rules.
 */
class SheetSafeBottomCasesTest {
    private val table get() = Contract.json("fixtures/sheet-safe-bottom/sheet-safe-bottom-cases.json")
    private val schema get() = Contract.json("screen-schema.json")

    private fun JsonObject.num(key: String): Double = this[key]?.number ?: 0.0

    @Test fun placementCases() {
        val rows = Contract.rows(table, "placementCases")
        assertEquals(19, rows.size)
        for (row in rows) {
            val input = row["input"]?.obj ?: JsonObject()
            val place = SheetSafeBottom.of(input["presentation"], input["modalPlacement"], input.num("areaWidth"), input["safeBottom"], input["bottomZone"]?.bool == true)
            val expect = row["expect"]?.obj ?: JsonObject()
            assertEquals("${row.name()}: root", expect.num("root"), place.root, 1e-9)
            assertEquals("${row.name()}: zone", expect.num("zone"), place.zone, 1e-9)
        }
    }

    @Test fun geometryCases() {
        val rows = Contract.rows(table, "geometryCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val input = row["input"]?.obj ?: JsonObject()
            val box = SheetSafeBottom.box(input.num("areaHeight"), input.num("contentHeight"), input["safeBottom"])
            val expect = row["expect"]?.obj ?: JsonObject()
            assertEquals("${row.name()}: y", expect.num("y"), box.y, 1e-9)
            assertEquals("${row.name()}: height", expect.num("height"), box.height, 1e-9)
            assertEquals("${row.name()}: contentBottom", expect.num("contentBottom"), box.contentBottom, 1e-9)
            assertEquals("${row.name()}: scrolls", expect["scrolls"]?.bool == true, box.scrolls)
        }
    }

    @Test fun ruleCases() {
        val rows = Contract.rows(table, "ruleCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            var value = schema["x-style"]?.get("layout")?.get("sheetSafeBottom")
            for (key in (row["rule"]?.string ?: "").split(".")) value = value?.get(key)
            assertEquals(row.name(), row["expect"], value)
        }
    }
}
