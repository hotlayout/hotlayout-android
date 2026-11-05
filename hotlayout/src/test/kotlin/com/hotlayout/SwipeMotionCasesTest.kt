package com.hotlayout

import com.hotlayout.render.widgets.SwipeConfirmRules
import com.hotlayout.render.widgets.SwipeMotion
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion of «swipe to confirm» against the shared table (`swipe-motion/swipe-motion-cases.json`): the fill behind
 * the thumb, the label, the radius of the thumb, the collapse into a circle, the fill over the screen, the order of the
 * steps and the one word of the field. Curves are compared to 1e-4.
 */
class SwipeMotionCasesTest {
    private val table get() = Contract.json("fixtures/swipe-motion/swipe-motion-cases.json")
    private val tolerance = 1e-4

    private fun rows(group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows", count, list.size)
        return list
    }

    private fun track(value: JsonValue?) = SwipeConfirmRules.Track(
        value?.get("height")?.number ?: 0.0, value?.get("width")?.number ?: 0.0, value?.get("border")?.number ?: 0.0, value?.get("inset")?.number ?: 0.0,
    )

    private fun near(label: String, expected: Double?, actual: Double) = assertEquals(label, expected ?: Double.NaN, actual, tolerance)

    @Test fun labelAndTrail() {
        for (row in rows("labelCases", 6)) near(row.name(), row["expect"]?.number, SwipeMotion.labelOpacity(row["progress"]?.number ?: 0.0))
        for (row in rows("trailCases", 4)) {
            val strip = SwipeMotion.trail(track(row["track"]), row["progress"]?.number ?: 0.0)
            near(row.name(), row["expect"]?.get("start")?.number, strip.start)
            near(row.name(), row["expect"]?.get("width")?.number, strip.width)
        }
    }

    @Test fun thumbRadius() {
        for (row in rows("thumbRadiusCases", 12)) near(row.name(), row["expect"]?.number, SwipeMotion.thumbRadius(track(row["track"]), row["radius"]?.number))
    }

    @Test fun collapse() {
        for (row in rows("collapseCases", 7)) {
            val frame = SwipeMotion.collapse(track(row["track"]), row["elapsed"]?.number ?: 0.0)
            val expect = row["expect"]
            near(row.name(), expect?.get("start")?.number, frame.start)
            near(row.name(), expect?.get("width")?.number, frame.width)
            near(row.name(), expect?.get("height")?.number, frame.height)
            near(row.name(), expect?.get("ring")?.number, frame.ring)
            near(row.name(), expect?.get("surfaceOpacity")?.number, frame.surfaceOpacity)
            near(row.name(), expect?.get("haloOpacity")?.number, frame.haloOpacity)
        }
    }

    @Test fun rippleAndReveal() {
        for (row in rows("rippleScaleCases", 4)) {
            val area = row["area"]
            near(
                row.name(), row["expect"]?.number,
                SwipeMotion.rippleScale(
                    area?.get("centerX")?.number ?: 0.0, area?.get("centerY")?.number ?: 0.0,
                    area?.get("screenWidth")?.number ?: 0.0, area?.get("screenHeight")?.number ?: 0.0, area?.get("core")?.number ?: 0.0,
                ),
            )
        }
        for (row in rows("rippleCases", 5)) near(row.name(), row["expect"]?.number, SwipeMotion.rippleAt(row["scale"]?.number ?: 1.0, row["elapsed"]?.number ?: 0.0))
        for (row in rows("revealCases", 4)) near(row.name(), row["expect"]?.number, SwipeMotion.revealAt(row["elapsed"]?.number ?: 0.0))
    }

    @Test fun theOrderOfTheSteps() {
        for (row in rows("sequenceCases", 10)) {
            val input = row["input"]
            val runner = SwipeConfirmRules.Runner.entries.first { it.word == input?.get("runner")?.string }
            val way = SwipeMotion.Way.entries.first { it.word == input?.get("way")?.string }
            val steps = SwipeMotion.sequence(runner, input?.get("actionType")?.string ?: "", input?.get("transition")?.string == "ripple", way, input?.get("reducedMotion")?.bool ?: false)
            assertEquals(row.name(), row["expect"]?.array.orEmpty().mapNotNull { it.string }, steps.map { it.word })
        }
        for (row in rows("busyReturnCases", 2)) {
            val steps = SwipeMotion.busyReturn(row["reducedMotion"]?.bool ?: false)
            assertEquals(row.name(), row["expect"]?.array.orEmpty().mapNotNull { it.string }, steps.map { it.word })
        }
    }

    @Test fun theFieldHasOneWord() {
        for (row in rows("fieldCases", 4)) {
            val reads = SwipeMotion.readsRipple(row["props"]?.obj ?: JsonObject.of())
            assertEquals(row.name(), row["reads"]?.string, if (reads) "ripple" else null)
        }
    }

    @Test fun rulesMirrorTheArtifact() {
        val schema = Contract.json("screen-schema.json")
        val block = schema["x-swipeConfirmMotion"]
        assertTrue("the artifact has the block", block != null)
        val props = schema["\$defs"]?.get("ButtonProps")?.get("properties")
        assertEquals(listOf(SwipeMotion.RIPPLE_WORD), props?.get(SwipeMotion.FIELD)?.get("enum")?.array.orEmpty().mapNotNull { it.string })
        for (row in rows("ruleCases", 12)) {
            var node: JsonValue? = block
            for (key in (row["rule"]?.string ?: "").split(".")) node = node?.get(key)
            assertEquals(row.name(), row["expect"], node)
            when (row["rule"]?.string) {
                "collapse.durationMs" -> assertEquals(row["expect"]?.number, SwipeMotion.COLLAPSE_MS)
                "collapse.haloOpacity" -> assertEquals(row["expect"]?.number, SwipeMotion.HALO_OPACITY)
                "ripple.durationMs" -> assertEquals(row["expect"]?.number, SwipeMotion.RIPPLE_MS)
                "reveal.durationMs" -> assertEquals(row["expect"]?.number, SwipeMotion.REVEAL_MS)
                "collapse.curve" -> assertEquals(row["expect"]?.array.orEmpty().map { it.number }, SwipeMotion.collapseCurve.toList())
                "ripple.actions" -> assertEquals(row["expect"]?.array.orEmpty().map { it.string }, SwipeMotion.rippleActionTypes)
                "field" -> assertEquals(row["expect"]?.string, SwipeMotion.FIELD)
                "words" -> assertEquals(row["expect"]?.array.orEmpty().map { it.string }, listOf(SwipeMotion.RIPPLE_WORD))
            }
        }
    }
}
