package com.hotlayout.demo

import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hotlayout.HotlayoutDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A page that does not change draws no frames: with nothing moving, a page with glass is as idle as a page without it.
 * A page that redraws all the time keeps the device awake and drains the battery, and the test rule never reports idle.
 * The frames are counted on the window, so the page is hosted without the test rule (which would wait for idle forever).
 */
@RunWith(AndroidJUnit4::class)
class IdleGlassOnDeviceTest {
    /** A glass card in the scrolling body (inside a clip of its own), with or without a pinned top zone. */
    private fun document(topZone: Boolean, glass: Boolean): HotlayoutDocument {
        val top = if (topZone) {
            """{"id":"zone","type":"Text","pin":"top","props":{"content":{"kind":"literal","value":"Top zone"}},"style":{"height":48}},"""
        } else {
            ""
        }
        val blur = if (glass) ""","backdropBlur":8""" else ""
        return hostile(
            """{"schemaVersion":"9.27.0","documentId":"idle-glass","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
            {"id":"r","type":"Screen","props":{"gap":"md"},"children":[
              $top
              {"id":"glass","type":"Container","props":{"direction":"column","gap":"none"},
               "style":{"height":200$blur,"background":{"kind":"color","color":{"value":"#ff000080"}}},
               "children":[{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Glass card"}}}]},
              {"id":"tall","type":"Container","props":{"direction":"column","gap":"none"},"style":{"height":1000},
               "children":[{"id":"e","type":"Text","props":{"content":{"kind":"literal","value":"end"}}}]}
            ]}}]}""",
        )
    }

    /** How many times the window drew in [seconds] after the page settled. */
    private fun framesWhileIdle(document: HotlayoutDocument, seconds: Long = 3): Int {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val count = AtomicInteger()
        var host: ComponentActivity? = null
        scenario.onActivity { activity ->
            host = activity
            activity.setContent { FixtureHost(document, Recorder(), modifier = Modifier.fillMaxSize(), safeAreaTop = 40.dp) }
            activity.window.decorView.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener { count.incrementAndGet() })
        }
        Thread.sleep(3000)
        val settled = count.get()
        Thread.sleep(seconds * 1000)
        val frames = count.get() - settled
        // Not `close()`: it waits for an idle page, and a page that keeps drawing never is.
        host?.let { it.runOnUiThread { it.finish() } }
        Thread.sleep(500)
        return frames
    }

    @Test
    fun aPageWithoutGlassIsIdle() {
        assertEquals("frames drawn by an unchanged page", 0, framesWhileIdle(document(topZone = true, glass = false)))
    }

    @Test
    fun aGlassInTheScrollingBodyIsIdle() {
        assertEquals("frames drawn by an unchanged page with glass", 0, framesWhileIdle(document(topZone = false, glass = true)))
    }

    @Test
    fun aGlassUnderATopZoneIsIdle() {
        assertEquals("frames drawn by an unchanged page with glass", 0, framesWhileIdle(document(topZone = true, glass = true)))
    }

    @Test
    fun aGlassNavigationBarIsIdle() {
        assertEquals("frames drawn by an unchanged page with glass", 0, framesWhileIdle(fixture("float-nav-swipe-screens", "home")))
    }
}
