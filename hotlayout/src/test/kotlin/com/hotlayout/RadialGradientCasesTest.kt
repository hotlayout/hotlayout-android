package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hotlayout.render.adaptive.ConfigChangeState
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenCapture
import com.hotlayout.support.Screens
import com.hotlayout.support.channels
import com.hotlayout.support.isColor
import com.hotlayout.support.name
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The radial gradient against the shared table (`radial-gradient/radial-gradient-cases.json`): the radius in pixels, the readings of
 * a gradient of a node and of a theme token, and the circle on the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class RadialGradientCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/radial-gradient/radial-gradient-cases.json")
    private val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())

    @Test fun everyGroupIsRun() {
        assertEquals(setOf("stops", "geometryCases", "documentCases"), Contract.groups(table))
    }

    @Test fun radiusInPixels() {
        val rows = Contract.rows(table, "geometryCases")
        assertEquals(48, rows.size)
        val stops = table["stops"]?.array.orEmpty().joinToString(",") { """{"color":{"value":"${it["color"]?.string}"},"position":${it["position"]?.number}}""" }
        for (row in rows) {
            val width = row["box"]?.get("width")?.number ?: Double.NaN
            val height = row["box"]?.get("height")?.number ?: Double.NaN
            val center = row["center"]
            val own = """{"shape":"radial","center":{"x":${center?.get("x")?.number},"y":${center?.get("y")?.number}},"radius":${row["radius"]?.number},"stops":[$stops]}"""
            val radial = resolver.gradient(JsonValue.parse(own))?.radial
            assertNotNull(row.name(), radial)
            val radius = radial!!.radiusIn(width, height)
            assertEquals(row.name(), row["radiusPx"]?.number ?: Double.NaN, radius, 1e-5)
            // The share of the shorter side that Flutter's own gradient takes.
            assertEquals(row.name(), row["flutterRadius"]?.number ?: Double.NaN, radius / minOf(width, height), 1e-6)
            assertEquals(row.name(), row["farthestCornerPx"]?.number ?: Double.NaN, radius / radial.radius, 1e-5)
        }
    }

    /** A form that does not resolve is no gradient; a number outside its bounds is brought to them. */
    private val notDrawn = setOf(
        "radial with an angle", "linear with a shape field", "shape without center", "shape without radius", "radial without shape", "unknown shape",
        "linear with a center", "radial with one stop",
    )

    @Test fun ownGradient() {
        val rows = Contract.rows(table, "documentCases")
        assertEquals(20, rows.size)
        for (row in rows) {
            val raw = row["gradient"]!!
            val gradient = resolver.gradient(raw)
            when {
                row["valid"]?.bool == true -> {
                    assertNotNull(row.name(), gradient)
                    assertEquals(row.name(), raw["shape"]?.string == "radial", gradient!!.radial != null)
                }
                row.name() in notDrawn -> assertNull(row.name(), gradient)
                else -> {
                    val radial = gradient?.radial
                    assertNotNull("${row.name()}: a number outside its bounds is brought to them, the gradient stays", radial)
                    assertTrue(row.name(), radial!!.centerX in 0.0..1.0 && radial.centerY in 0.0..1.0)
                    assertTrue(row.name(), radial.radius in 0.05..2.0)
                }
            }
        }
    }

    private fun themeWith(gradient: String): HotlayoutThemeSet? =
        HotlayoutThemeSet.read("""{"themeContractVersion":"4.1.0","defaultThemeId":"light","themes":{"light":{"brightness":"light","gradient":{"probe":$gradient}}}}""")

    @Test fun themeToken() {
        val colors = listOf("#336699", "#cc9966")
        for (row in Contract.rows(table, "documentCases")) {
            val source = row["gradient"]!!
            val stops = source["stops"]?.array.orEmpty().mapIndexed { index, stop -> """{"color":"${colors[index % 2]}","position":${stop["position"]?.number}}""" }
            val fields = source.obj!!.filter { it.first != "stops" }.joinToString(",") { "\"${it.first}\":${ConfigChangeState.encode(it.second)}" }
            val token = themeWith("""{$fields,"stops":[${stops.joinToString(",")}]}""")?.theme("light")?.gradient?.get("probe")
            when {
                row["valid"]?.bool == true -> {
                    assertNotNull(row.name(), token)
                    assertEquals(row.name(), source["shape"]?.string == "radial", token!!.radial != null)
                    token.radial?.let { radial ->
                        val center = source["center"]
                        assertEquals(row.name(), listOf(center?.get("x")?.number, center?.get("y")?.number, source["radius"]?.number), listOf(radial.centerX, radial.centerY, radial.radius))
                    }
                }
                // An entry of the wrong form is dropped: the theme has no such gradient.
                row.name() in notDrawn -> assertNull(row.name(), token)
                else -> {
                    val radial = token?.radial
                    assertNotNull("${row.name()}: a number outside its bounds is brought to them", radial)
                    assertTrue(row.name(), radial!!.centerX in 0.0..1.0 && radial.centerY in 0.0..1.0 && radial.radius in 0.05..2.0)
                }
            }
        }
    }

    private fun fill(width: Int, height: Int, x: Double, y: Double, radius: Double, locale: String = "en"): android.graphics.Bitmap {
        val gradient = """{"shape":"radial","center":{"x":$x,"y":$y},"radius":$radius,"stops":[{"color":{"value":"#ff0000"},"position":0},{"color":{"value":"#0000ff"},"position":1}]}"""
        val node = """{"id":"g","type":"Container","props":{"direction":"column"},"style":{"width":$width,"height":$height,"background":{"kind":"gradient","gradient":$gradient}},"children":[]}"""
        return ScreenCapture(compose).show(Screens.document("a" to node), locale)
    }

    /** 300 × 100, the centre in the middle, radius 0.5: R = 0.5 * 158.11 = 79.06, and 35 px right and 35 px down are the same distance. */
    @Test fun circleOnAWideBox() {
        val image = fill(300, 100, 0.5, 0.5, 0.5)
        assertTrue(image.isColor(150, 50, 0xff0000))
        assertTrue("past the circle: the last colour", image.isColor(5, 5, 0x0000ff))
        assertTrue(image.isColor(295, 95, 0x0000ff))
        val right = image.channels(150 + 35, 50)
        val below = image.channels(150, 50 + 35)
        assertEquals(right.first.toDouble(), below.first.toDouble(), 8.0)
        assertEquals(right.third.toDouble(), below.third.toDouble(), 8.0)
        assertTrue("a circle, not an ellipse: ${right}", right.first > 60 && right.third > 60)
    }

    @Test fun centreAtACornerAndNotMirrored() {
        val image = fill(200, 200, 0.0, 0.0, 0.5)
        assertTrue(image.isColor(2, 2, 0xff0000))
        assertTrue(image.isColor(197, 197, 0x0000ff))
    }

    @Test fun centreIsPhysicalInRightToLeftText() {
        // A box at the start of a right-to-left screen stands at the right edge; its centre (0, 0) is still its top left corner.
        val image = fill(200, 200, 0.0, 0.0, 0.5, locale = "ar")
        assertTrue("the box starts at the right", image.isColor(160 + 2, 2, 0xff0000))
        assertTrue(image.isColor(358, 197, 0x0000ff))
    }
}
