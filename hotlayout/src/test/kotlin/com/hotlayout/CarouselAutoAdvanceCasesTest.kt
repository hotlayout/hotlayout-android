package com.hotlayout

import com.hotlayout.render.widgets.CarouselAutoAdvance
import com.hotlayout.render.widgets.CarouselRules
import com.hotlayout.render.widgets.CarouselStopRules
import com.hotlayout.render.widgets.CarouselStopView
import com.hotlayout.render.widgets.CarouselAutoAdvanceDriver
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A carousel that advances by time (`carousel.autoAdvance`) against the `autoAdvance...` groups of `carousel/carousel-cases.json`:
 * how the field is read, the next stop, one tick of the clock, what a step writes, and the data of the artifact. The rows that run
 * a whole screen are in `CarouselAutoAdvanceWiringTest`; `autoAdvanceDocumentCases` is the table of the builder's validator.
 */
class CarouselAutoAdvanceCasesTest {
    private val table = Contract.json("fixtures/carousel/carousel-cases.json")
    private val schema get() = Contract.json("screen-schema.json")

    @Test fun everyGroupIsRunOrNamed() {
        val run = setOf("autoAdvanceReadCases", "autoAdvanceNextCases", "autoAdvanceTickCases", "autoAdvanceWriteCases", "autoAdvanceRunCases", "autoAdvanceRuleCases")
        val notRun = setOf("autoAdvanceDocumentCases")
        // The nodes of the screen the run rows are drawn on: data of the table, not rows.
        val data = setOf("autoAdvanceRunScreen")
        val groups = Contract.groups(table).filter { it.startsWith("autoAdvance") }.toSet()
        assertEquals("groups that are neither run nor named", emptySet<String>(), groups - run - notRun - data)
        assertEquals("names that are no longer groups", emptySet<String>(), run + notRun + data - groups)
    }

