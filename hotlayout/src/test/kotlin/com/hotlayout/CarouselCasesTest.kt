package com.hotlayout

import com.hotlayout.render.widgets.CarouselMemory
import com.hotlayout.render.widgets.CarouselRules
import com.hotlayout.render.widgets.RepeaterPlan
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The carousel against `carousel/carousel-cases.json` (who is a host, how the field is read, the
 * width of a card, the stops, the active stop, the dots, the area, the state key) and the carousel
 * rows of `repeater-filter/` (stops of the cards a filter lets through).
 */
class CarouselCasesTest {
    private val table = Contract.json("fixtures/carousel/carousel-cases.json")
    private val spacing: Map<String, Double> = table["theme"]?.get("spacing")?.obj?.mapNotNull { (k, v) -> v.number?.let { k to it } }?.toMap().orEmpty()

    private fun points(value: JsonValue?): Double = value?.number ?: spacing[value?.string ?: ""] ?: 0.0

    private fun numbers(value: JsonValue?): List<Double> = value?.array.orEmpty().mapNotNull { it.number }

    @Test fun everyGroupIsRun() {
        val run = setOf("hostCases", "readCases", "cardCases", "stopCases", "activeCases", "dotCases", "areaCases", "stateCases")
        // The groups of the carousel that tells its stop are run by `CarouselStopCasesTest` and `CarouselStopWiringTest`.
        val told = setOf("stopPathCases", "stopShownCases", "stopFollowCases", "stopWriteCases", "stopDocumentCases", "stopRunScreen", "stopRunCases", "stopRuleCases")
        assertEquals(run + told, Contract.groups(table))
    }

    @Test fun defaultsAreTheTables() {
        val defaults = table["defaults"]!!
        assertEquals(CarouselRules.DEFAULT_PEEK, defaults["peek"]?.string)
        assertEquals(CarouselRules.MAX_DOTS.toDouble(), defaults["maxDots"]?.number)
        assertEquals(CarouselRules.STOP_MERGE, defaults["stopMerge"]?.number)
        assertEquals(CarouselRules.MIN_CARD, defaults["minCard"]?.number)
        assertEquals(CarouselRules.INACTIVE_DOT_OPACITY, defaults["inactiveOpacity"]?.number)
        assertEquals(CarouselRules.perViewRange.first.toDouble(), defaults["perViewMin"]?.number)
        assertEquals(CarouselRules.perViewRange.last.toDouble(), defaults["perViewMax"]?.number)
        assertEquals(CarouselRules.DOT_SIZE_TOKEN, defaults["dotSize"]?.string)
        assertEquals(CarouselRules.DOT_GAP_TOKEN, defaults["dotGap"]?.string)
        assertEquals(CarouselRules.INDICATOR_GAP_TOKEN, defaults["indicatorGap"]?.string)
        assertEquals(CarouselRules.DOT_COLOR_TOKEN, defaults["activeColor"]?.string)
    }

    @Test fun hosts() {
        val rows = Contract.rows(table, "hostCases")
        assertEquals(8, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.bool, CarouselRules.isHost(row["type"]?.string ?: "", row["props"]?.obj ?: JsonObject()))
    }

    @Test fun readingTheField() {
        val rows = Contract.rows(table, "readCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val read = CarouselRules.read(row["value"])
            val expect = row["expect"]?.obj
            if (expect == null) {
                assertNull(row.name(), read)
                continue
            }
            assertEquals(row.name(), expect["perView"]?.number?.toInt(), read?.perView)
            assertEquals(row.name(), expect["peek"]?.takeIf { !it.isNull }, read?.peek)
            assertEquals(row.name(), expect["indicator"]?.string, if (read?.dots == false) "none" else "dots")
        }
    }

    @Test fun cardWidth() {
        val rows = Contract.rows(table, "cardCases")
        assertEquals(18, rows.size)
        for (row in rows) {
            val peek = CarouselRules.peekPixels(row["peek"]?.takeIf { !it.isNull }) { spacing[it] }
            val width = CarouselRules.cardWidth(row["viewport"]?.number ?: 0.0, points(row["paddingStart"]), points(row["gap"]), peek, row["perView"]?.number?.toInt() ?: 1)
            val expected = row["expect"]?.number
            if (expected != null) assertEquals(row.name(), expected, width ?: Double.NaN, 1e-9) else assertNull(row.name(), width)
        }
    }

