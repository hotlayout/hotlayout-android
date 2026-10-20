package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlayout.render.LineBoxKeeper
import com.hotlayout.render.neverNarrowerThanItsLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two text boxes the reader keeps exact on a device (Arabic fallback fonts grow the line; a right-to-left
 * line a fraction wider than its intrinsic width loses its last character). The fonts of a device are not in
 * the JVM, so these prove the wiring of the boxes, and `LayoutOnDeviceTest` proves the letters.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TextBoxWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aLineTallerThanItsLineBoxIsCutBackToIt() {
        compose.setContent {
            val keeper = remember { LineBoxKeeper(20.0) }
            Box(Modifier.testTag("tall").then(keeper.modifier)) { Box(Modifier.height(60.dp).width(40.dp)) }
        }
        assertEquals(20f, compose.onNodeWithTag("tall").getBoundsInRoot().let { it.bottom - it.top }.value, 0.6f)
    }

    @Test fun aLineThatFitsItsLineBoxIsNotTouched() {
        compose.setContent {
            val keeper = remember { LineBoxKeeper(20.0) }
            Box(Modifier.testTag("fits").then(keeper.modifier)) { Box(Modifier.height(20.dp).width(40.dp)) }
        }
        assertEquals(20f, compose.onNodeWithTag("fits").getBoundsInRoot().let { it.bottom - it.top }.value, 0.6f)
    }

    @Test fun aNeverShrinkingTextKeepsItsBoxAndItsPlace() {
        compose.setContent {
            Box {
                BasicText("+200", Modifier.testTag("wide").neverNarrowerThanItsLine(), style = TextStyle(fontSize = 16.sp), softWrap = false)
                BasicText("+200", Modifier.testTag("plain"), style = TextStyle(fontSize = 16.sp), softWrap = false)
            }
        }
        val wide = compose.onNodeWithTag("wide").getBoundsInRoot().let { it.right - it.left }.value
        val plain = compose.onNodeWithTag("plain").getBoundsInRoot().let { it.right - it.left }.value
        assertEquals("the slack is not part of the box", plain, wide, 0.1f)
    }
}
