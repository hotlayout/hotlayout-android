package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In the top zone every node but the first keeps its `pull`, as in the column of the screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TopZonePullTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun top(id: String, height: Int, extra: String = "") =
        """{"id":"$id","type":"Container","pin":"top"$extra,"props":{"direction":"column"},"style":{"height":$height},"children":[${Screens.text("t$id", Screens.literal(id))}]}"""

    @Test fun theSecondNodeOfTheTopZoneIsPulledOverTheFirst() {
        val children = top("A", 100) + "," + top("B", 50, ""","pull":30""") + "," + Screens.text("body", Screens.literal("body"))
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to children)) }
        }
        compose.waitForIdle()
        val a = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot.top
        val b = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.top
        assertEquals("B starts 100 below A, minus its pull of 30", 70f, b - a, 0.5f)
    }
}
