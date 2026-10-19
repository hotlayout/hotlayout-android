package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.SwipeConfirmRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Swipe to confirm» against the shared table (`swipe-confirm/swipe-confirm-cases.json`). */
class SwipeConfirmCasesTest {
    private val table get() = Contract.json("fixtures/swipe-confirm/swipe-confirm-cases.json")

    private fun rows(group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows", count, list.size)
        return list
    }

    private fun track(value: JsonValue?) = SwipeConfirmRules.Track(
        value?.get("height")?.number ?: 0.0, value?.get("width")?.number ?: 0.0, value?.get("border")?.number ?: 0.0, value?.get("inset")?.number ?: 0.0,
    )

    @Test fun geometry() {
        for (row in rows("geometryCases", 4)) {
            val geometry = SwipeConfirmRules.geometry(track(row["track"]))
            val expect = row["expect"]
            assertEquals(row.name(), expect?.get("diameter")?.number, geometry.diameter)
            assertEquals(row.name(), expect?.get("thumbRoom")?.number, geometry.thumbRoom)
            assertEquals(row.name(), expect?.get("travel")?.number, geometry.travel)
            assertEquals(row.name(), expect?.get("label")?.get("start")?.number, geometry.labelStart)
            assertEquals(row.name(), expect?.get("label")?.get("width")?.number, geometry.labelWidth)
        }
    }

    @Test fun naturalWidth() {
        for (row in rows("naturalWidthCases", 2)) {
            val t = track(row["track"])
            assertEquals(row.name(), row["expect"]?.number, SwipeConfirmRules.naturalWidth(row["label"]?.number ?: 0.0, t.height, t.border, t.inset))
        }
    }

    @Test fun progressAndRelease() {
        for (row in rows("progressCases", 7)) {
            assertEquals(row.name(), row["expect"]?.number, SwipeConfirmRules.progress(row["dx"]?.number ?: 0.0, row["travel"]?.number ?: 0.0, row["rtl"]?.bool ?: false))
        }
        for (row in rows("releaseCases", 5)) {
            assertEquals(row.name(), row["expect"]?.string, SwipeConfirmRules.release(row["progress"]?.number ?: 0.0, row["disabled"]?.bool ?: false).word)
        }
    }

    @Test fun thumbAndCapture() {
        for (row in rows("thumbCases", 5)) {
            assertEquals(row.name(), row["expect"]?.number ?: -1.0, SwipeConfirmRules.thumbStart(track(row["track"]), row["progress"]?.number ?: 0.0), 1e-9)
        }
        for (row in rows("captureCases", 4)) {
            assertEquals(row.name(), row["expect"]?.bool, SwipeConfirmRules.captures(row["dx"]?.number ?: 0.0, row["dy"]?.number ?: 0.0))
        }
    }

    @Test fun waysToPress() {
        for (row in rows("pressCases", 8)) {
            val way = SwipeConfirmRules.Way.entries.first { it.word == row["way"]?.string }
            assertEquals(row.name(), row["expect"]?.string, SwipeConfirmRules.press(way, row["disabled"]?.bool ?: false).word)
        }
    }

    @Test fun rulesMirrorTheArtifact() {
        val schema = Contract.json("screen-schema.json")
        val block = schema["x-swipeConfirm"]
        val busy = schema["x-swipeConfirmBusy"]
        assertEquals(block?.get("threshold")?.number, SwipeConfirmRules.THRESHOLD)
        assertEquals(listOf(SwipeConfirmRules.WORD), block?.get("words")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(block?.get("thumb")?.get("inset")?.string, SwipeConfirmRules.INSET_TOKEN)
        assertEquals(busy?.get("completeMs")?.number, SwipeConfirmRules.COMPLETE_MS)
        assertEquals(busy?.get("returnMs")?.number, SwipeConfirmRules.RETURN_MS)
        assertEquals(busy?.get("busy")?.get("turnMs")?.number, SwipeConfirmRules.SPINNER_TURN_MS)
        assertEquals(busy?.get("ends")?.get("noPromiseTimeoutMs")?.number, SwipeConfirmRules.BUSY_MAX_MS)
        for (row in rows("ruleCases", 10)) if (row["rule"]?.string == "threshold") assertEquals(row["expect"]?.number, SwipeConfirmRules.THRESHOLD)
        for (row in rows("busyRuleCases", 10)) {
            val expect = row["expect"]?.number
            when (row["rule"]?.string) {
                "ends.noPromiseTimeoutMs" -> assertEquals(expect, SwipeConfirmRules.BUSY_MAX_MS)
                "completeMs" -> assertEquals(expect, SwipeConfirmRules.COMPLETE_MS)
                "returnMs" -> assertEquals(expect, SwipeConfirmRules.RETURN_MS)
                "busy.turnMs" -> assertEquals(expect, SwipeConfirmRules.SPINNER_TURN_MS)
                else -> assertNull("${row.name()} is a statement: the wiring tests hold it", expect)
            }
        }
    }

    @Test fun afterRelease() {
        for (row in rows("busyReleaseCases", 4)) {
            val runner = SwipeConfirmRules.Runner.entries.first { it.word == row["runner"]?.string }
            assertEquals(row.name(), row["expect"]?.string, SwipeConfirmRules.afterRelease(runner).word)
        }
    }

    @Test fun runnersOfActions() {
        val cases = listOf(
            JsonObject.of("type" to "navigate", "params" to mapOf("screenId" to "next")) to SwipeConfirmRules.Runner.Reader,
            JsonObject.of("type" to "navigate.back") to SwipeConfirmRules.Runner.Reader,
            JsonObject.of("type" to "navigate.section", "params" to mapOf("screenId" to "home")) to SwipeConfirmRules.Runner.Reader,
            JsonObject.of("type" to "setValue", "params" to mapOf("path" to "a.b", "value" to "x")) to SwipeConfirmRules.Runner.Reader,
            JsonObject.of("type" to "redeem") to SwipeConfirmRules.Runner.Host,
            JsonObject.of("type" to "none") to SwipeConfirmRules.Runner.None,
            JsonObject.of("type" to "openUrl") to SwipeConfirmRules.Runner.Invalid,
            JsonObject.of("type" to "openUrl", "params" to mapOf("url" to "https://example.com/pay")) to SwipeConfirmRules.Runner.Host,
        )
        for ((action, runner) in cases) assertEquals(action.toString(), runner, SwipeConfirmRules.runner(action, emptyMap()))
    }

    @Test fun howBusyEnds() {
        for (row in rows("busyEndCases", 7)) {
            val event = SwipeConfirmRules.BusyEvent.entries.first { it.word == row["event"]?.string }
            assertEquals(row.name(), row["expect"]?.string, SwipeConfirmRules.busyOutcome(event, row["answer"]?.string == "promise").word)
        }
    }
}
