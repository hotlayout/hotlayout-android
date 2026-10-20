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

/** A background of the kind `image` is painted under the content of a node and of the screen root, as the colour is. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class BackgroundImageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val blue = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEElEQVR4nGNgYPiPAw0pCQCpcD/BFMrqcwAAAABJRU5ErkJggg=="
    private val background = """{"kind":"image","source":{"kind":"url","url":"$blue"},"fit":"cover"}"""

    private fun pixel(document: HotlayoutDocument, x: Int, y: Int): Int {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, images = ImagePolicy(resolve = { it })) }
        }
        // The file is decoded off the main thread: wait until the picture shows.
        var value = 0xFFFFFF
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            value = image.getPixel(x, y) and 0xFFFFFF
            if (!near(value, 0xFFFFFF, 8)) break
            Thread.sleep(50)
        }
        return value
    }

    @Test fun aNodeShowsItsBackgroundPictureInsideItsRadius() {
        val node = """{"id":"c","type":"Container","props":{"direction":"column"},"style":{"width":100,"height":100,"radius":"lg","background":$background},"children":[]}"""
        val value = pixel(Screens.document("a" to node), 50, 50)
        assertTrue("the picture is drawn: $value", near(value, 0x0000FF, 30))
    }

    @Test fun theScreenRootShowsItsBackgroundPicture() {
        val value = pixel(Screens.document("a" to Screens.text("t", Screens.literal("x")), rootStyle = """{"background":$background}"""), 300, 500)
        assertTrue("the picture is drawn under the whole screen: $value", near(value, 0x0000FF, 30))
    }
}
