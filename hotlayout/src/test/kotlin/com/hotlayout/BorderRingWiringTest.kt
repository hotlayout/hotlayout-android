package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/**
 * The border of a rounded node is a ring between the outer edge and the edge inset by its width, drawn over the content: the
 * content is clipped by the INNER rounded edge, so a picture or a block that fills the box never covers the ring on the arcs
 * of the corners (a card whose art is a rectangle lost its outline there).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class BorderRingWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(document: HotlayoutDocument): android.graphics.Bitmap {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document) } }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        return image
    }

    private fun block(side: String) = """{"id":"art","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":$side,"background":{"kind":"color","color":{"value":"#000000"}}},"children":[]}"""

    /** A 100 × 80 card of radius 20 with a border of 4, a faint white border: the red of the box shows through the ring; a black block fills its padding box. */
    private fun card(border: String = """{"width":4,"color":{"value":"#ffffff","opacity":0.08}}""") =
        """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","align":"start"},"style":{"width":100,"height":80,"radius":20,"border":$border,"background":{"kind":"color","color":{"value":"#ff0000"}}},"children":[{"id":"art","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":92,"height":72,"background":{"kind":"color","color":{"value":"#000000"}}},"children":[]}]}"""

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    @Test fun aBlockFillingTheBoxDoesNotCoverTheBorderOnTheArcsOfTheCorners() {
        val image = capture(Screens.document("a" to card()))
        // The middle of the ring on the diagonal of each corner (the faint border lets the box show, so a block over the ring would be seen): the circle of the corner has its centre 20 in, the ring lies 16–20 from it.
        val onArc = 20 - (18 * Math.sqrt(0.5)).toInt()
        for ((x, y) in listOf(onArc to onArc, 99 - onArc to onArc, onArc to 79 - onArc, 99 - onArc to 79 - onArc)) {
            assertTrue("the ring at ($x, $y) shows the box, not the block: ${image.rgbAt(x, y).toString(16)}", (image.rgbAt(x, y) shr 16) > 0xb0)
        }
        assertTrue("the ring on a straight edge", near(image.rgbAt(2, 40), 0xff1414, 30))
        assertTrue("the block inside", near(image.rgbAt(50, 40), 0x000000))
    }

    @Test fun theBlockIsRoundedByTheInnerEdgeOfTheBorder() {
        val image = capture(Screens.document("a" to card()))
        // The corner of the padding box (4, 4) lies outside the inner arc (radius 16 about (20, 20)): the block does not reach it.
        assertTrue("the corner of the block is cut: ${image.rgbAt(5, 5).toString(16)}", !near(image.rgbAt(5, 5), 0x000000, 40))
        assertTrue("the block is whole where the arc has passed: ${image.rgbAt(24, 24).toString(16)}", near(image.rgbAt(24, 24), 0x000000))
    }

    @Test fun aTranslucentBorderLiesOverTheBackgroundOfTheRingNotOverNothing() {
        val image = capture(Screens.document("a" to card("""{"width":4,"color":{"value":"#ffffff","opacity":0.5}}""")))
        val pixel = image.rgbAt(2, 40)
        assertTrue("half white over the red background: ${pixel.toString(16)}", near(pixel, 0xff8080, 14))
    }
}