    @Test fun thePausesAreTheListOfTheArtifactInItsOrder() {
        val block = schema["x-carouselAutoAdvance"]
        assertEquals(CarouselAutoAdvance.Pause.entries.map { it.word }, block?.get("pauses")?.get("list")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(CarouselAutoAdvance.MIN_MS, block?.get("field")?.get("intervalMs")?.get("min")?.number)
        assertEquals(CarouselAutoAdvance.MAX_MS, block?.get("field")?.get("intervalMs")?.get("max")?.number)
    }

    @Test fun theBlocksItRefinesDoNotMentionTheField() {
        assertFalse(schema["x-style"]?.get("layout")?.get("carousel").toString().contains("autoAdvance"))
        assertFalse(schema["x-carouselStop"].toString().contains("autoAdvance"))
    }

    @Test fun rules() {
        val rows = Contract.rows(table, "autoAdvanceRuleCases")
        assertEquals(10, rows.size)
        for (row in rows) {
            var value = schema["x-carouselAutoAdvance"]
            for (key in (row["rule"]?.string ?: "").split(".")) value = value?.get(key)
            assertEquals(row.name(), row["expect"], value)
        }
    }

    @Test fun reading() {
        val rows = Contract.rows(table, "autoAdvanceReadCases")
        assertEquals(12, rows.size)
        for (row in rows) {
            val expected = row["expect"]?.takeIf { !it.isNull }
            val props = row["props"] ?: JsonValue.Null
            // The field alone is read by its shape; that a node hosts a carousel at all is the call of the node.
            if (expected != null) {
                val read = CarouselAutoAdvance.of(props["carousel"])
                assertEquals(row.name(), expected["intervalMs"]?.number, read?.intervalMs)
                assertEquals(row.name(), expected["loop"]?.bool, read?.loop)
            }
            val fields = (props.obj ?: JsonObject()).toMutableList()
            if (row["type"]?.string == "Repeater") fields.add("item" to JsonValue.Str("item"))
            if (row["type"]?.string == "Row") fields.add("gap" to JsonValue.Str("none"))
            val node = ScreenSim.node(JsonValue.Obj(JsonObject(listOf("id" to JsonValue.Str("host"), "type" to (row["type"] ?: JsonValue.Null), "props" to JsonValue.Obj(JsonObject(fields))))))
            val read = CarouselAutoAdvance.of(node)
            assertEquals("through the node: ${row.name()}", expected?.get("intervalMs")?.number, read?.intervalMs)
            assertEquals("through the node: ${row.name()}", expected?.get("loop")?.bool, read?.loop)
        }
    }

    @Test fun next() {
        val rows = Contract.rows(table, "autoAdvanceNextCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val next = CarouselAutoAdvance.next(row["active"]?.number?.toInt() ?: 0, row["stops"]?.number?.toInt() ?: 0, row["loop"]?.bool == true)
            assertEquals(row.name(), row["expect"]?.number?.toInt(), next)
        }
    }

    @Test fun tick() {
        val rows = Contract.rows(table, "autoAdvanceTickCases")
        assertEquals(12, rows.size)
        for (row in rows) {
            val from = row["state"]
            val input = row["input"]
            val expected = row["expect"]
            val tick = CarouselAutoAdvance.tick(
                CarouselAutoAdvance.State(from?.get("active")?.number?.toInt() ?: 0, from?.get("elapsed")?.number ?: 0.0),
                input?.get("dtMs")?.number ?: 0.0, input?.get("active")?.number?.toInt() ?: 0, input?.get("stops")?.number?.toInt() ?: 0,
                input?.get("paused")?.bool == true, input?.get("intervalMs")?.number ?: 0.0, input?.get("loop")?.bool == true,
            )
            assertEquals(row.name(), expected?.get("state")?.get("active")?.number?.toInt(), tick.state.active)
            assertEquals(row.name(), expected?.get("state")?.get("elapsed")?.number, tick.state.elapsed)
            assertEquals(row.name(), expected?.get("step")?.number?.toInt(), tick.step)
        }
    }

    @Test fun write() {
        val rows = Contract.rows(table, "autoAdvanceWriteCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val written = CarouselAutoAdvance.write(row["step"]?.number?.toInt() ?: -1, row["current"]?.takeIf { !it.isNull })
            assertEquals(row.name(), row["expect"]?.number?.toInt(), written)
        }
    }

    // The clock.

    private fun driver(config: CarouselAutoAdvance, active: () -> Int = { 0 }, stops: Int = 3, log: MutableList<Int>): CarouselAutoAdvanceDriver =
        CarouselAutoAdvanceDriver().also {
            it.configure(config)
            it.view = { CarouselStopView(active(), stops) }
            it.onStep = { step -> log.add(step) }
        }

    private fun CarouselAutoAdvanceDriver.ticks(count: Int) = repeat(count) { tick() }

    @Test fun theFirstTickOnlyNotesTheStopAndTheCountStartsThere() {
        val log = ArrayList<Int>()
        val clock = driver(CarouselAutoAdvance(2000.0), log = log)
        clock.ticks(1 + 19)
        assertTrue("1.9 s after the first tick", log.isEmpty())
        clock.ticks(1)
        assertEquals("on the interval", listOf(1), log)
    }

    @Test fun aPauseFreezesTheCountAndItResumesWithWhatWasLeft() {
        val log = ArrayList<Int>()
        val clock = driver(CarouselAutoAdvance(2000.0), log = log)
        clock.ticks(1 + 10)
        clock.set(CarouselAutoAdvance.Pause.Held, true)
        clock.ticks(500)
        assertTrue(log.isEmpty())
        clock.set(CarouselAutoAdvance.Pause.Held, false)
        clock.ticks(9)
        assertTrue("one tick short of what was left", log.isEmpty())
        clock.ticks(1)
        assertEquals(listOf(1), log)
    }

    @Test fun eachPauseHoldsTheCountOnItsOwn() {
        for (pause in CarouselAutoAdvance.Pause.entries) {
            val log = ArrayList<Int>()
            val clock = driver(CarouselAutoAdvance(2000.0), log = log)
            clock.ticks(1)
            clock.set(pause, true)
            clock.ticks(100)
            assertTrue(pause.word, log.isEmpty())
        }
    }

    @Test fun aMovingScrollHoldsTheCountUntilItHasBeenQuiet() {
        val log = ArrayList<Int>()
        val clock = driver(CarouselAutoAdvance(2000.0), log = log)
        clock.ticks(1)
        clock.scrollMoved()
        assertTrue(clock.isPaused)
        clock.ticks(1)
        assertTrue("still within the quiet time", clock.isPaused)
        clock.ticks(1)
        assertFalse("quiet for 150 ms and more", clock.isPaused)
    }

    @Test fun aStopThatChangedOtherwiseRestartsTheCount() {
        val log = ArrayList<Int>()
        var active = 0
        val clock = driver(CarouselAutoAdvance(2000.0), active = { active }, log = log)
        clock.ticks(1 + 15)
        active = 2
        clock.ticks(1)
        clock.ticks(19)
        assertTrue("the count started again from the stop a person chose", log.isEmpty())
    }

    @Test fun aTimerWithNothingToDoNeverTicks() {
        val log = ArrayList<Int>()
        val clock = driver(CarouselAutoAdvance(2000.0), stops = 1, log = log)
        clock.ticks(1000)
        assertNull(log.firstOrNull())
    }

    // A whole screen of the table, without views.

    @Test fun theRowsOfTheTableRunOnTheClockOfTheDriver() {
        val rows = Contract.rows(table, "autoAdvanceRunCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val run = ModelRun(row, table)
            run.start()
            for (step in row["steps"]?.array.orEmpty()) run.perform(step)
            assertEquals("${row.name()}: what was written", row["log"]?.array.orEmpty(), run.log)
            assertEquals("${row.name()}: the offset", row["offset"]?.number, run.offset)
            assertEquals("${row.name()}: how it moved", row["behavior"]?.string, run.behavior)
            assertEquals("${row.name()}: the content under the carousel", row["shown"]?.array.orEmpty().mapNotNull { it.string }, run.shownDetails())
        }
    }

    /** A touch that does not move the row is no move of a person: it writes nothing when it is let go. */
    @Test fun aTouchThatDoesNotMoveTheRowWritesNothing() {
        val run = ModelRun(JsonObject.of("name" to "touch", "field" to "off"), table)
        run.start()
        for (word in listOf("hold", "release", "scrollend")) run.perform(JsonValue.Str(word))
        assertEquals(emptyList<JsonValue>(), run.log)
    }

    @Test fun theStepOfTheTimerIsNotMistakenForTheMoveOfAPerson() {
        val run = ModelRun(JsonObject.of("name" to "own step"), table)
        run.start()
        run.perform(JsonValue.Obj(JsonObject.of("wait" to 3300)))
        // The settle of the step wrote nothing of its own: the one write is the step's.
        run.perform(JsonValue.Str("scrollend"))
        assertEquals(listOf<JsonValue>(JsonValue.of(listOf("write", "account.cardIndex", 1))), run.log)
    }
}

/**
 * The screen of `autoAdvanceRunScreen` without views: the driver, the stops of the layout, a scroll that stands where it was put,
 * the data of the session, and what a step and a settled swipe write. Time moves by `wait` in ticks of the reader's clock.
 */
private class ModelRun(private val row: JsonObject, table: JsonValue) {
    private val path = "account.cardIndex"
    private val layout = table["stopRunLayout"]
    private val stops = CarouselRules.stops(
        layout?.get("viewport")?.number ?: 0.0, layout?.get("paddingStart")?.number ?: 0.0, layout?.get("paddingEnd")?.number ?: 0.0,
        layout?.get("gap")?.number ?: 0.0, layout?.get("cards")?.array.orEmpty().mapNotNull { it.number },
    ).stops
    private val reduced = row["reducedMotion"]?.bool == true
    private val withPath = row["field"]?.string != "noPath"
    private val withField = row["field"]?.string != "off"
    private val nodes: List<JsonValue>
    private val sim: ScreenSim
    private val driver = CarouselAutoAdvanceDriver()
    val log = ArrayList<JsonValue>()
    var offset = 0.0
        private set
    var behavior: String? = null
        private set

