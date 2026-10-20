package com.hotlayout.demo

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The soft keyboard on a device. Gboard (and most Android keyboards) keep the Latin word being typed as a
 * composing region until a space, a suggestion or a blur commits it; the IME talks to the field through
 * `InputConnection.setComposingText`, which is what these tests call.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun softKeyboardWithAHardOne() {
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name.endsWith("AndroidComposeView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var found: View? = null
        rule.runOnUiThread { found = find(rule.activity.window.decorView) }
        return found!!
    }

    private fun inputConnection(): InputConnection {
        var connection: InputConnection? = null
        val view = composeView()
        rule.runOnUiThread { connection = view.onCreateInputConnection(EditorInfo()) }
        return connection!!
    }

    /**
     * `survey-screens` / `details`: the age is filled, the person types «Alex» in the required name field and taps
     * Next while Gboard still holds the word as composing. The field shows «Alex»; the button stays disabled and the
     * tap does nothing — the person has to type a space or close the keyboard first.
     */
    @Test
    fun aButtonPressedMidWordSeesTheWord() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(fixture("survey-screens", "details"), recorder, "survey-screens", data = emptyMap()) }
        rule.onNodeWithTag("field:age", useUnmergedTree = true).performTextInput("30")
        rule.onNodeWithTag("field:name", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val connection = inputConnection()
        rule.runOnUiThread { connection.setComposingText("Alex", 1) }
        rule.waitForIdle()
        rule.onNodeWithText("Alex", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("Next").performClick()
        rule.waitForIdle()
        assertEquals("consent", recorder.shown.last().screenId)
        assertTrue(recorder.writes.any { it.first == "survey.name" && it.second.string == "Alex" })
    }

    private fun imeBottomGap(underPaddedHost: Boolean): Pair<Float, Float> {
        rule.setContent {
            val modifier = if (underPaddedHost) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()
            Box(modifier) { FixtureHost(fixture("survey-screens", "details"), Recorder(), "survey-screens") }
        }
        rule.onNodeWithTag("field:comment", useUnmergedTree = true).performClick()
        val view = composeView()
        var ime = 0
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            rule.runOnUiThread { ime = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
            if (ime > 0) break
            Thread.sleep(200)
        }
        Thread.sleep(800)
        rule.waitForIdle()
        rule.runOnUiThread { ime = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
        assertTrue("the soft keyboard did not show", ime > 0)
        val density = view.resources.displayMetrics.density
        // The first root is the activity's; a cursor-handle popup can be a second one.
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].getBoundsInRoot()
        val windowHeightDp = view.rootView.height / density
        val keyboardTopDp = windowHeightDp - ime / density
        val next = rule.onNodeWithText("Next").getBoundsInRoot()
        // The root is placed at the top of the window: the bounds are window coordinates.
        assertEquals(0f, root.top.value, 1f)
        return next.bottom.value to keyboardTopDp
    }

    /** The pinned block rides on the keyboard: its bottom is near the top of the keyboard, in an edge-to-edge host. */
    @Test
    fun pinnedBlockRidesOnTheKeyboard() {
        val (bottom, keyboardTop) = imeBottomGap(underPaddedHost = false)
        assertTrue("pinned bottom $bottom, keyboard top $keyboardTop", bottom <= keyboardTop + 1 && bottom >= keyboardTop - 48)
    }

    /**
     * A host that already pads by the safe drawing insets (which include the keyboard): the reader lifts the pinned
     * block by the keyboard height a second time, so it floats a keyboard height above the keyboard.
     */
    @Test
    fun pinnedBlockIsNotLiftedTwiceUnderAPaddedHost() {
        val (bottom, keyboardTop) = imeBottomGap(underPaddedHost = true)
        assertTrue("pinned bottom $bottom, keyboard top $keyboardTop", bottom <= keyboardTop + 1 && bottom >= keyboardTop - 48)
    }
}
