package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.motion.ValueTransition
import com.hotlayout.render.motion.ValueTransitionEngine
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared value-transition table: the curve, the step, the digits, the decisions and the targets of
 * a changing value and of an appearing one, each against the functions the engine runs on; then the
 * engine itself on a clock the test holds.
 */
@RunWith(RobolectricTestRunner::class)
class ValueTransitionCasesTest {
    private val table get() = Contract.json("fixtures/value-transition/value-transition-cases.json")

    private fun rows(group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows in the table", count, list.size)
        return list
    }

    @Test fun duration() = assertEquals(table["durationMs"]?.number, ValueTransition.DURATION_MS)

    @Test fun curve() {
        for (row in rows("curveCases", 7)) assertEquals(row.name(), row["e"]?.number ?: -1.0, ValueTransition.ease(row["p"]?.number ?: 0.0), 1e-9)
    }

    @Test fun steps() {
        for (row in rows("stepCases", 16)) {
            val actual = ValueTransition.value(row["from"]?.number ?: 0.0, row["to"]?.number ?: 0.0, row["t"]?.number ?: 0.0, row["digits"]?.number?.toInt())
            assertEquals(row.name(), row["expected"]?.number ?: -1.0, actual, 1e-9)
        }
    }

    @Test fun digits() {
        for (row in rows("digitsCases", 7)) {
            val digits = ValueTransition.digits(row["from"]?.number ?: 0.0, row["to"]?.number ?: 0.0, row["format"]?.string, row["currency"]?.string)
            assertEquals(row.name(), row["expected"]?.number?.toInt(), digits)
        }
    }

    @Test fun decisions() {
        for (row in rows("decisionCases", 12)) {
            val input = row["change"]
            val change = ValueTransition.Change(
                input?.get("animates")?.bool ?: false, input?.get("shownBefore")?.bool ?: false, input?.get("from"), input?.get("to"),
                input?.get("localeChanged")?.bool ?: false, input?.get("reducedMotion")?.bool ?: false,
            )
            val expected = row["expected"]
            when (val decision = ValueTransition.decision(change)) {
                ValueTransition.Decision.Leads -> assertEquals(row.name(), true, expected?.bool)
                is ValueTransition.Decision.AtOnce -> assertEquals(row.name(), expected?.string, decision.reason.word)
            }
        }
    }

    private fun targets(expected: JsonValue?) = expected?.array.orEmpty().map {
        ValueTransition.Target(it["path"]?.string ?: "", it["scale"]?.bool ?: false, it["format"]?.string, it["currency"]?.string)
    }

    @Test fun targets() {
        for (row in rows("targetCases", 7)) assertEquals(row.name(), targets(row["expected"]), ValueTransition.targets(row["props"]?.obj ?: JsonObject()))
    }

    @Test fun appearSteps() {
        for (row in rows("appearStepCases", 9)) {
            val actual = ValueTransition.value(ValueTransition.APPEAR_FROM, row["to"]?.number ?: 0.0, row["t"]?.number ?: 0.0, row["digits"]?.number?.toInt())
            assertEquals(row.name(), row["expected"]?.number ?: -1.0, actual, 1e-9)
        }
    }

    @Test fun appearDecisions() {
        for (row in rows("appearDecisionCases", 8)) {
            val input = row["input"]
            val decision = ValueTransition.appearDecision(input?.get("animates")?.bool ?: false, input?.get("to"), input?.get("reducedMotion")?.bool ?: false)
            val expected = row["expected"]
            when (decision) {
                ValueTransition.AppearDecision.Grows -> assertEquals(row.name(), true, expected?.bool)
                is ValueTransition.AppearDecision.AtOnce -> assertEquals(row.name(), expected?.string, decision.reason.word)
            }
        }
    }

    @Test fun appearTargets() {
        for (row in rows("appearTargetCases", 4)) assertEquals(row.name(), targets(row["expected"]), ValueTransition.appearTargets(row["props"]?.obj ?: JsonObject()))
    }

    @Test fun appearFlow() {
        for (row in rows("appearFlowCases", 9)) {
            val moment = ValueTransition.Moment.entries.firstOrNull { it.word == row["moment"]?.string }
            assertTrue("moment not read: ${row.name()}", moment != null)
            assertEquals(row.name(), row["expected"]?.string, ValueTransition.appear(moment!!).word)
        }
    }

    // region Engine

