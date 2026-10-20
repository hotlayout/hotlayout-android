package com.hotlayout

import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.HapticRules
import com.hotlayout.actions.LongPressRules
import com.hotlayout.actions.PressRules
import com.hotlayout.actions.PressTarget
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.interaction.PressFeedbackRules
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.name
import com.hotlayout.text.BindableText
import com.hotlayout.text.TemplateLanguage
import com.hotlayout.text.TextEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who takes a press, the long press, the feedback of a pressed node and the haptic request, against
 * the shared tables (`long-press/`, `composition/`, `press-feedback/`, `actions/`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PressCasesTest {
    private val longPress get() = Contract.json("fixtures/long-press/long-press-cases.json")
    private val composition get() = Contract.json("fixtures/composition/composition-cases.json")
    private val feedback get() = Contract.json("fixtures/press-feedback/press-feedback-cases.json")
    private val actions get() = Contract.json("fixtures/actions/action-cases.json")

    private fun rows(table: JsonValue, group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows", count, list.size)
        return list
    }

    private fun node(value: JsonValue?): DocumentNode = ScreenSim.node(value ?: JsonValue.Null)

    private fun text(node: DocumentNode, field: String): String = BindableText.text(
        node.props[field], TemplateLanguage(null, null) { null }, TextEnvironment(scope = emptyMap(), formattingLocale = "en", fallbackLocale = "en", now = 0.0),
    )

    // Long press

    @Test fun gestures() {
        for (row in rows(longPress, "gestureCases", 11)) {
            val outcome = LongPressRules.outcome(
                row["heldMs"]?.number ?: 0.0, row["moved"]?.number ?: 0.0, row["cancelled"]?.bool ?: false,
                row["hasLongPress"]?.bool ?: false, row["hasAction"]?.bool ?: false,
            )
            assertEquals(row.name(), row["expect"]?.string, outcome.word)
        }
    }

    @Test fun keys() {
        for (row in rows(longPress, "keyCases", 6)) {
            assertEquals(row.name(), row["expect"]?.bool, LongPressRules.isLongPressKey(row["key"]?.string ?: "", row["shiftKey"]?.bool ?: false))
        }
        for (row in rows(longPress, "keyRepeatCases", 6)) {
            val performs = LongPressRules.keyPerforms(row["key"]?.string ?: "", row["shiftKey"]?.bool ?: false, row["repeat"]?.bool ?: false)
            assertEquals(row.name(), row["expect"]?.bool, performs)
        }
    }

    @Test fun clickAfterFire() {
        for (row in rows(longPress, "clickAfterFireCases", 8)) {
            val swallowed = LongPressRules.swallowsClick(
                row["fired"]?.bool ?: false, row["released"]?.bool ?: false, row["sinceReleaseMs"]?.number ?: 0.0, row["newPointerDown"]?.bool ?: false,
            )
            assertEquals(row.name(), row["expect"]?.bool, swallowed)
        }
    }

    @Test fun longPressRulesMirrorTheArtifact() {
        for (row in rows(longPress, "ruleCases", 5)) {
            val expect = row["expect"]
            when (row["rule"]?.string) {
                "thresholdMs" -> assertEquals(expect?.number, LongPressRules.THRESHOLD_MS)
                "touchSlop" -> assertEquals(expect?.number, LongPressRules.TOUCH_SLOP)
                "order" -> assertEquals(expect?.array.orEmpty().mapNotNull { it.string }, LongPressRules.order)
                "props" -> {
                    assertEquals("longPress", expect?.get("action")?.string)
                    assertEquals("longPressLabel", expect?.get("label")?.string)
                }
                "accessibility.keys" -> assertEquals(2, expect?.array?.size)
                else -> error("unknown rule ${row.name()}")
            }
        }
        for (row in rows(longPress, "onceRuleCases", 3)) {
            if (row["rule"]?.string == "clickAfterFire.windowMs") assertEquals(row["expect"]?.number, LongPressRules.CLICK_AFTER_FIRE_WINDOW_MS)
        }
        val block = Contract.json("screen-schema.json")["x-longPress"]
        assertEquals(block?.get("thresholdMs")?.number, LongPressRules.THRESHOLD_MS)
        assertEquals(block?.get("touchSlop")?.number, LongPressRules.TOUCH_SLOP)
        assertEquals(block?.get("order")?.array.orEmpty().mapNotNull { it.string }, LongPressRules.order)
        assertEquals(Contract.json("screen-schema.json")["x-longPressOnce"]?.get("clickAfterFire")?.get("windowMs")?.number, LongPressRules.CLICK_AFTER_FIRE_WINDOW_MS)
    }

    @Test fun longPressNodes() {
        for (row in rows(longPress, "nodeCases", 9)) {
            val expect = row["expect"]
            val json = row["node"]
            if (json != null) {
                val node = node(json)
                assertEquals(row.name(), expect?.get("accepts")?.bool, PressTarget.acceptsLongPress(node.type))
                assertEquals(row.name(), expect?.get("longPress")?.bool, PressTarget.longPress(node) != null)
                assertEquals(row.name(), expect?.get("acts")?.bool, PressTarget.actsOnPress(node))
            } else {
                assertEquals(row.name(), expect?.get("accepts")?.bool, PressTarget.acceptsLongPress(row["type"]?.string ?: ""))
            }
        }
    }

    @Test fun longPressReceivers() {
        for (row in rows(longPress, "receiverCases", 5)) {
            val chain = row["chain"]?.array.orEmpty().map(::node)
            val receiver = PressTarget.receiver(chain)
            val runs = receiver?.takeIf { PressTarget.longPress(it) != null }?.id
            assertEquals(row.name(), row["expect"]?.string, runs)
        }
    }

    // Composition

    private fun chain(tree: DocumentNode, target: String): List<DocumentNode>? {
        if (tree.id == target) return listOf(tree)
        for (child in tree.children) chain(child, target)?.let { return listOf(tree) + it }
        return null
    }

    @Test fun pressable() {
        for (row in rows(composition, "pressableCases", 19)) {
            val node = node(row["node"])
            assertEquals("${row.name()}: pressable", row["pressable"]?.bool, PressTarget.isPressHost(node))
            assertEquals("${row.name()}: ownsPress", row["ownsPress"]?.bool, PressTarget.ownsPress(node))
            assertEquals("${row.name()}: acts", row["acts"]?.bool, PressTarget.actsOnPress(node))
        }
    }

    @Test fun receiverAndRunner() {
        for (row in rows(composition, "pressCases", 21)) {
            val tree = node(row["tree"])
            val path = chain(tree, row["press"]?.string ?: "") ?: error(row.name())
            val receiver = PressTarget.receiver(path)
            assertEquals("${row.name()}: receiver", row["receiver"]?.string, receiver?.id)
            val disabled = row["disabled"]?.array.orEmpty().mapNotNull { it.string }.toSet()
            val runs = receiver?.takeIf { PressTarget.actsOnPress(it) && it.id !in disabled }?.id
            assertEquals("${row.name()}: runs", row["runs"]?.string, runs)
        }
    }

    @Test fun names() {
        for (row in rows(composition, "nameCases", 35)) {
            val tree = node(row["tree"])
            val heard = PressTarget.name(tree, ::text)
            assertEquals("${row.name()}: hasName ($heard)", row["hasName"]?.bool, heard.isNotEmpty())
            row["startsWith"]?.string?.let { assertTrue("${row.name()}: starts with the first text ($heard)", heard.startsWith(it)) }
            for (word in row["heard"]?.array.orEmpty().mapNotNull { it.string } + row["described"]?.array.orEmpty().mapNotNull { it.string }) {
                assertTrue("${row.name()}: heard “$word” in “$heard”", heard.contains(word))
            }
        }
    }

    // Feedback

    @Test fun feedbackKinds() {
        for (row in rows(feedback, "kindCases", 10)) {
            val kind = PressFeedbackRules.kind(node(row["node"]), row["disabled"]?.bool ?: false, row["swipe"]?.bool ?: false)
            assertEquals(row.name(), row["expect"]?.string, kind?.word)
        }
    }

    @Test fun feedbackPoses() {
        for (row in rows(feedback, "poseCases", 3)) {
            val kind = PressFeedbackRules.Kind.entries.first { it.word == row["kind"]?.string }
            val pose = PressFeedbackRules.pose(kind)
            assertEquals(row["expect"]?.get("scale")?.number, pose.scale)
            assertEquals(row["expect"]?.get("opacity")?.number, pose.opacity)
            // «Reduce motion» keeps only the fade.
            assertEquals(PressFeedbackRules.Pose(1.0, 0.7), PressFeedbackRules.pose(kind, reduceMotion = true))
        }
    }

    @Test fun feedbackRulesMirrorTheArtifact() {
        for (row in rows(feedback, "ruleCases", 5)) {
            val expect = row["expect"]
            when (row["rule"]?.string) {
                "down.durationMs" -> assertEquals(expect?.number, PressFeedbackRules.DOWN_MS)
                "up.durationMs" -> assertEquals(expect?.number, PressFeedbackRules.UP_MS)
                "up.curve" -> assertEquals(expect?.array.orEmpty().mapNotNull { it.number }, PressFeedbackRules.upCurve)
                "down.curve" -> assertEquals(expect?.array.orEmpty().mapNotNull { it.number }, PressFeedbackRules.downCurve)
                "words" -> assertEquals(expect?.array.orEmpty().mapNotNull { it.string }, listOf(PressFeedbackRules.OFF_WORD))
                else -> error("unknown rule ${row.name()}")
            }
        }
        val block = Contract.json("screen-schema.json")["x-pressFeedback"]
        assertEquals(0.97, block?.get("pose")?.get("card")?.get("scale")?.number)
        assertEquals(0.96, block?.get("pose")?.get("button")?.get("scale")?.number)
        assertEquals(PressFeedbackRules.FADE_OPACITY, block?.get("pose")?.get("fade")?.get("opacity")?.number)
        assertEquals(PressFeedbackRules.DOWN_MS, block?.get("down")?.get("durationMs")?.number)
        assertEquals(PressFeedbackRules.UP_MS, block?.get("up")?.get("durationMs")?.number)
    }

    // Haptic

    @Test fun hapticRequests() {
        for (row in rows(actions, "hapticCases", 7)) {
            val word = PressRules.haptic(row["action"]?.obj ?: JsonObject(), row["disabled"]?.bool == true)
            assertEquals(row.name(), row["expect"]?.string, word)
        }
    }

    @Test fun hapticWordsMirrorTheArtifact() {
        assertEquals(Contract.json("screen-schema.json")["x-haptic"]?.get("words")?.array.orEmpty().mapNotNull { it.string }, HapticRules.words)
        assertNull(ActionCatalog.haptic(JsonObject.of("type" to "none", "haptic" to "buzz")))
    }
}
