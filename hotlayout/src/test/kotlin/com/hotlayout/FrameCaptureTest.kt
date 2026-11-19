package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.render.motion.LocalNodeAppearClockMs
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.support.Contract
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Writes the frames of the minors 9.38.0, 9.40.0 and 9.42.0 as PNG files for looking at, next to the canvas of the builder. It does
 * nothing unless `HOTLAYOUT_FRAME_DIR` names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FrameCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val folder: String? = System.getenv("HOTLAYOUT_FRAME_DIR")

    private fun write(name: String) {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(folder!!).apply { mkdirs() }.let { dir -> java.io.File(dir, "android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun document(name: String, screen: String? = null): HotlayoutDocument {
        val text = String(Contract.bytes("fixtures/$name.json"), Charsets.UTF_8)
        return loadDocument(if (screen == null) text else text.replace(Regex("\"initialScreenId\"\\s*:\\s*\"[^\"]*\""), "\"initialScreenId\": \"$screen\""))
    }

    private fun show(document: HotlayoutDocument, data: DataScope = emptyMap(), motionMs: Double? = null, appearMs: Double? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalNodeMotionClockMs provides motionMs, LocalNodeAppearClockMs provides appearMs) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = "en") }
            }
        }
        compose.waitForIdle()
    }

    @Test fun motionTurnFrames() {
        assumeNotNull(folder)
        // The rest pose of the motions and the end state of the appearances: what the canvas of the builder shows.
        show(document("motion-turn-screens"), motionMs = 0.0, appearMs = 100_000.0)
        write("motion-turn-rest")
    }

    @Test fun motionTurnLive() {
        assumeNotNull(folder)
        // Endless motions never let the screen go idle: the clock of the test is held and moved by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document("motion-turn-screens"), locale = "en") } }
        compose.mainClock.advanceTimeBy(1200)
        write("motion-turn-live")
    }

    @Test fun carouselAutoAdvanceStart() {
        assumeNotNull(folder)
        show(document("carousel-auto-advance-screens"), data = mapOf("account.cardIndex" to JsonValue.Num(0.0)))
        write("auto-advance-stories-start")
    }

    @Test fun carouselAutoAdvanceEnd() {
        assumeNotNull(folder)
        show(document("carousel-auto-advance-screens"), data = mapOf("account.cardIndex" to JsonValue.Num(2.0)))
        write("auto-advance-stories-end")
    }

    @Test fun carouselAutoAdvancePromo() {
        assumeNotNull(folder)
        show(document("carousel-auto-advance-screens", screen = "promo"))
        write("auto-advance-promo")
    }

    @Test fun carouselAutoAdvanceAfterOneStep() {
        assumeNotNull(folder)
        show(document("carousel-auto-advance-screens"))
        compose.mainClock.advanceTimeBy(6000)
        write("auto-advance-stories-after-one-step")
    }

    @Test fun screenTransitionFrames() {
        assumeNotNull(folder)
        show(document("screen-transition-screens"))
        write("screen-transition-welcome")
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(100)
        write("screen-transition-push-mid")
        compose.mainClock.advanceTimeBy(400)
        write("screen-transition-interests")
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Read the terms").performClick()
        compose.mainClock.advanceTimeBy(100)
        write("screen-transition-sheet-mid")
        compose.mainClock.advanceTimeBy(500)
        write("screen-transition-sheet-open")
    }
}
