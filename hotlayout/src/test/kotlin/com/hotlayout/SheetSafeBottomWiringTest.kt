package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotlayout.support.near
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with the bottom inset of the platform under a modal layer: a sheet takes it inside its
 * own box (the background reaches the bottom edge, the last node stands above the bar), once, with or without a
 * pinned block; a window and a sheet shown as a window in a wide place take nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class SheetSafeBottomWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun button(id: String, extra: String = "") =
        """{"id":"$id","type":"Button","props":{"label":{"kind":"literal","value":"$id"},"fullWidth":true,"variant":"primary","action":{"type":"ping"}}$extra}"""

    private fun page() =
        """{"screenId":"a","name":"a","root":{"id":"a-root","type":"Screen","props":{"gap":"none"},"children":[${button("open", "").replace("""{"type":"ping"}""", """{"type":"navigate","params":{"screenId":"m"}}""")}]}}"""

    private fun document(entry: String, pinned: Boolean): HotlayoutDocument {
        val box = """{"id":"m-box","type":"Container","props":{"direction":"column","gap":"none"},"style":{"height":100},"children":[]}"""
        val last = button("m-last", if (pinned) "" else "").let { if (pinned) it.dropLast(1) + ""","pin":"bottom"}""" else it }
        val blue = """{"background":{"kind":"color","color":{"value":"#0000ff"}}}"""
        val sheet = """{"screenId":"m","name":"m"$entry,"root":{"id":"m-root","type":"Screen","props":{"gap":"none"},"style":$blue,"children":[$box,$last]}}"""
        return loadDocument("""{"schemaVersion":"9.31.0","documentId":"s","name":"S","initialScreenId":"a","screens":[${page()},$sheet]}""")
    }

    private fun show(document: HotlayoutDocument, inset: Dp?, width: Dp = 360.dp) {
        compose.setContent {
            Box(Modifier.size(width, 640.dp)) { HotlayoutScreen(document, safeAreaBottom = inset) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("node:open").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private val sheetEntry = ""","presentation":"modal""""

    @Test fun aSheetTakesTheInsetInsideItsBoxUnderItsLastNode() {
        show(document(sheetEntry, pinned = false), 34.dp)
        val sheet = bounds("screen:m")
        val last = bounds("node:m-last")
        assertEquals("the sheet still ends at the bottom of the area", 640f, sheet.bottom, 1f)
        assertEquals("the last node stands above the bar", 640f - 34f, last.bottom, 1f)
        assertEquals("content plus the inset", 100f + last.height + 34f, sheet.height, 1f)
        assertTrue("the background reaches the bottom edge", near(capture().let { it.getPixel(20, 640 - 5) and 0xFFFFFF }, 0x0000ff, 3))
    }

    @Test fun theInsetIsOneWithAPinnedBlockAndLiesUnderItsContent() {
        show(document(sheetEntry, pinned = true), 34.dp)
        val sheet = bounds("screen:m")
        val last = bounds("node:m-last")
        assertEquals(640f, sheet.bottom, 1f)
        assertEquals("the pinned block stands above the bar", 640f - 34f, last.bottom, 1f)
        assertEquals("the inset once, not under the root and under the zone", 100f + last.height + 34f, sheet.height, 1f)
        assertTrue("the background reaches the bottom edge", near(capture().let { it.getPixel(20, 640 - 5) and 0xFFFFFF }, 0x0000ff, 3))
    }

    @Test fun aHostThatGaveZeroGetsTheSheetAsBefore() {
        show(document(sheetEntry, pinned = false), 0.dp)
        val sheet = bounds("screen:m")
        assertEquals(100f + bounds("node:m-last").height, sheet.height, 1f)
        assertEquals(640f, bounds("node:m-last").bottom, 1f)
    }

    @Test fun aWindowInTheCentreTakesNoInset() {
        show(document("""$sheetEntry,"modalPlacement":"center"""", pinned = false), 34.dp)
        val window = bounds("screen:m")
        assertEquals(100f + bounds("node:m-last").height, window.height, 1f)
    }

    @Config(qualifiers = "w800dp-h640dp-mdpi")
    @Test fun aSheetShownAsAWindowOnAWideAreaTakesNoInset() {
        show(document(sheetEntry, pinned = true), 34.dp, width = 800.dp)
        val window = bounds("screen:m")
        assertEquals(100f + bounds("node:m-last").height, window.height, 1f)
    }
}
