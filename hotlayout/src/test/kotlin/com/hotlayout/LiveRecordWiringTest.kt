package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.data.dataScopeOf
import com.hotlayout.model.loadDocument
import com.hotlayout.support.Contract
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live record of a page (`x-liveRecord`) through the real screen: a step done on the page updates in place when the host gives new data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-mdpi")
class LiveRecordWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun task(id: String, title: String, progress: Int, status: String) =
        mapOf("id" to id, "title" to title, "progress" to progress, "target" to 5, "status" to status)

    @Test fun aPageOpenedFromARowFollowsTheDataWithoutAShowAndWithoutLeavingTheScreen() {
        val document = loadDocument(Contract.bytes("fixtures/live-record-screens.json").decodeToString())
        var data by mutableStateOf(dataScopeOf("tasks" to listOf(task("t1", "Walk", 2, "active"), task("t2", "Read", 1, "active")), "doneTasks" to emptyList<Any>()))
        val shown = ArrayList<String>()
        compose.setContent { Box(Modifier.size(393.dp, 852.dp)) { HotlayoutScreen(document, data = data, locale = "en", onScreenChange = { shown.add("${it.screenId}:${it.cause.word}") }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Read").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1 / 5").assertExists()
        assertEquals(listOf("tasks:entry", "task:forward"), shown)
        // The step is done: the record leaves `tasks` and lands in `doneTasks` with its new numbers.
        data = dataScopeOf("tasks" to listOf(task("t1", "Walk", 2, "active")), "doneTasks" to listOf(task("t2", "Read", 5, "done")))
        compose.waitForIdle()
        compose.onNodeWithText("5 / 5").assertExists()
        assertEquals("the page stays and no show is reported", listOf("tasks:entry", "task:forward"), shown)
        // A record that is nowhere in the data keeps the last one known.
        data = dataScopeOf("tasks" to emptyList<Any>(), "doneTasks" to emptyList<Any>())
        compose.waitForIdle()
        compose.onNodeWithText("5 / 5").assertExists()
        assertEquals(0, compose.onAllNodesWithText("1 / 5").fetchSemanticsNodes().size)
    }
}
