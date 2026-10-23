package com.hotlayout.demo

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A focused field rises above the on-screen keyboard (`x-style.layout.inputs.focus`): the body area ends at the top
 * of the keyboard (or of the pinned block that rides on it), the focused field with its helper is scrolled into that
 * area, the bar of navigation stays under the keyboard, and a modal layer rises above the keyboard.
 */
@RunWith(AndroidJUnit4::class)
class FocusedFieldOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun softKeyboardWithAHardOne() {
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        // A host of today: the window is edge to edge and the keyboard is only an inset (`adjustResize`: no panning).
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun fields(count: Int, prefix: String = "f") = (0 until count).joinToString(",") { i ->
        """{"id":"$prefix$i","type":"TextField","props":{"label":{"kind":"literal","value":"Field $i"},"value":{"kind":"binding","path":"form.$prefix$i"},"helper":{"kind":"literal","value":"Help $prefix$i"}}}"""
    }

    private val nav = """{"id":"nav","type":"NavBar","props":{},"children":[
        {"id":"n1","type":"NavItem","props":{"icon":"house","label":{"kind":"literal","value":"Home"},"action":{"type":"navigate.section","params":{"screenId":"s"}}}},
        {"id":"n2","type":"NavItem","props":{"icon":"gift","label":{"kind":"literal","value":"Gifts"},"action":{"type":"navigate.section","params":{"screenId":"s"}}}}]}"""

    private val pinned = """{"id":"pay","type":"Button","pin":"bottom","props":{"label":{"kind":"literal","value":"Pay"},"fullWidth":true}}"""

    private fun screen(extra: String) = hostile(
        """{"schemaVersion":"9.28.0","documentId":"kb","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
        {"id":"r","type":"Screen","props":{"gap":"md"},"style":{"padding":"lg"},"children":[
          {"id":"bar","type":"AppBar","props":{"title":{"kind":"literal","value":"Form"},"showBack":false}},
          ${fields(12)}$extra
        ]}}]}""",
    )

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

    /** The top of the keyboard in dp from the top of the window, once it is shown and has settled. */
    private fun keyboardTop(): Float {
        val view = composeView()
        fun ime(): Int {
            var value = 0
            rule.runOnUiThread { value = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
            return value
        }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && ime() == 0) Thread.sleep(200)
        Thread.sleep(1000)
        rule.waitForIdle()
        val inset = ime()
        assertTrue("the soft keyboard did not show", inset > 0)
        val density = view.resources.displayMetrics.density
        return (view.rootView.height - inset) / density
    }

    private fun focusLast(extra: String): Float {
        rule.setContent { FixtureHost(screen(extra), Recorder()) }
        rule.onNodeWithTag("field:f11", useUnmergedTree = true).performScrollTo().performClick()
        return keyboardTop()
    }

    private fun assertAbove(text: String, top: Float) {
        // Displayed: not clipped away by the body area that has scrolled it out of sight; and not below the limit.
        rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val bounds = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("«$text» ends at ${bounds.bottom.value}, the keyboard (or the pinned block) starts at $top", bounds.bottom.value <= top + 1)
        assertTrue("«$text» is above the top of the screen: ${bounds.top.value}", bounds.top.value >= 0f)
    }

    /** A field at the bottom of a long form, tapped: it and its helper are above the keyboard. */
    @Test
    fun theFocusedFieldAndItsHelperAreAboveTheKeyboard() {
        val top = focusLast("")
        assertAbove("Help f11", top)
        assertAbove("Field 11", top)
    }

    /** With a pinned block: the block rides on the keyboard and the field with its helper ends above the block. */
    @Test
    fun theFocusedFieldIsAboveThePinnedBlockThatRidesOnTheKeyboard() {
        val top = focusLast(",$pinned")
        val pay = rule.onNodeWithText("Pay").getBoundsInRoot()
        assertTrue("the pinned block rides on the keyboard: bottom ${pay.bottom.value}, keyboard $top", pay.bottom.value <= top + 1 && pay.bottom.value >= top - 64)
        assertAbove("Help f11", pay.top.value)
    }

