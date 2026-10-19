package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.render.screen.ScreenAnatomy
import com.hotlayout.render.screen.ScreenZone
import com.hotlayout.render.screen.ScreenZonesLayout
import com.hotlayout.render.screen.ScreenZonesSettings
import com.hotlayout.render.screen.ZoneReport
import com.hotlayout.render.screen.inZone
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows of the zone tables through the REAL layout of a screen: children of the sizes the table
 * names stand where the table says, and the layout is as tall as the table says.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1200dp-mdpi")
class ScreenAnatomyLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Probe(
        val settings: ScreenZonesSettings,
        val width: Double = 320.0,
        val height: Double,
        val bar: Double? = null,
        val top: Double? = null,
        val bodyNatural: Double? = null,
        val pinned: Double? = null,
        val nav: Double? = null,
        val rightToLeft: Boolean = false,
    )

    private val probe = mutableStateOf<Probe?>(null)
    private val frames = HashMap<ScreenZone, Rect>()
    private var root = Rect.Zero

    private fun setUp() {
        compose.setContent {
            val current = probe.value ?: return@setContent
            val report = androidx.compose.runtime.remember { ZoneReport() }
            CompositionLocalProvider(LocalLayoutDirection provides if (current.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(current.width.dp, current.height.dp)) {
                    // A layer is as tall as its content: it is given loose height.
                    val size = if (current.settings.shrink) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
                    ScreenZonesLayout(current.settings, report, size.onGloballyPositioned { root = it.boundsInRoot() }) {
                        fun tag(zone: ScreenZone, modifier: Modifier) = modifier.inZone(zone).onGloballyPositioned { frames[zone] = it.boundsInRoot() }
                        current.bar?.let { Box(tag(ScreenZone.Bar, Modifier.height(it.dp))) }
                        current.top?.let { Box(tag(ScreenZone.Top, Modifier.height(it.dp))) }
                        Box(tag(ScreenZone.Body, current.bodyNatural?.let { Modifier.height(it.dp) } ?: Modifier))
                        current.pinned?.let { Box(tag(ScreenZone.Pinned, Modifier.height(it.dp))) }
                        current.nav?.let { Box(tag(ScreenZone.Nav, Modifier.height(it.dp))) }
                    }
                }
            }
        }
    }

    private fun draw(next: Probe): Map<ScreenZone, Rect> {
        frames.clear()
        probe.value = next
        compose.waitForIdle()
        return HashMap(frames)
    }

    private fun number(value: com.hotlayout.json.JsonValue?) = value?.number ?: 0.0

    private fun settings(row: JsonObject): ScreenZonesSettings {
        val padding = row["padding"]?.obj ?: JsonObject()
        val bottom = row["bottom"]?.obj
        return ScreenZonesSettings(
            shrink = row["shrink"]?.bool == true,
            paddingTop = number(padding["top"]), paddingBottom = number(padding["bottom"]), gap = number(row["gap"]),
            bodyEmpty = row["bodyContentHeight"]?.number == null, firstPull = number(row["firstPull"]), bodyTop = row["bodyTop"],
            safeTop = number(row["safeTop"]), safeBottom = number(bottom?.get("safeBottom")),
            navFloat = row["navFloat"]?.bool == true && bottom?.get("navBarHeight") != null,
        )
    }

    private fun close(actual: Float?, expected: Double?, what: String) {
        if (actual == null || expected == null) {
            assertEquals(what, expected == null, actual == null)
            return
        }
        assertEquals(what, expected, actual.toDouble(), 0.6)
    }

    @Test fun zoneRowsThroughTheLayout() {
        setUp()
        val chrome = Contract.json("fixtures/media-chrome/media-chrome-cases.json")
        val bottomTable = Contract.json("fixtures/bottom-zone/bottom-zone-cases.json")
        val rows = Contract.rows(chrome, "zoneCases") + Contract.rows(bottomTable, "zoneCases") + Contract.rows(bottomTable, "floatZoneCases") + Contract.rows(chrome, "screenTopZoneCases")
        for (row in rows) {
            val name = row.name()
            val expect = row["expect"]
            val settings = settings(row)
            val height = number(row["height"])
            val safeTop = ScreenAnatomy.safeTop(settings.safeTop, height)
            val bottom = row["bottom"]?.obj
            val nav = bottom?.get("navBarHeight")?.number
            val natural = if (settings.shrink) expect?.get("contentHeight")?.number else null
            val result = draw(
                Probe(
                    settings, height = height, bar = row["appBarHeight"]?.number?.let { it + safeTop }, bodyNatural = natural,
                    pinned = bottom?.get("pinnedHeight")?.number, nav = nav?.let { if (settings.navFloat) it else it + settings.safeBottom },
                ),
            )
            close(root.height, expect?.get("height")?.number, "$name: height")
            expect?.get("appBar")?.takeIf { !it.isNull }?.let { bar ->
                close(result[ScreenZone.Bar]?.top, bar["top"]?.number, "$name: bar top")
                close(result[ScreenZone.Bar]?.height, bar["height"]?.number, "$name: bar height")
            }
            expect?.get("body")?.let { area ->
                // A body with no height has no bounds to read.
                if (number(area["height"]) > 0) close(result[ScreenZone.Body]?.top, area["top"]?.number, "$name: body top")
                close(result[ScreenZone.Body]?.height, area["height"]?.number, "$name: body height")
            }
            expect?.get("bottom")?.takeIf { !it.isNull }?.let { zone ->
                zone["pinned"]?.takeIf { !it.isNull }?.let {
                    close(result[ScreenZone.Pinned]?.top, it["top"]?.number, "$name: pinned top")
                    close(result[ScreenZone.Pinned]?.height, it["height"]?.number, "$name: pinned height")
                }
                zone["navBar"]?.takeIf { !it.isNull }?.let {
                    close(result[ScreenZone.Nav]?.top, it["top"]?.number, "$name: nav top")
                    close(result[ScreenZone.Nav]?.height, it["height"]?.number, "$name: nav height")
                }
            }
        }
    }

    /** The root padding is shared: the bar and the pinned block stand inside the side padding, the body and the bar of navigation take the whole width; right-to-left text mirrors it. */
    @Test fun sidePaddingsAreShared() {
        setUp()
        val settings = ScreenZonesSettings(paddingStart = 16.0, paddingEnd = 24.0, paddingTop = 8.0, paddingBottom = 8.0, gap = 12.0)
        for (rtl in listOf(false, true)) {
            val result = draw(Probe(settings, width = 340.0, height = 800.0, bar = 40.0, pinned = 50.0, nav = 60.0, rightToLeft = rtl))
            assertEquals(300f, result[ScreenZone.Bar]!!.width, 0.5f)
            assertEquals("bar, rtl $rtl", if (rtl) 24f else 16f, result[ScreenZone.Bar]!!.left, 0.5f)
            assertEquals(300f, result[ScreenZone.Pinned]!!.width, 0.5f)
            assertEquals(if (rtl) 24f else 16f, result[ScreenZone.Pinned]!!.left, 0.5f)
            assertEquals(340f, result[ScreenZone.Body]!!.width, 0.5f)
            assertEquals(340f, result[ScreenZone.Nav]!!.width, 0.5f)
            assertEquals(0f, result[ScreenZone.Nav]!!.left, 0.5f)
        }
    }

    /** The top zone stands under the bar; the body starts under the zone. */
    @Test fun topZoneStandsUnderTheBar() {
        setUp()
        val result = draw(Probe(ScreenZonesSettings(paddingTop = 8.0, gap = 20.0), height = 600.0, bar = 56.0, top = 68.0))
        assertEquals(8f, result[ScreenZone.Bar]!!.top, 0.5f)
        assertEquals(64f, result[ScreenZone.Top]!!.top, 0.5f)
        assertEquals(64f + 68f, result[ScreenZone.Body]!!.top, 0.5f)
        assertEquals(600f - 64f - 68f, result[ScreenZone.Body]!!.height, 0.5f)
    }

    /** The keyboard raises the pinned block over itself and leaves the bar of navigation under it. */
    @Test fun keyboardRaisesThePinnedBlockOnly() {
        setUp()
        val result = draw(Probe(ScreenZonesSettings(paddingBottom = 16.0, keyboard = 300.0), height = 800.0, pinned = 48.0, nav = 60.0))
        assertEquals((800 - 300).toFloat(), result[ScreenZone.Pinned]!!.bottom + 16f, 0.5f)
        assertEquals((800 - 300).toFloat(), result[ScreenZone.Nav]!!.top, 0.5f)
        assertNotNull(result[ScreenZone.Body])
        assertEquals(240.0, ScreenAnatomy.keyboardLift(300.0, 60.0), 0.0)
        assertEquals(0.0, ScreenAnatomy.keyboardLift(40.0, 60.0), 0.0)
    }
}
