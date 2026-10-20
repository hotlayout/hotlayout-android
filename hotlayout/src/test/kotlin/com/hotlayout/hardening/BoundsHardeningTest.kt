package com.hotlayout.hardening

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.dataScopeOf
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.render.motion.NodeMotionFrames
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Limits of the space and the time a screen takes: it draws and takes touches only inside the box
 * the host gave it, a long list stays bounded, a tree at the parse limit draws, and endless motion
 * stops for «reduce motion».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class BoundsHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun lit(value: String) = Screens.literal(value)

    private fun box(id: String, w: Int, h: Int, color: String, fields: String = "", props: String = "") =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"$props},"style":{"width":$w,"height":$h,"background":{"kind":"color","color":{"value":"$color"}}}$fields,"children":[]}"""

    // ---------------------------------------------------------------- the host's box

    /**
     * Pieces of a document that reach past their own box: an overlay pushed out of a pinned stack, a
     * wide shadow, a pulse. The screen is given 360 × 200 in the middle of a red host page that is
     * itself pressable.
     */
    private val escaping: HotlayoutDocument = Screens.document(
        "a" to listOf(
            """{"id":"stack","type":"Stack","props":{},"pin":"bottom","children":[
                ${box("base", 360, 40, "#ffffff")},
                ${box("out", 200, 100, "#000000", ""","layer":{"alignHorizontal":"start","alignVertical":"bottom","offset":{"y":{"toward":"bottom","by":128},"x":{"toward":"start","by":64}}}""", ""","label":${lit("Out")},"action":{"type":"escaped"}""")}]}""",
            box(
                "shadowed", 100, 40, "#00ff00",
                ""","motion":{"kind":"pulse","scale":1.5,"duration":2000}""",
            ).replace(""""background":""", """"shadow":[{"offsetX":0,"offsetY":-60,"blur":40,"spread":40,"color":{"value":"#000000"}}],"background":"""),
        ).joinToString(","),
    )

    private val hostTaps = ArrayList<String>()
    private val actions = ArrayList<String>()

    private fun showInsideHost(document: HotlayoutDocument) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                Box(Modifier.fillMaxSize().background(Color.Red).testTag("host").clickable { hostTaps.add("host") })
                Box(Modifier.offset(y = 200.dp).size(360.dp, 200.dp)) {
                    // The clock of node motion is held, so the pulse stands at a moment of its run and the test can get idle.
                    CompositionLocalProvider(LocalNodeMotionClockMs provides 500.0) {
                        HotlayoutScreen(document, onAction = { a, _ -> actions.add(a.type) })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun nothingIsDrawnOutsideTheHostsBox() {
        showInsideHost(escaping)
        val image = capture()
        val outside = ArrayList<String>()
        for (y in (0 until image.height step 4).filter { it < 200 || it >= 400 }) {
            for (x in 0 until image.width step 4) {
                val rgb = image.getPixel(x, y) and 0xFFFFFF
                if (rgb != 0xFF0000) { outside.add("($x, $y) #%06x".format(rgb)); break }
            }
        }
        assertEquals("rows of the host page painted by the screen", emptyList<String>(), outside.take(10))
    }

    @Test fun aTouchOutsideTheHostsBoxBelongsToTheHost() {
        showInsideHost(escaping)
        // Where the pushed-out overlay would be drawn: below the screen's box.
        compose.onNodeWithTag("host").performTouchInput { click(androidx.compose.ui.geometry.Offset(60f, 470f)) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), actions)
        assertEquals(listOf("host"), hostTaps)
    }

    // ---------------------------------------------------------------- lists

    private fun cells(document: HotlayoutDocument, records: Int): Pair<Int, Double> {
        val rows = (0 until records).map { mapOf("id" to "r$it", "name" to "Row $it") }
        val started = System.nanoTime()
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = dataScopeOf("rows" to rows)) } }
        compose.waitForIdle()
        val seconds = (System.nanoTime() - started) / 1e9
        return compose.onAllNodesWithText("Row ", substring = true).fetchSemanticsNodes().size to seconds
    }

    private fun repeater(id: String, item: String, child: String) =
        """{"id":"$id","type":"Repeater","props":{"source":"rows","item":"$item","gap":"none"},"children":[$child]}"""

    private val rowText = """{"id":"t","type":"Text","props":{"content":{"kind":"binding","path":"row.name"}}}"""

    /**
     * A collection comes from the host's data, which comes from a server: its length has no bound.
     * A list draws at most `x-delivery.maxCollectionItems` records of the data contract.
     */
    @Test fun aLongListDrawsAtMostTheContractsCeiling() {
        val ceiling = Contract.json("data-contract.json")["x-delivery"]!!["maxCollectionItems"]!!.number!!.toInt()
        val (composed, seconds) = cells(Screens.document("a" to repeater("list", "row", rowText)), 3_000)
        assertEquals("composed $composed rows of 3000 in $seconds s", ceiling, composed)
    }

    /**
     * The ceiling is per list, so lists nested in the template multiply it: three levels over one
     * collection of 15 records draw 15³ = 3 375 rows, and four levels of 50 would be 6 250 000. The
     * document decides the nesting, so a screen has a budget for all the instances it draws
     * (`x-listBudget.perScreen`: two levels of the ceiling, 50 × (1 + 50)).
     */
    @Test fun nestedListsDoNotMultiplyTheCeiling() {
        val nested = repeater("outer", "a", repeater("middle", "b", repeater("inner", "row", rowText)))
        val (composed, seconds) = cells(Screens.document("a" to nested), 15)
        assertTrue("nested lists composed $composed rows in $seconds s; expected at most $SCREEN_INSTANCE_BUDGET", composed <= SCREEN_INSTANCE_BUDGET)
    }

    // ---------------------------------------------------------------- depth

    @Test fun aTreeAtTheParseLimitDraws() {
        val kinds = listOf("Column", "Row", "Container", "Stack")
        var inner = Screens.text("leaf", lit("Leaf"))
        for (level in 31 downTo 1) {
            val type = kinds[level % kinds.size]
            val props = when (type) { "Container" -> """{"direction":"column","gap":"none"}"""; "Stack" -> "{}"; else -> """{"gap":"none"}""" }
            inner = """{"id":"n$level","type":"$type","props":$props,"children":[$inner]}"""
        }
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to inner)) } }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Leaf").fetchSemanticsNodes().size)
    }

    // ---------------------------------------------------------------- reduced motion

    private val moving: HotlayoutDocument = Screens.document(
        "a" to listOf(
            box("float", 40, 40, "#ff0000", ""","motion":{"kind":"float","axis":"horizontal","amplitude":20,"duration":1200}"""),
            box("pulse", 40, 40, "#0000ff", ""","motion":{"kind":"pulse","scale":1.2,"opacity":0.5,"duration":800,"delay":100}"""),
        ).joinToString(","),
    )

    private fun framesAskedFor(animatorScale: Float): Int {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, animatorScale)
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(moving) } }
        compose.mainClock.advanceTimeByFrame()
        val before = NodeMotionFrames.requested.get()
        compose.mainClock.advanceTimeBy(5_000)
        return NodeMotionFrames.requested.get() - before
    }

    @Test fun endlessMotionAsksForNoFrameUnderReducedMotion() {
        assertEquals(0, framesAskedFor(0f))
        // And the screen becomes idle: an endless clock would keep `waitForIdle` waiting.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun endlessMotionRunsOtherwise() {
        assertTrue(framesAskedFor(1f) > 50)
    }

    private companion object {
        const val SCREEN_INSTANCE_BUDGET = 2550
    }
}
