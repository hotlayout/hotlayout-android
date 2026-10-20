package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The insets a host already consumed (`safeDrawingPadding`, a `Scaffold`) are not added again by the reader. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ConsumedInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val document = Screens.document("a" to """{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Hello"}}}""")

    private fun titleTop(host: Modifier): Float {
        compose.setContent { Box(host.fillMaxSize()) { HotlayoutScreen(document, locale = "en") } }
        compose.runOnUiThread {
            val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        return compose.onNodeWithText("Hello").getBoundsInRoot().top.value
    }

    @Test fun aHostThatPadsByTheStatusBarLeavesTheScreenNothingToAdd() {
        val padded = titleTop(Modifier.safeDrawingPadding())
        // The status bar of 48 px (48 dp at mdpi) is the host's padding already: the title starts right under it, not 48 dp lower.
        assertEquals(48f, padded, 8f)
    }

    @Test fun aHostThatDrawsEdgeToEdgeGetsTheStatusBarFromTheReader() {
        val edge = titleTop(Modifier)
        assertEquals(48f, edge, 8f)
    }

    @Test fun aHostThatConsumedOnlyTheStatusBarByHandIsHonouredToo() {
        val padded = titleTop(Modifier.padding(top = 48.dp).consumeWindowInsets(WindowInsets(top = 48.dp)))
        assertEquals(48f, padded, 8f)
    }
}
