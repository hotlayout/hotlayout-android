package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Screens
import com.hotlayout.support.near
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What counts as a show for `animateIn`: the node mounting on the shown screen. Coming back is a show; closing a layer over a page is not. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class AppearShowWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val fill = 0x4f46e5

    private fun capture(): android.graphics.Bitmap {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun filledAt(x: Int) = near(capture().getPixel(x, 4) and 0xFFFFFF, fill)

    private fun bar(id: String) =
        """{"id":"$id","type":"ProgressBar","props":{"value":{"kind":"literal","value":100.0},"max":{"kind":"literal","value":100.0},"color":"primary","thickness":8,"labelFormat":"none","animateIn":true}}"""

    private fun button(label: String, action: String) =
        """{"id":"b-$label","type":"Button","props":{"label":{"kind":"literal","value":"$label"},"variant":"primary","action":$action}}"""

    private val clock get() = compose.mainClock

    @Test fun aScreenShownAgainGrowsItsNumbersAndAClosedLayerDoesNotRestartThePageBelow() {
        clock.autoAdvance = false
        val document = Screens.document(
            "a" to (bar("barA") + "," + button("Next", """{"type":"navigate","params":{"screenId":"b"}}""") + "," + button("Layer", """{"type":"navigate","params":{"screenId":"m"}}""")),
            "b" to (bar("barB") + "," + button("Back", """{"type":"navigate.back"}""")),
            "m" to button("Close", """{"type":"navigate.back"}"""),
            modal = setOf("m"),
        )
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = "en") } }
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(1000)
        assertTrue("the first page has grown", filledAt(300))
        // A layer over the page: the page below is not shown again.
        compose.onNodeWithText("Layer").performClick()
        clock.advanceTimeBy(100)
        compose.onNodeWithText("Close").performClick()
        // The layer sinks back before it is gone (its dim still lies over the page meanwhile).
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(400)
        assertTrue("closing the layer is not a show: the bar stays full", filledAt(300))
        // A page opened forward grows from zero, and so does the one shown again by «back».
        compose.onNodeWithText("Next").performClick()
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(100)
        assertTrue("the new page starts empty", !filledAt(300))
        clock.advanceTimeBy(1000)
        assertTrue("and grows", filledAt(300))
        compose.onNodeWithText("Back").performClick()
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(100)
        assertTrue("coming back is a new show: empty again", !filledAt(300))
        clock.advanceTimeBy(1000)
        assertTrue("and grown", filledAt(300))
    }
}
