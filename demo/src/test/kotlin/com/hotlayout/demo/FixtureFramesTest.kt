package com.hotlayout.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frames of real fixtures as the demo draws them (with Inter), for putting side by side with the
 * frames of the other readers. Recorded with `./gradlew :demo:recordRoborazziDebug` into
 * `demo/frames/`; a plain `./gradlew test` draws them without writing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
class FixtureFramesTest {
    @get:Rule val compose = createComposeRule()

    private fun frame(fixture: String, screen: String?) {
        compose.setContent {
            Box(Modifier.size(393.dp, 852.dp)) { FixtureScreen(fixture, screen, "en") }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("frames/$fixture${screen?.let { "-$it" } ?: ""}.png")
    }

    @Test fun rewardsShowcase() = frame("rewards-showcase-screens", null)

    @Test fun offsetOverlap() = frame("offset-screens", "overlap")

    @Test fun offsetDetails() = frame("offset-screens", "details")

    @Test fun minimalScreen() = frame("minimal-screen", null)
}
