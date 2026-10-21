package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.render.paint.GlassProbe
import com.hotlayout.support.Screens
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
 * Glass nodes that stand inside one another, beside one another and inside a moving node, through the real screen. The blur
 * is a render effect of the GPU path, so these tests look at what does not depend on it: which box is on top, that every
 * box is drawn, and that a box is drawn once and where it stands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class GlassNestingWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(document: HotlayoutDocument, clockMs: Double? = null) {
        compose.setContent { CompositionLocalProvider(LocalNodeMotionClockMs provides clockMs) { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document) } } }
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

    private fun box(id: String, w: Int, h: Int, color: String, blur: Int? = null, fields: String = "", children: String = "") =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none","align":"start"},"style":{"width":$w,"height":$h,"radius":0,"background":{"kind":"color","color":{"value":"$color"}}${if (blur != null) ""","backdropBlur":$blur""" else ""}}$fields,"children":[$children]}"""

    private fun stack(vararg layers: String) =
        """{"id":"s","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[${box("art", 360, 200, "#ff0000")},${layers.joinToString(",")}]}"""

    private val atStart = ""","layer":{"alignHorizontal":"start","alignVertical":"start"}"""

    @Test fun aGlassInsideAGlassIsDrawnAfterIt() {
        val inner = box("inner", 50, 30, "#00ff00", blur = 8)
        show(Screens.document("a" to stack(box("outer", 200, 100, "#0000ff", blur = 12, fields = atStart, children = inner))))
        capture()
        val drawn = GlassProbe.drawnBoxes
        assertEquals("both boxes are drawn: $drawn", 2, drawn.size)
        assertEquals("the outer box first", 200f, drawn[0].width, 0.5f)
        assertEquals("the glass inside it over it", 50f, drawn[1].width, 0.5f)
    }

    @Test fun aGlassInsideAGlassIsDrawnAfterItWhateverOrderTheyFirstAppearIn() {
        // A glass beside them stands before them in the tree: the order inside a pair is the tree order, whatever is drawn first.
        val inner = box("inner", 50, 30, "#00ff00", blur = 8)
        val beside = box("beside", 40, 40, "#ffff00", blur = 6, fields = ""","layer":{"alignHorizontal":"end","alignVertical":"start"}""")
        val outer = box("outer", 200, 100, "#0000ff", blur = 12, fields = atStart, children = inner)
        show(Screens.document("a" to stack(beside, outer)))
        capture()
        val widths = GlassProbe.drawnBoxes.map { it.width.toInt() }
        assertEquals("every glass is drawn: $widths", setOf(40, 200, 50), widths.toSet())
        assertTrue("the outer box before the glass inside it: $widths", widths.indexOf(200) < widths.indexOf(50))
    }

    @Test fun aGlassInAMovingNodeIsDrawnOnceAtTheMovedPlace() {
        // A quarter of the period of a float of 20 over 2000 ms: the node stands 20 px to the end of the line.
        val glass = box("glass", 100, 50, "#0000ff", blur = 12, fields = atStart + ""","motion":{"kind":"float","axis":"horizontal","amplitude":20,"duration":2000}""")
        show(Screens.document("a" to stack(glass)), clockMs = 500.0)
        val image = capture()
        assertEquals("the box moved with its node", 20f, GlassProbe.lastBox.left, 1f)
        // Not a pixel of it in the page, at the place it stands or at the place it left (the capture does not replay layers).
        for (x in listOf(10, 60, 110, 130)) assertTrue("nothing of it in the page at $x: ${Integer.toHexString(image.rgbAt(x, 25))}", near(image.rgbAt(x, 25), 0xff0000))
    }
}
