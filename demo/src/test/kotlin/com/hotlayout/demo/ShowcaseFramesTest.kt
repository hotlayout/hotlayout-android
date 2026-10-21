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

/** Frames of the showcase apps at rest, for putting side by side with the other readers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
class ShowcaseFramesTest {
    @get:Rule val compose = createComposeRule()

    private fun frame(app: String, screen: String?, name: String, top: Int? = null, bottom: Int? = null) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { Box(Modifier.size(393.dp, 852.dp)) { FixtureScreen(app, screen, "en", safeAreaTop = top?.dp, safeAreaBottom = bottom?.dp) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("frames/$name.png")
    }

    @Test fun orbitHome() = frame("showcase-orbit", "home", "showcase-orbit-home", top = 47, bottom = 34)
    @Test fun orbitMission() = frame("showcase-orbit", "mission", "showcase-orbit-mission", top = 47, bottom = 34)
}
