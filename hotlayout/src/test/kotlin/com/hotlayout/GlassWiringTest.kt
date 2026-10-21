package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
 * Glass (`style.backdropBlur`) through the real screen. The blur itself is a render effect of the
 * GPU path: the software canvas of these tests draws the page under the box unblurred, so the tests
 * prove what does not depend on it — where the glass is drawn, with which radius, that the page and
 * the box are each drawn once, and that nothing is drawn below API 31.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class GlassWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    private fun box(id: String, w: Int, h: Int, color: String, extra: String = "", style: String = "") =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":$w,"height":$h,"background":{"kind":"color","color":{"value":"$color"}}$style}$extra,"children":[]}"""

    private fun glassOver(blur: Int?): HotlayoutDocument {
        val glass = box("glass", 100, 50, "#0000ff", ""","layer":{"alignHorizontal":"start","alignVertical":"start","offsetX":{"kind":"px","value":0}}""", if (blur != null) ""","backdropBlur":$blur,"radius":0""" else "")
        val stack = """{"id":"s","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[${box("art", 360, 200, "#ff0000")},$glass]}"""
        return Screens.document("a" to stack)
    }

    private fun show(document: HotlayoutDocument) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document) } }
        compose.waitForIdle()
    }

    @Test fun aGlassIsDrawnOverTheBoxWithItsRadiusAndThePageAndTheBoxStayWhole() {
        val before = GlassProbe.draws.get()
        show(glassOver(12))
        val image = capture()
        assertTrue("the glass was drawn", GlassProbe.draws.get() > before)
        assertEquals(12f, GlassProbe.lastRadiusPx, 0.01f)
        assertEquals(100f, GlassProbe.lastBox.width, 0.5f)
        assertEquals(50f, GlassProbe.lastBox.height, 0.5f)
        assertTrue("the page is drawn", near(image.rgbAt(300, 100), 0xff0000))
        // The software canvas of these tests does not replay a layer: the box reaches the screen through its layer over the page,
        // so a blue pixel of this capture would be a copy of it painted into the page itself (a ghost, blurred under itself).
        assertTrue("the box is not painted into the page", near(image.rgbAt(50, 25), 0xff0000))
        assertTrue("the page is drawn once", near(image.rgbAt(150, 25), 0xff0000))
        assertEquals("the box stands where the layout put it", 0f, GlassProbe.lastBox.left, 0.5f)
    }

    @Test fun theBlurReadsThePageBeyondTheBoxSoTheEdgesAreNotSmearedByTheEdgePixels() {
        show(glassOver(12))
        capture()
        // A blur of deviation 12 reaches three deviations; the backdrop is recorded that much wider on every side.
        assertEquals((3 * 12), GlassProbe.lastBackdropReachPx)
    }

    @Test fun aPageWithoutAGlassDrawsNone() {
        val before = GlassProbe.draws.get()
        show(glassOver(null))
        capture()
        assertEquals(before, GlassProbe.draws.get())
    }

    @Test fun aGlassOnAButtonLiesOnItsRoleSurface() {
        val before = GlassProbe.draws.get()
        val button = """{"id":"b","type":"Button","props":{"label":{"kind":"literal","value":"Pay"},"variant":"primary","action":{"type":"pay"}},"style":{"backdropBlur":20}}"""
        show(Screens.document("a" to (box("art", 360, 100, "#ff0000") + "," + button)))
        capture()
        assertTrue("the glass of the button was drawn", GlassProbe.draws.get() > before)
        assertEquals(20f, GlassProbe.lastRadiusPx, 0.01f)
    }

    @Test @Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi") fun belowApi31NothingBlursAndTheBoxIsDrawnAsItIs() {
        val before = GlassProbe.draws.get()
        show(glassOver(12))
        val image = capture()
        assertEquals(before, GlassProbe.draws.get())
        assertTrue(near(image.rgbAt(50, 25), 0x0000ff))
        assertTrue(near(image.rgbAt(300, 100), 0xff0000))
    }

    @Test fun aLayerAfterAGlassLayerLiesOverTheGlassAndIsNotBlurredUnderIt() {
        val glass = box("glass", 100, 50, "#0000ff", ""","layer":{"alignHorizontal":"start","alignVertical":"start"}""", ""","backdropBlur":12,"radius":0""")
        val dot = box("dot", 10, 10, "#00ff00", ""","layer":{"alignHorizontal":"start","alignVertical":"start","offset":{"x":{"toward":"end","by":20},"y":{"toward":"bottom","by":20}}}""")
        val stack = """{"id":"s","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[${box("art", 360, 200, "#ff0000")},$glass,$dot]}"""
        val before = GlassProbe.aboveDraws.get()
        show(Screens.document("a" to stack))
        val image = capture()
        assertTrue("the layer after the glass was drawn over it", GlassProbe.aboveDraws.get() > before)
        // Neither the glass nor the layer after it is painted into the page (the capture does not replay layers): both are drawn over it.
        assertTrue("the glass box is not painted into the page", near(image.rgbAt(5, 5), 0xff0000))
        assertTrue("the later layer is not painted into the page the glass blurs: ${Integer.toHexString(image.rgbAt(25, 25))}", near(image.rgbAt(25, 25), 0xff0000))
    }
}
