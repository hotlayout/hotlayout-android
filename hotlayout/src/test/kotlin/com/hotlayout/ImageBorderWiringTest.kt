package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Screens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The outline of a round picture (an avatar: `style.border` on an `Image` with the radius `pill`) is drawn over the picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ImageBorderWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theOutlineOfARoundPictureIsDrawn() {
        val avatar = """{"id":"avatar","type":"Image","props":{"source":{"kind":"literal","value":"placeholder"},"aspectRatio":"1:1","fit":"cover"},"style":{"width":64,"height":64,"radius":"pill","border":{"width":2,"color":{"value":"#ff0000"}}}}"""
        val document = Screens.document("a" to avatar)
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document) } }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        // The top of the circle, in the ring: the outline colour, not the picture and not the page.
        val ring = image.getPixel(32, 1) and 0xFFFFFF
        assertTrue("the ring at the top of the circle: ${Integer.toHexString(ring)}", (ring shr 16) > 0xc0 && (ring shr 8 and 0xFF) < 0x60)
        val inside = image.getPixel(32, 8) and 0xFFFFFF
        assertTrue("the picture inside the ring is not the outline colour: ${Integer.toHexString(inside)}", (inside shr 16) < 0xc0 || (inside shr 8 and 0xFF) > 0x60)
    }
}
