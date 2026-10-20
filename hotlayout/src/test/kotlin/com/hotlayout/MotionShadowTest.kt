package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A node that pulses is translucent for a while; the shadow it paints outside its box is not cut at the box. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class MotionShadowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = HotlayoutThemeSet.read(String(Contract.bytes("fixtures/themes/rewards-showcase-theme.json")))!!

    private fun redBelowTheBox(motion: String): Int {
        val node = """{"id":"c","type":"Container","props":{"direction":"column"}$motion,"style":{"width":100,"height":50,"shadow":"medium"},"children":[]}"""
        // A pulse never ends: the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to node), theme = theme) } }
        compose.mainClock.advanceTimeBy(400)
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        return (image.getPixel(50, 62) shr 16) and 0xFF
    }

    @Test fun aPulsingNodeKeepsItsShadowOutsideItsBox() {
        val pulsed = redBelowTheBox(""","motion":{"kind":"pulse","scale":1.1,"opacity":0.6,"duration":2000}""")
        assertTrue("the shadow below the box is painted: $pulsed", pulsed <= 250)
    }
}
