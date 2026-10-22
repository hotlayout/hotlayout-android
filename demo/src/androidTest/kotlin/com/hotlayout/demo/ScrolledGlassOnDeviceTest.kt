package com.hotlayout.demo

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hotlayout.HotlayoutDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A glass node (and a node over glass) is drawn at the page, outside the clip of its scrolling ancestors; the body scrolled
 * under the top zone must not show it over the zone or in the status bar. The page is hosted without the Compose test rule:
 * a page with glass redraws all the time, so the rule never reports idle and every query of it hangs. The drag and the
 * screenshot go through the device, with real frames.
 */
@RunWith(AndroidJUnit4::class)
class ScrolledGlassOnDeviceTest {
    private fun document(topZone: Boolean): HotlayoutDocument {
        val top = if (topZone) {
            """{"id":"zone","type":"Text","pin":"top","props":{"content":{"kind":"literal","value":"Top zone"}},"style":{"height":48,"color":{"value":"#ffffff"}}},"""
        } else {
            ""
        }
        return hostile(
            """{"schemaVersion":"9.27.0","documentId":"scrolled-glass","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
            {"id":"r","type":"Screen","props":{"gap":"md"},"style":{"background":{"kind":"color","color":{"value":"#000000"}}},"children":[
              $top
              {"id":"glass","type":"Container","props":{"direction":"column","gap":"none"},
               "style":{"height":200,"backdropBlur":8,"background":{"kind":"color","color":{"value":"#ff0000"}}},
               "children":[{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Glass card"}}}]},
              {"id":"tall","type":"Container","props":{"direction":"column","gap":"none"},"style":{"height":1000},
               "children":[{"id":"e","type":"Text","props":{"content":{"kind":"literal","value":"end"}}}]}
            ]}}]}""",
        )
    }

    private fun drag(x: Float, fromY: Float, toY: Float) {
        val uiAutomation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            uiAutomation.injectInputEvent(event, false)
            event.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, fromY)
        val steps = 20
        for (i in 1..steps) {
            Thread.sleep(16)
            send(android.view.MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * i / steps)
        }
        send(android.view.MotionEvent.ACTION_UP, toY)
    }

    private fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) < 80 && Color.blue(pixel) < 80

    /** The red pixels of the card above [limit] points from the top of the window, and under it, after the page is dragged up. */
    private fun redAfterDrag(topZone: Boolean, limit: Float): Pair<Int, Int> {
        val density = targetContext.resources.displayMetrics.density
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        run {
            var host: ComponentActivity? = null
            scenario.onActivity { activity ->
                host = activity
                activity.setContent { FixtureHost(document(topZone), Recorder(), modifier = Modifier.fillMaxSize(), safeAreaTop = 40.dp) }
            }
            Thread.sleep(2000)
            val width = device.displayWidth
            val height = device.displayHeight
            // Drag from low on the page by 100 points (and the touch slop); the device swipe waits for an idle page, this does not.
            val drag = (100 * density).toInt() + 60
            drag(width / 2f, height * 0.8f, height * 0.8f - drag)
            Thread.sleep(2000)
            val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val edge = (limit * density).toInt()
            var above = 0
            var below = 0
            for (y in 0 until shot.height) for (x in 0 until shot.width step 4) {
                if (!isRed(shot.getPixel(x, y))) continue
                if (y < edge) above++ else below++
            }
            // Not `close()` nor `onActivity`: they wait for an idle page, and a page with glass never is.
            host?.let { it.runOnUiThread { it.finish() } }
            return above to below
        }
    }

    /**
     * Only the body scrolls, and it is clipped by the bottom edge of the top zone: a glass card scrolled up is not drawn over
     * the pinned node nor in the status bar (the Orbit home card showed over «Good evening» and the clock).
     */
    @Test
    fun scrolledGlassIsClippedByTheTopZone() {
        // The top zone stands from the inset (40) to 40 + its height (48).
        val (above, below) = redAfterDrag(topZone = true, limit = 88f)
        assertTrue("the card is still in the body: $below", below > 0)
        assertEquals("red pixels of the scrolled card over the top zone and the status bar", 0, above)
    }

    /** Without any header the body area starts under the top inset: the inset shows no content. */
    @Test
    fun scrolledGlassIsClippedUnderTheTopInset() {
        val (above, below) = redAfterDrag(topZone = false, limit = 40f)
        assertTrue("the card is still in the body: $below", below > 0)
        assertEquals("red pixels of the scrolled card in the status bar", 0, above)
    }
}
