package com.hotlayout

import com.hotlayout.render.widgets.CarouselStopRules
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The carousel that tells its active stop against the `stop...` groups of `carousel/carousel-cases.json`:
 * which path is read, which stop a value shows, where a changed value goes, what a settled swipe writes, and
 * the data of the artifact. `CarouselStopWiringTest` runs the real screen through `stopRunCases`.
 */
class CarouselStopCasesTest {
    private val table = Contract.json("fixtures/carousel/carousel-cases.json")
    private val schema get() = Contract.json("screen-schema.json")

    @Test fun readingThePath() {
        val rows = Contract.rows(table, "stopPathCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val node = ScreenSim.node(JsonValue.Obj(JsonObject(listOf("id" to JsonValue.Str("n"), "type" to (row["type"] ?: JsonValue.Null), "props" to (row["props"] ?: JsonValue.Null)))))
            assertEquals(row.name(), row["expect"]?.string, CarouselStopRules.path(node))
        }
    }

    @Test fun theStopAValueShows() {
        val rows = Contract.rows(table, "stopShownCases")
        assertEquals(13, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.number?.toInt(), CarouselStopRules.shown(row["value"], row["stops"]?.number?.toInt() ?: 1))
    }

    @Test fun whereAChangedValueGoes() {
        val rows = Contract.rows(table, "stopFollowCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            val target = CarouselStopRules.follow(row["previous"], row["next"], row["stops"]?.number?.toInt() ?: 1, row["active"]?.number?.toInt() ?: 0)
            val expected = row["expect"]?.number?.toInt()
            if (expected == null) assertNull(row.name(), target) else assertEquals(row.name(), expected, target)
        }
    }

    @Test fun whatASettledSwipeWrites() {
        val rows = Contract.rows(table, "stopWriteCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val value = CarouselStopRules.write(row["settled"]?.number?.toInt() ?: 0, row["byPerson"]?.bool == true, row["current"])
            val expected = row["expect"]?.number?.toInt()
            if (expected == null) assertNull(row.name(), value) else assertEquals(row.name(), expected, value)
        }
    }

    @Test fun theDataOfTheArtifact() {
        val rows = Contract.rows(table, "stopRuleCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            var value = schema["x-carouselStop"]
            for (key in (row["rule"]?.string ?: "").split(".")) value = value?.get(key)
            assertEquals(row.name(), row["expect"], value)
        }
        assertEquals(CarouselStopRules.KEY, "stopPath")
        assertEquals(CarouselStopRules.PATH_MAX.toDouble(), schema["x-carouselStop"]?.get("path")?.get("maxLength")?.number)
    }
}