    init {
        nodes = table["autoAdvanceRunScreen"]?.array.orEmpty().map { node ->
            if (node["id"]?.string != "row") return@map node
            val carousel = node["props"]?.get("carousel")?.obj ?: JsonObject()
            var edited = carousel.filter { withPath || it.first != "stopPath" }.filter { withField || it.first != "autoAdvance" }
            if (row["loop"]?.bool == true) edited = edited.map { (key, value) -> if (key == "autoAdvance") key to JsonValue.Obj(JsonObject((value.obj ?: JsonObject()).toList() + ("loop" to JsonValue.Bool(true)))) else key to value }
            val props = JsonObject((node["props"]?.obj ?: JsonObject()).map { (key, value) -> if (key == "carousel") key to JsonValue.Obj(JsonObject(edited)) else key to value })
            JsonValue.Obj(JsonObject((node.obj ?: JsonObject()).map { (key, value) -> if (key == "props") key to JsonValue.Obj(props) else key to value }))
        }
        sim = ScreenSim(nodes, com.hotlayout.data.DataLayers(session = Contract.scope(row["session"])))
    }

    fun shownDetails(): List<String> = listOf("details-0", "details-1", "details-2").filter { sim.isShown(it) }

    fun start() {
        val host = sim.root.children.first { it.id == "row" }
        // The show: a value the document tells puts the row on that stop at once.
        if (withPath) sim.scope[path]?.let { value ->
            val stop = CarouselStopRules.shown(value, stops.size)
            offset = stops[stop]
            behavior = if (stop > 0) "instant" else null
        }
        driver.configure(CarouselAutoAdvance.of(host))
        driver.set(CarouselAutoAdvance.Pause.NotShown, row["inert"]?.bool == true)
        driver.view = { CarouselStopView(CarouselRules.activeStop(stops, offset), stops.size) }
        driver.onStep = { step ->
            driver.scrollMoved()
            offset = stops[step]
            behavior = if (reduced) "instant" else "smooth"
            if (withPath) CarouselAutoAdvance.write(step, sim.scope[path])?.let { write(it) }
        }
    }

