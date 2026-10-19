package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.HotlayoutAction
import com.hotlayout.actions.ScreenShownCause
import com.hotlayout.actions.ScreenShownEvent
import com.hotlayout.actions.StandardAction
import com.hotlayout.data.dataScopeOf
import com.hotlayout.json.JsonValue
import com.hotlayout.render.HotlayoutFonts
import com.hotlayout.support.Screens
import com.hotlayout.support.Screens.literal
import com.hotlayout.support.Screens.text
import com.hotlayout.support.near
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
 * What the real screen does with each capability of the foundation, read back from the semantics
 * tree or from pixels. The rule tables prove the rules; these prove that `HotlayoutScreen`,
 * `NodeView` and the widgets call them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class WiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The drawn content as a bitmap (software drawing of the real view; one pixel per dp at mdpi). */
    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    private fun show(document: com.hotlayout.model.HotlayoutDocument, content: @androidx.compose.runtime.Composable (com.hotlayout.model.HotlayoutDocument) -> Unit = { HotlayoutScreen(it) }) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { content(document) } }
        compose.waitForIdle()
    }

    @Test fun aBindingShowsTheDataAndFollowsIt() {
        val document = Screens.document("a" to text("t", """{"kind":"binding","path":"user.name","fallback":"Guest"}"""))
        var data by mutableStateOf(dataScopeOf("user.name" to "Maya"))
        compose.setContent { HotlayoutScreen(document, data = data) }
        compose.onNodeWithText("Maya").assertExists()
        data = dataScopeOf()
        compose.waitForIdle()
        compose.onNodeWithText("Guest").assertExists()
    }

    @Test fun visibleIfHidesTheNode() {
        val document = Screens.document("a" to text("t", literal("Secret"), ""","visibleIf":{"path":"user.vip","op":"isTrue"}"""))
        var data by mutableStateOf(dataScopeOf("user.vip" to false))
        compose.setContent { HotlayoutScreen(document, data = data) }
        compose.onAllNodesWithText("Secret").assertCountEquals(0)
        data = dataScopeOf("user.vip" to true)
        compose.waitForIdle()
        compose.onNodeWithText("Secret").assertExists()
    }

    @Test fun numbersAreWrittenInTheLanguageOfTheScreen() {
        val document = Screens.document("a" to text("t", """{"kind":"binding","path":"user.points","fallback":"0","format":"number"}"""))
        compose.setContent { HotlayoutScreen(document, data = dataScopeOf("user.points" to 1850), locale = "es") }
        // Groups always (`x-template.number.grouping`): CLDR alone would write «1850» in Spanish.
        compose.onNodeWithText("1.850").assertExists()
    }

    @Test fun theTranslationAndTheDirectionOfTheDeviceLocale() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/t/content":"مرحبا"}}}"""
        val badge = """{"id":"b","type":"Badge","props":{"label":{"kind":"literal","value":"Tag"}}}"""
        val document = Screens.document("a" to "${text("t", literal("Hello"))},$badge", extra = localization)
        show(document) { HotlayoutScreen(it, locale = "ar-EG") }
        compose.onNodeWithText("مرحبا").assertExists()
        // A badge of natural width stands at the start of the column: on the right in Arabic.
        val bounds = compose.onNodeWithText("Tag").fetchSemanticsNode().boundsInRoot
        assertTrue("the badge stands at the right: $bounds", bounds.left > 180f)
    }

    @Test fun aButtonHandsAStandardActionToTheHostAfterTheEventAndTheHaptic() {
        val button = """{"id":"go","type":"Button","props":{"label":{"kind":"literal","value":"Open"},
            "action":{"type":"openUrl","params":{"url":"https://hotlayout.com/help"},"track":{"event":"help_open"},"haptic":"light"}}}"""
        val calls = ArrayList<String>()
        var handed: HotlayoutAction? = null
        show(Screens.document("a" to button)) {
            HotlayoutScreen(
                it,
                onAction = { action, source -> handed = action; calls.add("action:${source.nodeId}") },
                onTrack = { event, _ -> calls.add("track:${event.event}") },
                onHaptic = { word, _ -> calls.add("haptic:$word") },
            )
        }
        compose.onNodeWithText("Open").performClick()
        assertEquals(listOf("haptic:light", "track:help_open", "action:go"), calls)
        assertEquals(StandardAction.OpenUrl("https://hotlayout.com/help"), handed?.standard)
    }

    @Test fun anAddressOfTheWrongFormIsHandedAsInvalid() {
        val button = """{"id":"go","type":"Button","props":{"label":{"kind":"literal","value":"Open"},"action":{"type":"openUrl","params":{"url":"http://insecure.example"}}}}"""
        var handed: HotlayoutAction? = null
        show(Screens.document("a" to button)) { HotlayoutScreen(it, onAction = { action, _ -> handed = action }) }
        compose.onNodeWithText("Open").performClick()
        assertEquals(null, handed?.standard)
        assertEquals("url", handed?.invalid?.param)
    }

    @Test fun navigationMovesTheStackAndReportsEveryShow() {
        val forward = """{"id":"next","type":"Button","props":{"label":{"kind":"literal","value":"Next"},"action":{"type":"navigate","params":{"screenId":"b"}}}}"""
        val back = """{"id":"back","type":"Button","props":{"label":{"kind":"literal","value":"Back"},"action":{"type":"navigate.back"}}}"""
        val shown = ArrayList<ScreenShownEvent>()
        val handed = ArrayList<String>()
        show(Screens.document("a" to forward, "b" to back)) { HotlayoutScreen(it, onScreenChange = { event -> shown.add(event) }, onAction = { action, _ -> handed.add(action.type) }) }
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Next").assertExists()
        assertEquals(listOf("a" to ScreenShownCause.Entry, "b" to ScreenShownCause.Forward, "a" to ScreenShownCause.Back), shown.map { it.screenId to it.cause })
        // Back on the first screen is the host's.
        compose.onNodeWithText("Next").assertExists()
        assertTrue(handed.isEmpty())
    }

    @Test fun aContainerWithAnActionIsOneControlNamedByItsLabel() {
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","label":{"kind":"literal","value":"Open the reward"},
            "action":{"type":"custom.reward","params":{"id":"r1"}}},"children":[${text("t", literal("Reward"))}]}"""
        var handed: HotlayoutAction? = null
        show(Screens.document("a" to card)) { HotlayoutScreen(it, onAction = { action, _ -> handed = action }) }
        // The name is the label first, then the texts of the content (`composition.nameCases`).
        compose.onNodeWithContentDescription("Open the reward, Reward").performClick()
        assertEquals(HotlayoutAction("custom.reward", mapOf("id" to "r1")), handed)
    }

    private fun box(color: String, extra: String = "") =
        """{"id":"box","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":100,"height":100,"background":{"kind":"color","color":$color}}$extra,"children":[]}"""

    @Test fun aTokenOfTheThemePaintsTheBoxAndAStateRepaintsIt() {
        val document = Screens.document("a" to box("\"primary\"", ""","styleWhen":[{"when":{"path":"done","op":"isTrue"},"style":{"background":{"kind":"color","color":{"value":"#00ff00"}}}}]"""))
        var data by mutableStateOf(dataScopeOf("done" to false))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        compose.waitForIdle()
        assertTrue(near(capture().rgbAt(50, 50), 0x4f46e5))
        data = dataScopeOf("done" to true)
        compose.waitForIdle()
        assertTrue(near(capture().rgbAt(50, 50), 0x00ff00))
    }

    @Test fun anIconIsDrawnAndAnUnknownOneIsAnEmptyBoxOfTheSameSize() {
        fun icon(id: String, name: String) = """{"id":"$id","type":"Icon","props":{"name":"$name","size":"lg","glyphSize":48},"style":{"color":{"value":"#000000"}}}"""
        show(Screens.document("a" to "${icon("known", "star")},${icon("unknown", "no-such-icon")}"))
        val image = capture()
        val known = compose.onNodeWithTag("icon:star").fetchSemanticsNode().boundsInRoot
        val empty = compose.onNodeWithTag("icon:").fetchSemanticsNode().boundsInRoot
        assertEquals(known.size, empty.size)
        fun dark(bounds: androidx.compose.ui.geometry.Rect): Int {
            var count = 0
            for (x in bounds.left.toInt() until bounds.right.toInt()) for (y in bounds.top.toInt() until bounds.bottom.toInt()) if (image.rgbAt(x, y) < 0x404040) count++
            return count
        }
        assertTrue("the star is drawn", dark(known) > 50)
        assertEquals("the unknown name draws nothing", 0, dark(empty))
    }

    @Test fun theFamilyTheThemeNamesIsAskedOfTheHost() {
        val asked = HashSet<String>()
        show(Screens.document("a" to text("t", literal("Hi")))) { HotlayoutScreen(it, fonts = HotlayoutFonts { name -> asked.add(name); null }) }
        assertTrue("asked: $asked", asked.contains(com.hotlayout.theme.HotlayoutThemeSet.builtIn.theme().fontFamilies["body"]))
    }

    /** The gate of a write: the shape of a data path, then the host's list. */
    @Test fun theGateOfAWrite() {
        assertTrue(com.hotlayout.data.WritablePaths.permits(null, "survey.age"))
        assertFalse(com.hotlayout.data.WritablePaths.permits(null, "__proto__.x"))
        assertFalse(com.hotlayout.data.WritablePaths.permits(com.hotlayout.data.WritablePaths(setOf("survey.age")), "user.name"))
    }
}
