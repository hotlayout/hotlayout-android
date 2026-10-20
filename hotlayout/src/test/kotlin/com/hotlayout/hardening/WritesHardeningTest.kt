package com.hotlayout.hardening

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.JsonValue
import com.hotlayout.WritablePaths
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Limits of writing: `onWrite` gets only paths of the shape of a data path that the host's
 * `writablePaths` allows, the path is never rewritten on the way, and nothing is written without a
 * gesture of the person.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class WritesHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // ---------------------------------------------------------------- the shape of a path

    private val refusedShapes = listOf(
        "", "__proto__", "__proto__.x", "user.__proto__", "constructor", "user.constructor", "prototype.x",
        "user..name", ".user", "user.", " user.name", "user.name ", "user.name\n", "user\u0000.name", "user/name",
        "user[0]", "user.0", "usеr.name" /* Cyrillic е */, "user.na me", "a".repeat(129), "1user", "user.-x",
    )

    /** Paths of the wrong shape are refused before the host's predicate is even asked. */
    @Test fun aPathOfTheWrongShapeIsNeverWritableWhateverTheHostSays() {
        val asked = ArrayList<String>()
        val everything = WritablePaths.where { asked.add(it); true }
        for (path in refusedShapes) {
            assertFalse("«$path» without a host list", WritablePaths.permits(null, path))
            assertFalse("«$path» with a host that allows everything", WritablePaths.permits(everything, path))
        }
        assertEquals(emptyList<String>(), asked)
        for (path in listOf("user.name", "survey.age", "a", "A_b-c.d_e", "a".repeat(128))) assertTrue(path, WritablePaths.permits(null, path))
    }

    /** The host's predicate sees the path exactly as the document wrote it: no trimming, no case folding. */
    @Test fun theHostSeesThePathAsWritten() {
        val asked = ArrayList<String>()
        val gate = WritablePaths.where { asked.add(it); it.startsWith("survey.") }
        assertTrue(WritablePaths.permits(gate, "survey.Age"))
        assertFalse(WritablePaths.permits(gate, "Survey.age"))
        assertEquals(listOf("survey.Age", "Survey.age"), asked)
        val list = WritablePaths(setOf("survey.age"))
        assertFalse(WritablePaths.permits(list, "survey.AGE"))
        assertFalse(WritablePaths.permits(list, "survey.age.x"))
        assertFalse(WritablePaths.permits(list, "survey"))
    }

    // ---------------------------------------------------------------- through the real screen

    private val writes = ArrayList<String>()
    private var hostData by mutableStateOf<Map<String, JsonValue>>(emptyMap())

    private fun show(document: HotlayoutDocument, gate: WritablePaths?) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(document, data = hostData, writablePaths = gate, onWrite = { p, v -> writes.add("$p=${v.string ?: v.bool ?: v.number}") })
            }
        }
        compose.waitForIdle()
    }

    private fun lit(value: String) = Screens.literal(value)

    private fun bind(path: String) = """{"kind":"binding","path":"$path"}"""

    private fun row(id: String, option: String) =
        """{"id":"$id","type":"ListTile","props":{"leading":{"kind":"none"},"title":${lit(option)},"trailing":{"kind":"radio","optionValue":"$option"}}}"""

    /** Every control that writes, each to its own path. */
    private fun controls(prefix: String): HotlayoutDocument = Screens.document(
        "a" to listOf(
            """{"id":"field","type":"TextField","props":{"label":${lit("Name")},"value":${bind("$prefix.name")}}}""",
            """{"id":"switch","type":"Switch","props":{"label":${lit("Notify")},"value":${bind("$prefix.notify")}}}""",
            """{"id":"check","type":"Checkbox","props":{"label":${lit("Agree")},"value":${bind("$prefix.agreed")}}}""",
            """{"id":"group","type":"ChoiceGroup","props":{"value":${bind("$prefix.goal")},"gap":"none"},"children":[${row("gold", "gold")},${row("silver", "silver")}]}""",
            """{"id":"set","type":"Button","props":{"label":${lit("Set")},"action":{"type":"setValue","params":{"path":"$prefix.period","value":"month"}}}}""",
            """{"id":"toggle","type":"Button","props":{"label":${lit("Toggle")},"action":{"type":"toggleValue","params":{"path":"$prefix.hidden"}}}}""",
        ).joinToString(","),
    )

    private fun pressEverything() {
        compose.onNodeWithTag("field:field", useUnmergedTree = true).performTextInput("x")
        for (id in listOf("switch", "check", "silver", "set", "toggle")) compose.onNodeWithTag("node:$id").performClick()
        compose.waitForIdle()
    }

    @Test fun everyControlWritesOnlyWhereTheHostAllows() {
        show(controls("profile"), WritablePaths(setOf("survey.name")))
        pressEverything()
        assertEquals(emptyList<String>(), writes)
    }

    @Test fun everyControlWritesOnceWhereTheHostAllows() {
        show(controls("survey"), WritablePaths.where { it.startsWith("survey.") })
        pressEverything()
        assertEquals(
            listOf("survey.name=x", "survey.notify=true", "survey.agreed=true", "survey.goal=silver", "survey.period=month", "survey.hidden=true"),
            writes,
        )
    }

    @Test fun aWriteToAForbiddenPathIsRefusedEvenWithoutAHostList() {
        val document = Screens.document(
            "a" to refusedShapes.filter { it.isNotEmpty() && '"' !in it && '\\' !in it && it.none { c -> c < ' ' } }.mapIndexed { index, path ->
                """{"id":"b$index","type":"Button","props":{"label":${lit("B$index")},"action":{"type":"setValue","params":{"path":"$path","value":"x"}}}}"""
            }.joinToString(","),
        )
        show(document, null)
        var index = 0
        while (index < 50) {
            runCatching { compose.onNodeWithTag("node:b$index").fetchSemanticsNode() }.getOrNull() ?: break
            compose.onNodeWithTag("node:b$index").performClick()
            index++
        }
        assertTrue(index > 10)
        assertEquals(emptyList<String>(), writes)
    }

    // ---------------------------------------------------------------- no gesture, no write

    @Test fun nothingIsWrittenWithoutAGesture() {
        hostData = mapOf(
            "survey.name" to JsonValue.Str("Maya"), "survey.notify" to JsonValue.Bool(true),
            "survey.agreed" to JsonValue.Bool(false), "survey.goal" to JsonValue.Str("gold"),
        )
        show(controls("survey"), null)
        compose.mainClock.advanceTimeBy(10_000)
        // The host's data change under the controls (A → B → A): the controls follow, nothing is written.
        hostData = hostData + ("survey.name" to JsonValue.Str("Alex")) + ("survey.goal" to JsonValue.Str("silver"))
        compose.waitForIdle()
        hostData = hostData + ("survey.name" to JsonValue.Str("Maya"))
        compose.waitForIdle()
        // Text the platform puts into an untouched field (autofill) is not the person's.
        compose.onNodeWithTag("field:field", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("filled@example.com")) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), writes)
    }
}