    private val points = ValueTransition.Target("user.points", true)
    private fun num(value: Double) = JsonValue.Num(value)

    @Test fun firstShowDoesNotTransition() {
        val engine = ValueTransitionEngine()
        engine.sync(listOf(points), { num(10.0) }, true, "en", false)
        assertFalse(engine.isRunning)
        assertEquals(num(10.0), engine.shown(mapOf("user.points" to num(10.0))).smooth["user.points"])
    }

    @Test fun aChangeLeadsFromTheOldValueAndEndsOnTheNew() {
        val engine = ValueTransitionEngine()
        engine.sync(listOf(points), { num(0.0) }, true, "en", false)
        engine.sync(listOf(points), { num(100.0) }, true, "en", false)
        assertTrue(engine.isRunning)
        engine.tick(1000.0)
        assertEquals("the first frame is the old value", num(0.0), engine.shown(emptyMap()).smooth["user.points"])
        engine.tick(1350.0)
        assertEquals(87.5, engine.shown(emptyMap()).smooth["user.points"]?.number ?: -1.0, 1e-9)
        assertEquals(ValueTransitionEngine.Ends(0.0, 100.0, 87.5), engine.shown(emptyMap()).ends["user.points"])
        engine.tick(1700.0)
        assertFalse(engine.isRunning)
        assertEquals(num(100.0), engine.shown(mapOf("user.points" to num(100.0))).smooth["user.points"])
    }

    @Test fun anInterruptionStartsFromTheShownValueWithTheFullDuration() {
        val engine = ValueTransitionEngine()
        engine.sync(listOf(points), { num(0.0) }, true, "en", false)
        engine.sync(listOf(points), { num(100.0) }, true, "en", false)
        engine.tick(0.0)
        engine.tick(350.0)
        engine.sync(listOf(points), { num(0.0) }, true, "en", false)
        assertEquals(87.5, engine.runs["user.points"]?.from ?: -1.0, 1e-9)
        engine.tick(400.0)
        engine.tick(400.0 + 699)
        assertTrue(engine.isRunning)
        engine.tick(400.0 + 700)
        assertFalse(engine.isRunning)
    }

    @Test fun noTransitionWithoutTheFieldWithReducedMotionOrAChangedLanguage() {
        for ((leads, reduced, locale) in listOf(Triple(false, false, "en"), Triple(true, true, "en"), Triple(true, false, "uk"))) {
            val engine = ValueTransitionEngine()
            engine.sync(listOf(points), { num(0.0) }, true, "en", false)
            engine.sync(listOf(points), { num(100.0) }, leads, locale, reduced)
            assertFalse("leads $leads, reduced $reduced, locale $locale", engine.isRunning)
        }
    }

    @Test fun anAppearanceGrowsFromZeroAndAChangeWithoutTheFieldEndsIt() {
        val engine = ValueTransitionEngine()
        engine.appear(listOf(points), { num(80.0) }, false)
        engine.sync(listOf(points), { num(80.0) }, false, "en", false)
        engine.tick(0.0)
        assertEquals("the first frame is zero", num(0.0), engine.shown(emptyMap()).smooth["user.points"])
        engine.tick(175.0)
        assertEquals(80 * 0.578125, engine.shown(emptyMap()).smooth["user.points"]?.number ?: -1.0, 1e-9)
        engine.sync(listOf(points), { num(60.0) }, false, "en", false)
        assertFalse("without animateChanges the new value is there at once", engine.isRunning)
    }

    @Test fun aLiteralValueAppearsUnderItsOwnKey() {
        val engine = ValueTransitionEngine()
        val literal = ValueTransition.Target(ValueTransition.LITERAL_KEY, true)
        engine.appear(listOf(literal), { if (it == ValueTransition.LITERAL_KEY) num(87.0) else null }, false)
        engine.tick(10.0)
        engine.tick(185.0)
        assertEquals(87 * 0.578125, engine.shown(emptyMap()).literal?.second ?: -1.0, 1e-9)
        assertNull("a literal has no path in the data", engine.shown(emptyMap()).smooth[ValueTransition.LITERAL_KEY])
    }

    @Test fun reducedMotionAndZeroDoNotAppear() {
        for ((value, reduced) in listOf(num(80.0) to true, num(0.0) to false, JsonValue.Str("80") to false)) {
            val engine = ValueTransitionEngine()
            engine.appear(listOf(points), { value }, reduced)
            assertFalse(engine.isRunning)
        }
    }

    // endregion
}