    /** One device pixel in dp: the tolerance of the tail checks. */
    private fun pixel() = 1f / composeView().resources.displayMetrics.density

    /** With a pinned block: under the field and its helper stays the gap of the screen (`md`, 12), not nothing. */
    @Test
    fun theFocusedFieldKeepsTheGapOfTheScreenAbovePinnedBlock() {
        focusLast(",$pinned")
        val pay = rule.onNodeWithText("Pay").getBoundsInRoot()
        val help = rule.onNodeWithText("Help f11", useUnmergedTree = true).getBoundsInRoot()
        val distance = pay.top.value - help.bottom.value
        assertTrue("from the helper to the pinned block: $distance dp, the gap of the screen is 12", Math.abs(distance - 12f) <= pixel())
    }

    /** Without a pinned block: under the field and its helper stays the bottom padding of the root (`lg`, 16) above the keyboard. */
    @Test
    fun theFocusedFieldKeepsTheBottomPaddingOfTheRootAboveTheKeyboard() {
        val top = focusLast("")
        val help = rule.onNodeWithText("Help f11", useUnmergedTree = true).getBoundsInRoot()
        val distance = top - help.bottom.value
        assertTrue("from the helper to the keyboard: $distance dp, the bottom padding of the root is 16", Math.abs(distance - 16f) <= pixel())
    }

    /**
     * A floating bar of navigation is covered by the keyboard, so its room is not added to the tail: the distance from
     * the helper to the keyboard equals the one on the same screen without `float` (the bottom padding of the root, 16).
     */
    @Test
    fun theTailUnderTheFocusedFieldIsTheSameWithAFloatingBar() {
        val floating = nav.replace("\"type\":\"NavBar\",\"props\":{}", "\"type\":\"NavBar\",\"props\":{\"float\":true}")
        assertTrue("the bar floats", floating != nav)
        val top = focusLast(",$floating")
        val help = rule.onNodeWithText("Help f11", useUnmergedTree = true).getBoundsInRoot()
        val distance = top - help.bottom.value
        android.util.Log.i("KBTAIL", "float distance=$distance top=$top help=${help.bottom.value}")
        assertTrue("from the helper to the keyboard with a floating bar: $distance dp, as without it: 16", Math.abs(distance - 16f) <= pixel())
    }

    /** With a bar of navigation: it stays under the keyboard, the field with its helper is above the keyboard. */
    @Test
    fun theBarOfNavigationStaysUnderTheKeyboardAndTheFieldIsAboveIt() {
        val top = focusLast(",$nav")
        assertAbove("Help f11", top)
        val bar = rule.onNodeWithText("Home", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("the bar of navigation is covered by the keyboard: top ${bar.top.value}, keyboard $top", bar.top.value >= top - 1)
    }

    /** A modal layer: the field of the layer and its helper rise above the keyboard. */
    @Test
    fun aModalLayerRisesAboveTheKeyboard() {
        val document = hostile(
            """{"schemaVersion":"9.28.0","documentId":"kb-modal","initialScreenId":"s","screens":[
            {"screenId":"s","name":"S","root":{"id":"r","type":"Screen","props":{"gap":"md"},"children":[
              {"id":"open","type":"Button","props":{"label":{"kind":"literal","value":"Open"},"action":{"type":"navigate","params":{"screenId":"m"}}}}]}},
            {"screenId":"m","name":"M","presentation":"modal","root":{"id":"mr","type":"Screen","props":{"gap":"md"},
              "style":{"padding":"lg","background":{"kind":"color","color":"surface"}},"children":[${fields(3, "m")}]}}]}""",
        )
        rule.setContent { FixtureHost(document, Recorder()) }
        rule.onNodeWithText("Open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("field:m2", useUnmergedTree = true).performClick()
        val top = keyboardTop()
        assertAbove("Help m2", top)
    }
}
