package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A diagonal gradient is the CSS `to <corner>` (`x-gradientGeometry`): on a wide box the two corners of the other diagonal
 * lie on the same isoline, so the top end and the bottom start corners show the same colour, the middle one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class GradientGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = HotlayoutThemeSet.read(String(Contract.bytes("fixtures/themes/rewards-showcase-theme.json")))!!

    @Test fun theOtherDiagonalIsOneIsolineOnAWideBox() {
        // `missionSunset` is 135 degrees: to bottom right, from #c2410c to #9d174d.
        val node = """{"id":"c","type":"Container","props":{"direction":"column"},"style":{"width":300,"height":50,"background":{"kind":"gradient","gradient":"missionSunset"}},"children":[]}"""
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to node), theme = theme) } }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        fun red(x: Int, y: Int) = (image.getPixel(x, y) shr 16) and 0xFF
        val topEnd = red(296, 3)
        val bottomStart = red(3, 46)
        assertEquals("the corners of the other diagonal share one colour: $topEnd / $bottomStart", topEnd.toDouble(), bottomStart.toDouble(), 6.0)
        assertTrue("the start corner is the first colour: ${red(3, 3)}", red(3, 3) > topEnd + 8)
        assertTrue("the target corner is the last colour: ${red(296, 46)}", red(296, 46) < topEnd - 8)
    }
}