    private fun write(stop: Int) {
        sim.session = sim.session + (path to JsonValue.Num(stop.toDouble()))
        log.add(JsonValue.of(listOf("write", path, stop)))
    }

    /** A move of a person ends where the scroll settled: the stop is written once, and only then. */
    private fun settle() {
        if (!withPath || !moved) return
        moved = false
        CarouselStopRules.write(CarouselRules.activeStop(stops, offset), true, sim.scope[path])?.let { write(it) }
    }

    private var moved = false

    fun perform(step: JsonValue) {
        step.string?.let { word ->
            when (word) {
                "hold" -> driver.set(CarouselAutoAdvance.Pause.Held, true)
                "release" -> driver.set(CarouselAutoAdvance.Pause.Held, false)
                "scrollend" -> settle()
                else -> error("a step this test does not know: $word")
            }
            return
        }
        val wait = step["wait"]?.number
        val swipe = step["swipe"]?.array
        val visibility = step["visibility"]?.string
        when {
            wait != null -> {
                var left = wait
                while (left > 0) {
                    val part = minOf(left, CarouselAutoAdvance.TICK_MS.toDouble())
                    // The clock of the reader ticks every 100 ms of its own.
                    if (part == CarouselAutoAdvance.TICK_MS.toDouble()) driver.tick()
                    left -= part
                }
            }
            swipe != null -> {
                perform(JsonValue.Str("hold"))
                for (to in swipe.mapNotNull { it.number }) {
                    offset = to
                    moved = true
                    driver.scrollMoved()
                }
                perform(JsonValue.Str("release"))
                perform(JsonValue.Str("scrollend"))
            }
            visibility != null -> driver.set(CarouselAutoAdvance.Pause.NotShown, visibility == "hidden")
            else -> error("a step this test does not know: $step")
        }
    }
}
