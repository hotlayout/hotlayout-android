package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A write action on the long press, through the real screen: the hold, the context-menu key and the tap of
 * the shared table `long-press/long-press-write-cases.json`, and the host sees what the table says.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LongPressWriteCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/long-press/long-press-write-cases.json")

    private val log = ArrayList<List<JsonValue>>()
    private val handed = ArrayList<String>()
    private val session = HashMap<String, JsonValue>()

    private var current by mutableStateOf<HotlayoutDocument?>(null)
    private var contentSet = false

    /** The screen of one document; the content is set once per test, the documents follow each other in it. */
    private fun show(document: HotlayoutDocument) {
        current = document
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                Box(Modifier.size(360.dp, 640.dp)) {
                    current?.let {
                        HotlayoutScreen(
                            it,
                            onAction = { action, _ -> handed.add(action.type) },
                            onTrack = { event, _ -> log.add(listOf(JsonValue.Str("track"), JsonValue.Str(event.event))) },
                            onWrite = { path, value -> log.add(listOf(JsonValue.Str("write"), JsonValue.Str(path), value)); session[path] = value },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Whether the text of a watched node (the children of the root are texts) is on the screen. */
    private fun isDrawn(nodes: List<JsonValue>, id: String): Boolean {
        val text = nodes.firstOrNull { it["id"]?.string == id }?.get("props")?.get("content")?.get("value")?.string ?: return false
        return compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gesture(how: String, tag: String) {
        val target = compose.onNodeWithTag(tag)
        when (how) {
            "hold" -> {
                // The threshold is a timeout of the gesture: the clock of the test runs through it before the finger lifts.
                target.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(600)
                target.performTouchInput { up() }
            }
            "tap" -> target.performClick()
            "key" -> {
                target.requestFocus()
                target.performKeyInput { keyDown(Key.ShiftLeft); keyDown(Key.F10); keyUp(Key.F10); keyUp(Key.ShiftLeft) }
            }
            else -> error("unknown gesture $how")
        }
        compose.waitForIdle()
    }

    @Test fun theGesturesOfTheTableWriteWhatTheHostSees() {
        val rows = Contract.rows(table, "runCases")
        assertEquals(6, rows.size)
        for ((index, row) in rows.withIndex()) {
            log.clear(); handed.clear(); session.clear()
            val document = loadDocument(
                """{"schemaVersion":"9.30.0","documentId":"w$index","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{},"children":${TableJson.text(row["nodes"] ?: JsonValue.Null)}}}]}""",
            )
            show(document)
            val tag = "node:${row["node"]?.string}"
            for (item in row["gestures"]?.array.orEmpty()) gesture(item.string ?: "", tag)
            assertEquals("log: ${row.name()}", row["log"]?.array.orEmpty().map { it.array.orEmpty() }, log.toList())
            assertEquals("handed: ${row.name()}", row["handed"]?.array.orEmpty().mapNotNull { it.string }, handed.toList())
            assertEquals("session: ${row.name()}", Contract.scope(row["session"]), session.toMap())
            val expectedShown = row["shown"]?.array.orEmpty().mapNotNull { it.string }
            val shown = row["watch"]?.array.orEmpty().mapNotNull { it.string }.filter { id -> isDrawn(row["nodes"]?.array.orEmpty(), id) }
            assertEquals("shown: ${row.name()}", expectedShown, shown)
            val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
            assertEquals("role: ${row.name()}", Role.Button, config.getOrNull(SemanticsProperties.Role))
            assertNull("no state: ${row.name()}", config.getOrNull(SemanticsProperties.Selected))
            assertNull("no toggle state: ${row.name()}", config.getOrNull(SemanticsProperties.ToggleableState))
        }
    }

    @Test fun theNameOfANodeIsForTheEditorAndChangesNothingOnTheScreen() {
        fun drawn(extra: String): List<String> {
            val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","action":{"type":"share"},"label":{"kind":"literal","value":"Open"}}$extra,"children":[{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Hello"}}$extra}]}"""
            val document = loadDocument("""{"schemaVersion":"9.30.0","documentId":"n","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{},"children":[$card]}}]}""")
            show(document)
            val config = compose.onNodeWithTag("node:card").fetchSemanticsNode().config
            return listOf(config.getOrNull(SemanticsProperties.Role), config.getOrNull(SemanticsProperties.ContentDescription)).map { it.toString() }
        }
        val plain = drawn("")
        assertEquals(2, plain.size)
        assertEquals(plain, drawn(""","name":"Mission card""""))
    }
}
