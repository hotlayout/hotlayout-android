package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hotlayout.render.adaptive.ConfigChangeState
import com.hotlayout.render.paint.InsetShadowGeometry
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenCapture
import com.hotlayout.support.Screens
import com.hotlayout.support.channels
import com.hotlayout.support.isColor
import com.hotlayout.support.name
import com.hotlayout.theme.Corners
import com.hotlayout.theme.StyleResolver
import com.hotlayout.theme.TextShadowRules
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
 * The inner shadow and the shadow of a text against the shared table (`shadow-effects/shadow-effects-cases.json`): how a layer is
 * read, the geometry of an inner layer, the order of the layers of a text, and what is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ShadowEffectsCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/shadow-effects/shadow-effects-cases.json")
    private val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())

    private fun rows(block: String, name: String, count: Int): List<JsonObject> =
        table[block]?.get(name)?.array.orEmpty().mapNotNull { it.obj }.also { assertEquals("$block.$name", count, it.size) }

    @Test fun everyBlockIsRun() {
        assertEquals(setOf("css", "geometry", "document", "themeElevation"), table["insetCases"]?.obj?.keys?.toSet())
        assertEquals(setOf("css", "document"), table["textShadowCases"]?.obj?.keys?.toSet())
    }

    // ---- Inner layers

    /** The layers of a row as the reader reads them: the numbers as written, and the flag only for the word `true`. */
    @Test fun insetLayersAreRead() {
        for (row in rows("insetCases", "css", 8)) {
            val written = row["layers"]?.array.orEmpty()
            val read = resolver.shadow(JsonValue.Arr(written))
            assertNotNull(row.name(), read)
            assertEquals(row.name(), written.size, read!!.size)
            for ((layer, source) in read.zip(written)) {
                assertEquals(row.name(), source["inset"]?.bool == true, layer.inset)
                assertEquals(row.name(), source["offsetX"]?.number, layer.offsetX)
                assertEquals(row.name(), source["offsetY"]?.number, layer.offsetY)
                assertEquals(row.name(), source["blur"]?.number, layer.blur)
                assertEquals(row.name(), source["spread"]?.number, layer.spread)
            }
        }
    }

    @Test fun insetGeometry() {
        for (row in rows("insetCases", "geometry", 12)) {
            val box = row["box"]
            val layer = row["layer"]
            val radius = box?.get("radius")?.number ?: Double.NaN
            val clip = InsetShadowGeometry.clip(box?.get("width")?.number ?: Double.NaN, box?.get("height")?.number ?: Double.NaN, box?.get("borderWidth")?.number ?: Double.NaN, Corners.all(radius))
            val hole = InsetShadowGeometry.hole(clip, layer?.get("offsetX")?.number ?: Double.NaN, layer?.get("offsetY")?.number ?: Double.NaN, layer?.get("spread")?.number ?: Double.NaN)
            for ((got, key) in listOf(clip to "clip", hole to "hole")) {
                val want = row[key]
                val at = "${row.name()} $key"
                assertEquals("$at.x", want?.get("x")?.number ?: Double.NaN, got.x, 1e-9)
                assertEquals("$at.y", want?.get("y")?.number ?: Double.NaN, got.y, 1e-9)
                assertEquals("$at.width", want?.get("width")?.number ?: Double.NaN, got.width, 1e-9)
                assertEquals("$at.height", want?.get("height")?.number ?: Double.NaN, got.height, 1e-9)
                assertEquals("$at.radius", want?.get("radius")?.number ?: Double.NaN, got.corners.topStart, 1e-9)
                assertEquals("$at.radius", want?.get("radius")?.number ?: Double.NaN, got.corners.bottomEnd, 1e-9)
            }
        }
    }

    /** A reader keeps a layer whose `inset` is anything but `true` as an outer one, brings numbers to their bounds, and reads no list of more than four layers. */
    @Test fun insetDocuments() {
        for (row in rows("insetCases", "document", 7)) {
            val layers = row["shadow"]?.array.orEmpty()
            val read = resolver.shadow(row["shadow"])
            if (layers.size > 4) {
                assertNull(row.name(), read)
                continue
            }
            assertEquals(row.name(), layers.size, read?.size)
            assertEquals(row.name(), layers.map { it["inset"]?.bool == true }, read?.map { it.inset })
            for (layer in read.orEmpty()) assertTrue(row.name(), layer.offsetX in -64.0..64.0 && layer.offsetY in -64.0..64.0 && layer.blur in 0.0..128.0)
        }
    }

    /** The theme has no inner shadow: a layer of an elevation token is an outer one whatever it says. */
    @Test fun themeElevationHasNoInnerLayer() {
        for (row in rows("insetCases", "themeElevation", 2)) {
            val set = HotlayoutThemeSet.read(
                """{"themeContractVersion":"4.1.0","defaultThemeId":"light","themes":{"light":{"brightness":"light","elevation":{"probe":[${ConfigChangeState.encode(row["layer"]!!)}]}}}}""",
            )
            assertEquals(row.name(), listOf(false), set?.theme("light")?.elevation?.get("probe")?.map { it.inset })
        }
    }

    private fun box(shadow: String, extra: String = ""): HotlayoutDocument = Screens.document(
        "a" to """{"id":"b","type":"Container","props":{"direction":"column"},"style":{"width":200,"height":80,"background":{"kind":"color","color":{"value":"#ffffff"}},"shadow":[$shadow]$extra},"children":[]}""",
        rootStyle = """{"background":{"kind":"color","color":{"value":"#000000"}},"padding":10}""",
    )

    private fun layer(x: Int, y: Int, color: String, inset: Boolean, blur: Int = 0, spread: Int = 0) =
        """{"offsetX":$x,"offsetY":$y,"blur":$blur,"spread":$spread,"color":{"value":"$color"}${if (inset) ""","inset":true""" else ""}}"""

    private fun capture(document: HotlayoutDocument) = ScreenCapture(compose).show(document)

    /** An inner layer lies INSIDE the box, over the background: a band at the top edge and nothing outside. */
    @Test fun innerLayerIsPaintedInsideTheBox() {
        val image = capture(box(layer(0, 3, "#ff0000", true)))
        assertTrue("the band under the top edge", image.isColor(110, 10 + 1, 0xff0000))
        assertTrue(image.isColor(110, 10 + 2, 0xff0000))
        assertTrue("the middle is the background", image.isColor(110, 10 + 40, 0xffffff))
        assertTrue("the bottom edge is not shaded by a layer that moves down", image.isColor(110, 10 + 78, 0xffffff))
        assertTrue("nothing outside the box, above it", image.isColor(110, 5, 0x000000))
        assertTrue("nothing outside the box, below it", image.isColor(110, 10 + 80 + 5, 0x000000))
    }

    /** An outer layer is drawn behind the box as before, and a mixed list keeps both. */
    @Test fun outerLayerStaysOutside() {
        val image = capture(box(layer(0, 6, "#00ff00", false) + "," + layer(0, 3, "#ff0000", true)))
        assertTrue("the drop under the box", image.isColor(110, 10 + 80 + 3, 0x00ff00))
        assertTrue("the inner band", image.isColor(110, 10 + 1, 0xff0000))
        assertTrue(image.isColor(110, 10 + 40, 0xffffff))
    }

    @Test fun firstInnerLayerIsOnTop() {
        val image = capture(box(layer(0, 4, "#ff0000", true) + "," + layer(0, 4, "#0000ff", true)))
        assertTrue(image.isColor(110, 10 + 1, 0xff0000))
    }

    /** The layer is cut to the box inside the border: the border stays free of it. */
    @Test fun innerLayerStopsAtTheBorder() {
        val image = capture(box(layer(0, 0, "#ff0000", true, spread = 6), ""","border":{"width":4,"color":{"value":"#00ff00"}}"""))
        assertTrue("the border is over the layer", image.isColor(110, 10 + 1, 0x00ff00))
        assertTrue("the thick edge inside the border", image.isColor(110, 10 + 6, 0xff0000))
        assertTrue(image.isColor(110, 10 + 40, 0xffffff))
    }

    /** A button takes the inner layer too: it is drawn by its own surface. */
    @Test fun innerLayerOnAButton() {
        val node = """{"id":"b","type":"Button","props":{"label":{"kind":"literal","value":"Go"},"action":{"type":"none"},"fullWidth":false},"style":{"background":{"kind":"color","color":{"value":"#ffffff"}},"color":{"value":"#ffffff"},"radius":0,"shadow":[${layer(0, 3, "#ff0000", true)}]}}"""
        val image = capture(Screens.document("a" to node))
        assertTrue("the band under the top edge of the button", image.isColor(3, 1, 0xff0000))
    }

    // ---- The shadow of a text

    @Test fun textShadowOrder() {
        for (row in rows("textShadowCases", "css", 5)) {
            val layers = resolver.textShadow(row["layers"])
            assertEquals(row.name(), row["layers"]?.array?.size, layers?.size)
            val order = TextShadowRules.bottomFirst(layers.orEmpty())
            val expected = row["bottomFirst"]?.array.orEmpty().mapNotNull { it.number?.toInt() }
            assertEquals(row.name(), expected.map { layers!![it] }, order)
        }
    }

    /** Only `Text` reads the field; one or two layers; numbers are brought to their bounds; an unknown extra field is not read. */
    @Test fun textShadowDocuments() {
        for (row in rows("textShadowCases", "document", 10)) {
            val type = row["type"]?.string ?: ""
            val style = JsonObject.of("textShadow" to row["textShadow"])
            val layers = TextShadowRules.layers(type, style, resolver)
            val count = row["textShadow"]?.array?.size ?: 0
            if (type != "Text" || count == 0 || count > 2) {
                assertNull(row.name(), layers)
            } else {
                assertEquals(row.name(), count, layers?.size)
                for (layer in layers.orEmpty()) assertTrue(row.name(), layer.blur in 0.0..128.0 && layer.offsetY in -64.0..64.0)
            }
        }
    }

    private fun text(shadow: String?, gradient: Boolean = false): HotlayoutDocument {
        val grad = if (gradient) ""","textGradient":{"angle":180,"stops":[{"color":{"value":"#ffffff"},"position":0},{"color":{"value":"#ffffff"},"position":1}]}""" else ""
        val shadowField = if (shadow != null) ""","textShadow":[$shadow]""" else ""
        val node = """{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"HI"}},"style":{"color":{"value":"#ffffff"},"typography":{"token":"displayL","fontSize":60}$grad$shadowField}}"""
        return Screens.document("a" to node, rootStyle = """{"background":{"kind":"color","color":{"value":"#000000"}},"padding":30}""")
    }

    private fun textLayer(x: Int, y: Int, color: String) = """{"offsetX":$x,"offsetY":$y,"blur":0,"color":{"value":"$color"}}"""

    private fun count(image: android.graphics.Bitmap, test: (Int, Int, Int) -> Boolean): Int {
        var found = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val (r, g, b) = image.channels(x, y)
            if (test(r, g, b)) found++
        }
        return found
    }

    private fun isRed(r: Int, g: Int, b: Int) = r > 200 && g < 60 && b < 60

    /** The shadow is a copy of the text in the colour of the layer: it shows past the glyphs. */
    @Test fun textShadowShowsBesideTheGlyphs() {
        val shadowed = capture(text(textLayer(0, 12, "#ff0000")))
        assertTrue("red pixels of the shadow: ${count(shadowed, ::isRed)}", count(shadowed, ::isRed) > 40)
    }

    @Test fun textWithoutAShadowHasNoRedPixels() {
        assertEquals(0, count(capture(text(null)), ::isRed))
    }

    /** The shadow takes no room: the text is as tall as its line, however far the layer is moved. */
    @Test fun textShadowDoesNotChangeTheBoxOfTheText() {
        capture(text(textLayer(40, 40, "#ff0000")))
        val line = HotlayoutThemeSet.builtIn.theme().typography["displayL"]!!.lineHeight * 60
        assertEquals(line, compose.onNodeWithText("HI").fetchSemanticsNode().size.height.toDouble(), 1.5)
    }

    /** An opaque gradient on the glyphs hides the shadow under it as a solid colour would. */
    @Test fun textShadowDoesNotShineThroughAGradientOnTheGlyphs() {
        val hidden = capture(text(textLayer(0, 0, "#ff0000"), gradient = true))
        assertEquals("a shadow at the glyphs themselves is covered", 0, count(hidden, ::isRed))
    }

    @Test fun textShadowUnderAGradientShowsBeside() {
        val moved = capture(text(textLayer(0, 12, "#ff0000"), gradient = true))
        assertTrue(count(moved, ::isRed) > 40)
    }

    @Test fun firstTextLayerIsOnTop() {
        val both = capture(text(textLayer(0, 12, "#ff0000") + "," + textLayer(0, 12, "#0000ff")))
        assertTrue(count(both, ::isRed) > 40)
        assertEquals(0, count(both) { r, g, b -> b > 200 && r < 60 && g < 60 })
    }
}
