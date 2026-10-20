package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** A child of a column stretches to the column, but a limit it names holds: `width: 100%` with `max-width: 120` is 120. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class StretchedMaxWidthTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun widthOf(extraStyle: String): Float {
        val text = """{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"A long line of text that would take the whole width of the column"},"align":"start"},"style":{$extraStyle}}"""
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to text)) } }
        compose.waitForIdle()
        return compose.onNodeWithText("A long line of text that would take the whole width of the column").fetchSemanticsNode().boundsInRoot.width
    }

    @Test fun aStretchedChildStopsAtItsMaxWidth() = assertEquals(120f, widthOf("\"maxWidth\":120"), 0.5f)

    @Test fun aStretchedChildFillsTheColumnWithoutALimit() = assertEquals(360f, widthOf("\"opacity\":1"), 0.5f)
}
