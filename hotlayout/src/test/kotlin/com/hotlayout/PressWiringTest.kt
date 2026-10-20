package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Screens
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
 * What the real screen does with presses on list rows, navigation items and navigation: the long
 * press of a row, the sunken pose of a row and of an item, and the host's veto of a screen change —
 * through `HotlayoutScreen`, not through the tables that prove the rules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class PressWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun lit(value: String) = """{"kind":"literal","value":"$value"}"""

    private fun row(id: String, title: String, props: String = "", node: String = "") =
        """{"id":"$id","type":"ListTile","props":{"title":${lit(title)},"trailing":{"kind":"none"}$props}$node}"""

    private fun show(document: HotlayoutDocument, content: @androidx.compose.runtime.Composable (HotlayoutDocument) -> Unit = { HotlayoutScreen(it) }) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { content(document) } }
        compose.waitForIdle()
    }

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    // A list row

    @Test fun aRowWithOnlyALongPressIsAControlAndTheHoldPerformsIt() {
        val calls = ArrayList<String>()
        val hold = ""","longPress":{"type":"hold","haptic":"heavy","track":{"event":"held"}},"longPressLabel":${lit("More")}"""
        show(Screens.document("a" to row("r", "Row", hold))) {
            HotlayoutScreen(it, onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") }, onHaptic = { w, _ -> calls.add("haptic:$w") })
        }
        assertEquals(Role.Button, compose.onNodeWithTag("node:r").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithText("Row").performTouchInput { down(center); advanceEventTime(600); up() }
        compose.waitForIdle()
        assertEquals(listOf("haptic:heavy", "track:held", "action:hold"), calls)
        calls.clear()
        compose.onNodeWithText("Row").performTouchInput { down(center); advanceEventTime(100); up() }
        compose.waitForIdle()
        assertEquals("a tap does not perform the long press", emptyList<String>(), calls)
    }

    @Test fun aRowWithAnActionActsOnATapAndNotOnAHold() {
        val calls = ArrayList<String>()
        val action = ""","action":{"type":"open","haptic":"light"}"""
        show(Screens.document("a" to row("r", "Row", action))) { HotlayoutScreen(it, onAction = { a, _ -> calls.add("action:${a.type}") }, onHaptic = { w, _ -> calls.add("haptic:$w") }) }
        compose.onNodeWithText("Row").performClick()
        assertEquals(listOf("haptic:light", "action:open"), calls)
    }

    @Test fun theLongPressOfARowInsideACardIsTheRowsAndNotTheCards() {
        val inner = row("r", "Row", ""","longPress":{"type":"row-hold"}""")
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","longPress":{"type":"card-hold"}},"children":[$inner]}"""
        val calls = ArrayList<String>()
        show(Screens.document("a" to card)) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        compose.onNodeWithText("Row").performTouchInput { down(center); advanceEventTime(600); up() }
        compose.waitForIdle()
        assertEquals(listOf("row-hold"), calls)
    }

    private fun sunkRowPixel(node: String): Int {
        val style = ""","style":{"background":{"kind":"color","color":{"value":"#4f46e5"}}}"""
        show(Screens.document("a" to row("r", "Row", ""","action":{"type":"open"}""", style + node)))
        assertTrue("at rest the edge is painted", near(capture().rgbAt(2, 10), 0x4f46e5))
        compose.onNodeWithText("Row").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        return capture().rgbAt(2, 10)
    }

    @Test fun aPressedRowSinksAndNoneTurnsItOff() {
        assertTrue("sunk", near(sunkRowPixel(""), 0xffffff))
    }

    @Test fun noneKeepsARowStill() {
        assertTrue("not sunk", near(sunkRowPixel(""","pressFeedback":"none""""), 0x4f46e5))
    }

    // A navigation item

    private fun navDocument(item: String) =
        Screens.document(
            "home" to """{"id":"h","type":"Text","props":{"content":${lit("Home page")}}},{"id":"nav","type":"NavBar","props":{},"children":[
                {"id":"n-home","type":"NavItem","props":{"icon":"house","label":${lit("Home")},"action":{"type":"navigate.section","params":{"screenId":"home"}}}},
                {"id":"n-more","type":"NavItem","props":{"icon":"house","label":${lit("More")},"action":{"type":"navigate.section","params":{"screenId":"more"}}}$item}]}""",
            "more" to """{"id":"m","type":"Text","props":{"content":${lit("More page")}}}""",
        )

    private fun itemChangesWhileHeld(item: String): Boolean {
        show(navDocument(item))
        val before = capture()
        val bounds = compose.onNodeWithText("More").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("More").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        val during = capture()
        for (x in bounds.left.toInt()..bounds.right.toInt() - 1) for (y in bounds.top.toInt()..bounds.bottom.toInt() - 1) {
            if (before.getPixel(x, y) != during.getPixel(x, y)) return true
        }
        return false
    }

    @Test fun aPressedNavigationItemFadesAndNoneTurnsItOff() {
        assertTrue("fades", itemChangesWhileHeld(""))
    }

    @Test fun noneKeepsANavigationItemStill() {
        assertFalse("still", itemChangesWhileHeld(""","pressFeedback":"none""""))
    }

    // The host's veto of a screen change

    private fun navigationDocument() =
        Screens.document(
            "a" to """{"id":"go","type":"Button","props":{"label":${lit("Go")},"action":{"type":"navigate","params":{"screenId":"b"},"haptic":"light","track":{"event":"go"}}}},
                {"id":"sec","type":"Button","props":{"label":${lit("Section")},"action":{"type":"navigate.section","params":{"screenId":"c"}}}}""",
            "b" to """{"id":"back","type":"Button","props":{"label":${lit("Back")},"action":{"type":"navigate.back"}}}""",
            "c" to """{"id":"t","type":"Text","props":{"content":${lit("Section page")}}}""",
        )

    @Test fun theHostCancelsAScreenChangeAfterTheHapticRequestAndTheEvent() {
        val calls = ArrayList<String>()
        val requests = ArrayList<NavigateRequest>()
        var allow by mutableStateOf(false)
        show(navigationDocument()) {
            HotlayoutScreen(
                it, onTrack = { e, _ -> calls.add("track:${e.event}") }, onHaptic = { w, _ -> calls.add("haptic:$w") },
                onNavigate = { r -> calls.add("navigate"); requests.add(r); allow }, onScreenChange = { e -> calls.add("shown:${e.screenId}:${e.cause.word}") },
            )
        }
        calls.clear()
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf("haptic:light", "track:go", "navigate"), calls)
        assertEquals(NavigateKind.Push, requests.single().kind)
        assertEquals("a", requests.single().fromScreenId)
        assertEquals("b", requests.single().toScreenId)
        assertEquals("go", requests.single().source.nodeId)
        compose.onNodeWithText("Back").assertDoesNotExist()
        calls.clear()
        allow = true
        compose.waitForIdle()
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf("haptic:light", "track:go", "navigate", "shown:b:forward"), calls)
        compose.onNodeWithText("Back").assertExists()
    }

    @Test fun theHostCancelsBackAndSectionToo() {
        val kinds = ArrayList<NavigateKind>()
        var allow by mutableStateOf(true)
        show(navigationDocument()) { HotlayoutScreen(it, onNavigate = { r -> kinds.add(r.kind); allow }) }
        compose.onNodeWithText("Go").performClick()
        allow = false
        compose.waitForIdle()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Back").assertExists()
        allow = true
        compose.waitForIdle()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Section").assertExists()
        allow = false
        compose.waitForIdle()
        compose.onNodeWithText("Section").performClick()
        compose.onNodeWithText("Section page").assertDoesNotExist()
        assertEquals(listOf(NavigateKind.Push, NavigateKind.Back, NavigateKind.Back, NavigateKind.Switch), kinds)
    }
}
