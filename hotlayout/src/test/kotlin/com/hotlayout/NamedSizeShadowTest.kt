package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.near
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A button with a named height paints its shadow below its box. The outer box that holds the named size clips what is
 * larger than itself, but the shadow belongs to the surface inside it and is painted outside the box, as in a browser.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class NamedSizeShadowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = HotlayoutThemeSet.read(String(Contract.bytes("fixtures/themes/rewards-showcase-theme.json")))!!

    private fun pixelBelowTheBox(node: String, y: Int = 53): Int {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to node), theme = theme) }
        }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        // The box is 50 tall, the shadow of `medium` is shifted 8 down: a few points below the box it is still dark.
        return image.getPixel(100, y) and 0xFFFFFF
    }

    @Test fun aButtonWithANamedHeightPaintsItsShadowBelowTheBox() {
        val button = """{"id":"b","type":"Button","props":{"label":{"kind":"literal","value":"Go"},"variant":"primary"},"style":{"height":50,"shadow":"medium"}}"""
        assertFalse("the shadow is not cut at the box", near(pixelBelowTheBox(button), 0xFFFFFF, 4))
    }

    @Test fun aBlurredShadowReachesAsFarAsACssBlurOfThatRadius() {
        // `medium` is shifted 8 down and blurred by 20 (a Gaussian of deviation 10): ten points below its edge, at y = 68, a CSS
        // shadow still darkens the page by about 3 % of its opacity-weighted colour. A blur filter given the deviation as its
        // radius would reach only about 0.4 of that far and leave the page white there.
        val button = """{"id":"b","type":"Button","props":{"label":{"kind":"literal","value":"Go"},"variant":"primary"},"style":{"height":50,"shadow":"medium"}}"""
        val red = (pixelBelowTheBox(button, 68) shr 16) and 0xFF
        assertTrue("the shadow still reaches y = 68: $red", red <= 251)
    }

    @Test fun aPinnedButtonPaintsItsShadowAboveItsBox() {
        val body = Screens.text("body", Screens.literal("body"))
        val button = """{"id":"b","type":"Button","pin":"bottom","props":{"label":{"kind":"literal","value":"Go"},"variant":"primary"},"style":{"height":50,"shadow":"medium"}}"""
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to "$body,$button"), theme = theme) } }
        compose.waitForIdle()
        val top = compose.onNodeWithText("Go").fetchSemanticsNode().boundsInRoot.top.toInt()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        // The shadow of `medium` is shifted 8 down and blurred by 20, so a point above the box is still a little dark.
        val red = (image.getPixel(100, top - 2) shr 16) and 0xFF
        assertTrue("the shadow reaches above the pinned button: $red", red <= 252)
    }
}
