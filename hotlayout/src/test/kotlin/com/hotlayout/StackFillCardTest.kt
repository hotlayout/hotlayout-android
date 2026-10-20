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
 * A card whose own box is a stack: a picture with a named height as the sizing layer and a `fill` layer over it. The card
 * is as wide as the picture would be alone (its height times the proportion), as in a browser; it must not collapse to
 * nothing because the picture asked for no width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class StackFillCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val card = """
        {"id":"outer","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[
          {"id":"card","type":"Container","props":{"direction":"column","gap":"none"},
           "style":{"background":{"kind":"color","color":{"value":"#ff0000"}}},"children":[
            {"id":"layers","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[
              {"id":"art","type":"Image","props":{"source":{"kind":"asset","asset":"placeholder"},"aspectRatio":{"width":300,"height":200},"fit":"cover"},"style":{"height":200}},
              {"id":"content","type":"Column","fill":true,"props":{"gap":"none","align":"start","justify":"between"},"children":[
                {"id":"label","type":"Text","props":{"content":{"kind":"literal","value":"Points"}}}]}]}]}]}
    """.trimIndent()

    @Test fun theCardIsPaintedAcrossTheStack() {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(Screens.document("a" to card, rootStyle = """{"background":{"kind":"color","color":{"value":"#ffffff"}}}"""))
            }
        }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        fun at(x: Int, y: Int) = image.getPixel(x, y) and 0xFFFFFF
        // The card is as wide as the picture would be alone (200 tall at 3:2 is 300 wide) and 200 tall.
        assertTrue("the card background is drawn inside", near(at(290, 100), 0xFF0000, 30))
        assertTrue("the card background is drawn at the bottom", near(at(10, 190), 0xFF0000, 30))
        assertTrue("the card ends where the picture would", near(at(320, 100), 0xFFFFFF))
        assertTrue("the card is no taller than the picture", near(at(10, 210), 0xFFFFFF))
    }
}
