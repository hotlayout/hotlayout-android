package com.hotlayout.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The adaptive fixture at the three widths of the width classes, in both writing directions: the bar of navigation moves from the
 * bottom (393) into the app bar and to a bar of its own (768, 1280), a sheet becomes a window. Recorded with
 * `./gradlew :demo:recordRoborazziDebug` into `demo/frames/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveFramesTest {
    @get:Rule val compose = createComposeRule()

    private val sampleNow = java.time.Instant.parse("2026-10-10T09:00:00.000Z").toEpochMilli()

    private fun frame(width: Int, height: Int, name: String, locale: String = "en", before: () -> Unit = {}) {
        compose.setContent { Box(Modifier.size(width.dp, height.dp)) { FixtureScreen("adaptive-screens", null, locale, sampleNow) } }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("frames/$name.png")
    }

    private fun openTab(label: String) = { compose.onNode(hasContentDescription(label) and hasClickAction()).performClick() }

    @Config(qualifiers = "w393dp-h852dp-xxhdpi") @Test fun homeCompact() = frame(393, 852, "adaptive-screens-home-393")

    @Config(qualifiers = "w768dp-h1024dp-xhdpi") @Test fun homeMedium() = frame(768, 1024, "adaptive-screens-home-768")

    @Config(qualifiers = "w1280dp-h800dp-xhdpi") @Test fun homeExpanded() = frame(1280, 800, "adaptive-screens-home-1280")

    @Config(qualifiers = "w768dp-h1024dp-xhdpi") @Test fun homeMediumRtl() = frame(768, 1024, "adaptive-screens-home-768-rtl", "ar")

    @Config(qualifiers = "w393dp-h852dp-xxhdpi") @Test fun labCompact() = frame(393, 852, "adaptive-screens-lab-393") { openTab("Layout lab")() }

    @Config(qualifiers = "w1280dp-h800dp-xhdpi") @Test fun labExpanded() = frame(1280, 800, "adaptive-screens-lab-1280") { openTab("Layout lab")() }

    @Config(qualifiers = "w768dp-h1024dp-xhdpi") @Test fun rewardsOwnBar() = frame(768, 1024, "adaptive-screens-rewards-768") { openTab("Rewards")() }

    @Config(qualifiers = "w768dp-h1024dp-xhdpi") @Test fun bonusWindow() = frame(768, 1024, "adaptive-screens-bonus-768") { compose.onNodeWithText("Claim +20 pts").performScrollTo().performClick() }

    @Config(qualifiers = "w393dp-h852dp-xxhdpi") @Test fun bonusSheet() = frame(393, 852, "adaptive-screens-bonus-393") { compose.onNodeWithText("Claim +20 pts").performScrollTo().performClick() }
}