    private fun assertStops(row: JsonObject, viewport: Double) {
        val result = CarouselRules.stops(viewport, points(row["paddingStart"]), points(row["paddingEnd"]), points(row["gap"]), numbers(row["cards"]))
        val expect = row["expect"]
        expect?.get("maxScroll")?.number?.let { assertEquals(row.name(), it, result.maxScroll, 1e-9) }
        val stops = numbers(expect?.get("stops"))
        assertEquals(row.name(), stops.size, result.stops.size)
        for ((actual, wanted) in result.stops.zip(stops)) assertEquals(row.name(), wanted, actual, 1e-9)
        expect?.get("stopOfCard")?.let { assertEquals(row.name(), numbers(it).map(Double::toInt), result.stopOfCard) }
    }

    @Test fun stops() {
        val rows = Contract.rows(table, "stopCases")
        assertEquals(10, rows.size)
        for (row in rows) assertStops(row, row["viewport"]?.number ?: 0.0)
    }

    @Test fun activeStop() {
        val rows = Contract.rows(table, "activeCases")
        assertEquals(10, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.number?.toInt(), CarouselRules.activeStop(numbers(row["stops"]), row["offset"]?.number ?: 0.0))
    }

    @Test fun dots() {
        val rows = Contract.rows(table, "dotCases")
        assertEquals(10, rows.size)
        val size = points(JsonValue.Str("sm"))
        for (row in rows) {
            val count = CarouselRules.dotCount(row["indicator"]?.string != "none", row["stops"]?.number?.toInt() ?: 0, row["viewport"]?.number ?: 0.0, size, size)
            assertEquals(row.name(), row["expect"]?.get("dots")?.number?.toInt(), count)
            assertEquals(row.name(), row["expect"]?.get("block")?.number, CarouselRules.indicatorBlock(count, size, size))
        }
    }

    @Test fun theAreaDoesNotDependOnTheDots() {
        val rows = Contract.rows(table, "areaCases")
        assertEquals(4, rows.size)
        val size = points(JsonValue.Str("sm"))
        for (row in rows) {
            val place = row["place"]?.number ?: 0.0
            assertStops(row, place)
            val stops = CarouselRules.stops(place, points(row["paddingStart"]), points(row["paddingEnd"]), points(row["gap"]), numbers(row["cards"]))
            val dots = CarouselRules.dotCount(row["indicator"]?.string != "none", stops.stops.size, place, size, size)
            assertEquals(row.name(), row["expect"]?.get("dots")?.number?.toInt(), dots)
            assertEquals(row.name(), row["expect"]?.get("viewport")?.number, place)
        }
    }

    @Test fun stateKeys() {
        val rows = Contract.rows(table, "stateCases")
        assertEquals(6, rows.size)
        fun key(side: JsonValue?) = CarouselRules.stateKey(side?.get("node")?.string ?: "", side?.get("instance")?.array.orEmpty().mapNotNull { it.number?.toInt() })
        for (row in rows) assertEquals(row.name(), row["sameState"]?.bool, key(row["a"]) == key(row["b"]))
    }

    @Test fun stopsOfTheCardsDrawn() {
        val filter = Contract.json("fixtures/repeater-filter/repeater-filter-cases.json")
        val records = filter["records"]?.array.orEmpty()
        val rows = Contract.rows(filter, "carouselCases")
        assertEquals(4, rows.size)
        for (row in rows) {
            val drawn = RepeaterPlan.drawnIndexes(records, "reward", row["where"], Contract.scope(row["outer"]), null)
            val cards = List(drawn.size) { row["card"]?.number ?: 0.0 }
            val result = CarouselRules.stops(row["viewport"]?.number ?: 0.0, 0.0, 0.0, row["gap"]?.number ?: 0.0, cards)
            assertEquals(row.name(), row["stops"]?.number?.toInt(), result.stops.size)
        }
    }

    @Test fun memoryFollowsTheFramesOfTheStack() {
        val memory = CarouselMemory()
        val first = CarouselMemory.key(0, "home", "strip")
        val second = CarouselMemory.key(1, "detail", "strip")
        memory.set(2, first)
        memory.set(1, second)
        memory.prune(listOf("home"))
        assertEquals(2, memory.stop(first))
        assertNull("a frame that left the stack takes its state", memory.stop(second))
        memory.prune(listOf("other"))
        assertEquals("frame 0 is another screen now (a section switch)", 0, memory.count)
    }
}
