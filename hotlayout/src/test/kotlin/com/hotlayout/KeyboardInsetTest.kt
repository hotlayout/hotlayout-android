package com.hotlayout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotlayout.render.widgets.ProvideKeyboardInset
import com.hotlayout.render.widgets.keyboardInset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The keyboard hook of the screen zones: the reader reads its own `WindowInsets.ime`, and a stated
 * inset replaces it. With no keyboard in the test window the real inset is zero.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theInsetIsZeroWithoutAKeyboardAndTheStatedOneWhenGiven() {
        var read = Dp.Unspecified
        var stated = Dp.Unspecified
        compose.setContent {
            read = keyboardInset()
            ProvideKeyboardInset(300.dp) { stated = keyboardInset() }
        }
        compose.waitForIdle()
        assertEquals(0f, read.value, 0.01f)
        assertEquals(300f, stated.value, 0.01f)
    }
}
