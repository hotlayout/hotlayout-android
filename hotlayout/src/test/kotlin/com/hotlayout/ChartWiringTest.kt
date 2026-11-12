package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.render.motion.LocalValueTransitionElapsedMs
import com.hotlayout.render.motion.ValueTransition
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.isColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with a chart and with the host actions `copy` and `share`, read back from pixels, from
 * semantics and from what the host receives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ChartWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val green = 0x00ff00

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun show(document: HotlayoutDocument, elapsed: Double? = null, content: @androidx.compose.runtime.Composable (HotlayoutDocument) -> Unit = { HotlayoutScreen(it, locale = "en") }) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                if (elapsed == null) content(document) else CompositionLocalProvider(LocalValueTransitionElapsedMs provides elapsed) { content(document) }
            }
        }
        compose.waitForIdle()
    }

    private val bars = """"variant":"bars","color":{"value":"#00ff00"},"barGap":0,"barRadius":0"""

    private fun twoPoints(first: Int = 10, second: Int = 1) =
        """"data":{"kind":"literal","points":[{"value":$first,"label":"Mon"},{"value":$second,"label":"Tue"}]}"""

    private fun chart(props: String, style: String = "", id: String = "c") =
        """{"id":"$id","type":"Chart","props":{$props}${if (style.isEmpty()) "" else ""","style":$style"""}}"""

    @Test fun barsStandOnTheBottomAndTheFirstPointIsAtTheStart() {
        // The root has no padding; the chart is 120 high without a style height and as wide as the screen (360).
        show(Screens.document("a" to chart("$bars,${twoPoints()}")))
        val image = capture()
        assertTrue("the tall first bar", image.isColor(90, 10, green))
        assertTrue("the first bar at the bottom", image.isColor(90, 118, green))
        assertTrue("the short second bar at the bottom", image.isColor(270, 118, green))
        assertFalse("nothing above the short second bar", image.isColor(270, 60, green))
        assertFalse("nothing under the chart", image.isColor(90, 130, green))
    }

    @Test fun aNamedHeightFixesTheBoxAndRightToLeftMirrorsTheDrawing() {
        val document = Screens.document("a" to chart("$bars,${twoPoints()}", style = """{"height":64}"""))
        show(document)
        val plain = capture()
        assertTrue(plain.isColor(90, 10, green))
        assertFalse("the box is 64 high", plain.isColor(90, 100, green))
    }

    @Test fun rightToLeftMirrorsTheDrawing() {
        show(Screens.document("a" to chart("$bars,${twoPoints()}", style = """{"height":64}"""))) { HotlayoutScreen(it, locale = "ar") }
        val mirrored = capture()
        assertTrue("the first bar is at the end", mirrored.isColor(270, 10, green))
        assertFalse(mirrored.isColor(90, 10, green))
    }

    @Test fun anEmptyChartKeepsItsBoxAndDrawsNothing() {
        show(Screens.document("a" to chart("$bars,\"data\":{\"kind\":\"literal\",\"points\":[{\"value\":5}]}")))
        val image = capture()
        for ((x, y) in listOf(90 to 100, 270 to 118, 180 to 60)) assertFalse("$x,$y", image.isColor(x, y, green))
    }

    @Test fun pointsComeFromACollectionOfTheData() {
        val data = """"data":{"kind":"collection","source":"spend","item":"s","valueField":"s.amount"}"""
        val scope = mapOf("spend" to JsonValue.of(listOf(mapOf("s.amount" to 10), mapOf("amount" to 1))))
        show(Screens.document("a" to chart("$bars,$data"))) { HotlayoutScreen(it, data = scope, locale = "en") }
        val image = capture()
        assertTrue(image.isColor(90, 10, green))
        assertFalse(image.isColor(270, 60, green))
    }

    private val growing = Screens.document("a" to chart("$bars,${twoPoints()},\"animateIn\":true"))

    @Test fun theAppearanceStartsAtZero() {
        show(growing, elapsed = 0.0)
        assertFalse("nothing has grown yet", capture().isColor(90, 100, green))
    }

    @Test fun theAppearanceEndsOnTheFinalChart() {
        show(growing, elapsed = ValueTransition.DURATION_MS)
        assertTrue(capture().isColor(90, 10, green))
    }

    @Test fun aChartIsOnePictureNamedByAltAndAnUnnamedOneIsHidden() {
        val labels = "$bars,${twoPoints()},\"labels\":true"
        show(Screens.document("a" to chart("$labels,\"alt\":{\"kind\":\"literal\",\"value\":\"Spending by day\"}")))
        val named = compose.onNodeWithContentDescription("Spending by day").fetchSemanticsNode()
        assertEquals(androidx.compose.ui.semantics.Role.Image, named.config.getOrNull(SemanticsProperties.Role))
        assertEquals("the labels are part of the picture, not read one by one", 0, compose.onAllNodesWithText("Mon").fetchSemanticsNodes().size)
    }

    @Test fun aChartWithoutAnAltIsHidden() {
        show(Screens.document("a" to chart("$bars,${twoPoints()},\"labels\":true")))
        assertEquals(0, compose.onAllNodesWithText("Mon").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Spending by day").fetchSemanticsNodes().size)
    }

    @Test fun copyAndShareArriveAsCheckedStandardActions() {
        fun button(label: String, action: String) =
            """{"id":"b-$label","type":"Button","props":{"label":{"kind":"literal","value":"$label"},"variant":"primary","action":$action}}"""
        val log = ArrayList<String>()
        val document = Screens.document(
            "a" to listOf(
                button("Copy code", """{"type":"copy","params":{"text":"ORB-7Q2K-91"}}"""),
                button("Share", """{"type":"share","params":{"text":"Join me","url":"https://example.com/promo"}}"""),
                button("Share nothing", """{"type":"share"}"""),
            ).joinToString(","),
        )
        show(document) {
            HotlayoutScreen(it, locale = "en", onAction = { action, _ ->
                val verdict = when (val standard = action.standard) {
                    is StandardAction.Copy -> "copy ${standard.text}"
                    is StandardAction.Share -> "share ${standard.text ?: "-"} ${standard.url ?: "-"}"
                    else -> "invalid ${action.invalid?.param} ${action.invalid?.problem?.word}"
                }
                log.add("${action.type}: $verdict")
            })
        }
        for (label in listOf("Copy code", "Share", "Share nothing")) {
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("copy: copy ORB-7Q2K-91", "share: share Join me https://example.com/promo", "share: invalid text missing"), log)
    }

    @Test fun theChartScreenFixtureReadsWithoutAnUnknownWidget() {
        val document = loadDocument(Contract.bytes("fixtures/chart-screens.json"))
        assertTrue(document.reports.none { it.kind == HotlayoutReport.Kind.UnknownWidget })
        assertNull(document.reports.firstOrNull { it.kind == HotlayoutReport.Kind.UnknownWidget })
        // The frame next to the editor's canvas: written only when asked.
        val target = System.getenv("HOTLAYOUT_FRAME_DIR") ?: return
        // The week of spending the editor's canvas shows for the collection `spending`.
        val spending = listOf("Mon" to 420, "Tue" to 260, "Wed" to 580, "Thu" to 310, "Fri" to 760, "Sat" to 540, "Sun" to 690)
            .map { (day, amount) -> mapOf("spend.day" to day, "spend.amount" to amount) }
        show(document) { HotlayoutScreen(it, data = mapOf("spending" to JsonValue.of(spending)), locale = "en") }
        java.io.File(target, "chart-screens-android.png").outputStream().use { capture().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
