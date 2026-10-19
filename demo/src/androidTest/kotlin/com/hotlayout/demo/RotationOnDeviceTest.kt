package com.hotlayout.demo

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The demo activity recreated by a rotation keeps the stack and what the person typed. */
@RunWith(AndroidJUnit4::class)
class RotationOnDeviceTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun rotationKeepsTheStackAndTheEnteredText() {
        val intent = Intent(targetContext, MainActivity::class.java)
            .putExtra("fixture", "survey-screens").putExtra("screen", "details").putExtra("locale", "en")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.onNodeWithTag("field:name", useUnmergedTree = true).performTextInput("Ana")
            rule.onNodeWithTag("field:age", useUnmergedTree = true).performTextInput("41")
            rule.onNodeWithText("Next").performClick()
            rule.onNodeWithText("Send answers").assertExists()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1500)
            rule.waitForIdle()
            rule.onNodeWithText("Send answers").assertExists()
            device.pressBack()
            rule.waitForIdle()
            rule.onNodeWithText("Ana", useUnmergedTree = true).assertExists()
            rule.onNodeWithText("41", useUnmergedTree = true).assertExists()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }
}
