package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hotlayout.render.widgets.ImageRules
import com.hotlayout.render.widgets.ImageStore
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import com.hotlayout.support.near
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The picture groups of `media-chrome-cases.json`: the proportion, the box, where the file lies in
 * it, the radius that clips it, and the circle. Every row runs through the rule functions; the box
 * rows run once more through the real node, and the pixels are read from the real screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ImageCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/media-chrome/media-chrome-cases.json")
    private fun JsonValue?.n(): Double = this?.number ?: 0.0
    private operator fun JsonValue?.get(key: String): JsonValue? = this?.get(key)

    @Before fun freshCache() = ImageStore.reset()

    @Test fun ratioCases() {
        val rows = Contract.rows(table, "ratioCases")
        assertEquals(20, rows.size)
        for (row in rows) {
            assertEquals(row.name(), row["expect"]["width"].n() / row["expect"]["height"].n(), ImageRules.ratio(row["aspectRatio"]), 1e-9)
        }
    }

    @Test fun imageBoxCases() {
        val rows = Contract.rows(table, "imageBoxCases")
        assertEquals(18, rows.size)
        for (row in rows) {
            val style = row["style"]?.obj
            val box = ImageRules.box(row["aspectRatio"], row["width"].n(), style?.get("height")?.number, style?.get("minHeight")?.number, style?.get("maxHeight")?.number)
            assertEquals(row.name(), row["expect"]["width"].n(), box.width, 0.001)
            assertEquals(row.name(), row["expect"]["height"].n(), box.height, 0.001)
            assertEquals(row.name(), row["expect"]["byRatio"]?.bool, box.byRatio)
        }
    }

    @Test fun paintCases() {
        val rows = Contract.rows(table, "paintCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            val rect = ImageRules.paintRect(row["box"]["width"].n(), row["box"]["height"].n(), row["natural"].n(), row["fit"]?.string)
            val expect = row["expect"]
            assertEquals(row.name(), listOf(expect["x"].n(), expect["y"].n(), expect["width"].n(), expect["height"].n()), rect.toList())
        }
    }

    @Test fun radiusCases() {
        val rows = Contract.rows(table, "radiusCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            val (w, h, r) = Triple(row["box"]["width"].n(), row["box"]["height"].n(), row["radius"].n())
            assertEquals(row.name(), row["expect"]["radius"].n(), ImageRules.effectiveRadius(w, h, r), 0.0)
            assertEquals(row.name(), row["expect"]["circle"]?.bool, ImageRules.isCircle(w, h, r))
        }
    }

    @Test fun circleCases() {
        val pill = HotlayoutThemeSet.builtIn.theme().radius["pill"]
        assertEquals(72.0, pill)
        for (row in Contract.rows(table, "circleCases")) {
            val side = row["side"].n()
            assertEquals(row.name(), side, row["expect"]["width"].n(), 0.0)
            assertEquals(row.name(), side, row["expect"]["height"].n(), 0.0)
            val radius = if (row["expect"]["radius"]?.string == "pill") pill!! else row["expect"]["radius"].n()
            assertTrue(row.name(), ImageRules.isCircle(side, side, radius))
        }
    }

    // ---- the real screen -------------------------------------------------------------------

    /** A 40 × 20 file: left half red, right half blue, as a `data:` address. */
    private val picture: String by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFFFF0000.toInt())
        canvas.drawRect(20f, 0f, 40f, 20f, android.graphics.Paint().apply { color = 0xFF0000FF.toInt() })
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private val dataPolicy = ImagePolicy(resolve = { if (it.startsWith("data:image/")) it else ImagePolicy.httpsOnly(it) })

    private fun image(props: String = "", style: String = "", alt: String? = "pic", address: String? = null): String {
        val source = address?.let { """,\"source\":{\"kind\":\"url\",\"url\":\"$it\"}""".replace("\\\"", "\"") } ?: ""
        val altField = alt?.let { ""","alt":"$it"""" } ?: ""
        return """{"id":"img","type":"Image","props":{"aspectRatio":"16:9"$altField$source${if (props.isEmpty()) "" else ",$props"}}${if (style.isEmpty()) "" else ""","style":{$style}"""}}"""
    }

    private var shown by mutableStateOf<Triple<Int, HotlayoutDocument, ImagePolicy>?>(null)
    private var content = false

    /** Shows a document; a test may show several in turn (a new one replaces the screen). */
    private fun show(document: HotlayoutDocument, images: ImagePolicy = ImagePolicy()) {
        shown = Triple((shown?.first ?: 0) + 1, document, images)
        if (!content) {
            content = true
            compose.setContent {
                shown?.let { (version, doc, policy) -> key(version) { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(doc, images = policy) } } }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds() = compose.onNode(hasContentDescription("pic")).fetchSemanticsNode().boundsInRoot

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int) = getPixel(x, y) and 0xFFFFFF

    /** Waits for the file (read on another thread) and returns the frame. */
    private fun loaded(): android.graphics.Bitmap {
        compose.waitUntil(10_000) { ImageStore.cached(address = picture) != null }
        compose.waitForIdle()
        return capture()
    }

    @Test fun theBoxRowsThroughTheRealNode() {
        for (row in Contract.rows(table, "imageBoxCases")) {
            val width = row["width"].n()
            // The test screen is 640 tall: a box taller than it is not measured here.
            if (width <= 0 || width > 360 || row["expect"]["height"].n() > 600) continue
            val style = row["style"]?.obj
            val named = style?.filter { it.first in setOf("height", "minHeight", "maxHeight") }.orEmpty()
                .joinToString(",") { "\"${it.first}\":${it.second.number}" }
            val ratio = row["aspectRatio"]?.let { value ->
                if (value.string != null) "\"${value.string}\"" else """{"width":${value["width"].n()},"height":${value["height"].n()}}"""
            }
            val node = """{"id":"img","type":"Image","props":{${ratio?.let { "\"aspectRatio\":$it," } ?: ""}"alt":"pic"},"style":{"width":$width${if (named.isEmpty()) "" else ",$named"}}}"""
            show(Screens.document("a" to node, rootStyle = "{}"))
            val box = bounds()
            assertEquals(row.name(), row["expect"]["width"].n(), box.width.toDouble(), 1.0)
            assertEquals(row.name(), row["expect"]["height"].n(), box.height.toDouble(), 1.0)
        }
    }

    @Test fun anAltStringNamesThePictureAndAnEmptyOneHidesIt() {
        show(Screens.document("a" to image()))
        compose.onNode(hasContentDescription("pic")).assertExists()
        show(Screens.document("a" to image(alt = "")))
        assertTrue(compose.onAllNodes(hasContentDescription("pic")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aBoundAltIsTheShownTextOfTheDocumentString() {
        val node = """{"id":"img","type":"Image","props":{"aspectRatio":"1:1","alt":{"kind":"literal","value":"Maya in a red coat"}},"style":{"width":50}}"""
        show(Screens.document("a" to node))
        compose.onNode(hasContentDescription("Maya in a red coat")).assertExists()
    }

    @Test fun aCircleIsClippedByTheNodeAndAPillOnALargeSquareIsNot() {
        fun disc(side: Int) = """{"id":"img","type":"Image","props":{"aspectRatio":"1:1","alt":"pic"},"style":{"width":$side,"height":$side,"radius":"pill","background":{"kind":"color","color":{"value":"#ff0000"}}}}"""
        show(Screens.document("a" to disc(56)))
        var frame = capture()
        assertEquals("the corner is cut", 0xffffff, frame.rgbAt(1, 1))
        assertTrue("the middle is painted", frame.rgbAt(28, 28) != 0xffffff)
        show(Screens.document("a" to disc(160)))
        frame = capture()
        assertEquals("the corner is cut by 72", 0xffffff, frame.rgbAt(4, 4))
        assertTrue("a rounded square, not a circle: (2, 65) is painted", frame.rgbAt(2, 65) != 0xffffff)
    }

    @Test fun theFileIsPlacedByFit() {
        val box = """"width":100,"height":100"""
        show(Screens.document("a" to image(style = box, address = picture), rootStyle = "{}"), dataPolicy)
        var frame = loaded()
        assertTrue("cover fills the box: the top edge is red on the left", near(frame.rgbAt(10, 2), 0xff0000, 40))
        assertTrue("cover: the right is blue", near(frame.rgbAt(90, 98), 0x0000ff, 40))
        show(Screens.document("a" to image(props = "\"fit\":\"contain\"", style = box, address = picture), rootStyle = "{}"), dataPolicy)
        frame = loaded()
        assertTrue("contain leaves a band above the picture", frame.rgbAt(50, 5) != 0xff0000 && frame.rgbAt(50, 5) != 0x0000ff)
        assertTrue("contain draws the file in the middle band", near(frame.rgbAt(10, 50), 0xff0000, 40))
    }

    @Test fun anAddressTheHostRefusesDrawsThePlaceholder() {
        // The default policy lets only `https:` through: a `data:` address is refused and nothing is loaded.
        show(Screens.document("a" to image(style = """"width":100,"height":100""", address = picture), rootStyle = "{}"))
        // Nothing is expected to load: give a request that should not exist time to show itself.
        try { compose.waitUntil(300) { ImageStore.cached(picture) != null } } catch (_: ComposeTimeoutException) {}
        compose.waitForIdle()
        assertTrue(ImageStore.cached(picture) == null)
        val frame = capture()
        assertFalse("the placeholder is not the file", near(frame.rgbAt(10, 50), 0xff0000, 60))
        assertTrue("the placeholder is drawn over the box", frame.rgbAt(50, 50) != 0xffffff)
    }

    @Test fun aColumnWithJustifyEndInAFillLayerStandsAtTheBottomOfTheStack() {
        val column = """{"id":"c","type":"Column","fill":true,"props":{"gap":"none","justify":"end"},"children":[${Screens.text("t1", Screens.literal("Bottom"))}]}"""
        val sizer = """{"id":"s","type":"Spacer","props":{}}"""
        val stack = """{"id":"stack","type":"Stack","props":{},"style":{"height":300},"children":[$sizer,$column]}"""
        show(Screens.document("a" to stack, rootStyle = "{}"))
        near(300f, compose.onNodeWithText("Bottom").fetchSemanticsNode().boundsInRoot.bottom, "the text rests on the bottom of the stack")
    }

    @Test fun aNestedStackInAFillLayerIsAsWideAsTheStackAndItsOverlaysDraw() {
        val dot = """{"id":"d","type":"Container","props":{},"style":{"width":96,"height":96,"background":{"kind":"color","color":{"value":"#ff0000"}}},"layer":{"alignHorizontal":"end","alignVertical":"start"}}"""
        val anchor = """{"id":"an","type":"Container","props":{},"style":{"height":300}}"""
        val inner = """{"id":"in","type":"Stack","props":{},"children":[$anchor,$dot]}"""
        val fill = """{"id":"f","type":"Container","props":{},"fill":true,"children":[$inner]}"""
        val sky = """{"id":"sky","type":"Container","props":{},"style":{"height":300}}"""
        show(Screens.document("a" to """{"id":"out","type":"Stack","props":{},"children":[$sky,$fill]}""", rootStyle = "{}"))
        val frame = capture()
        assertEquals("the dot stands at the end edge of the stack, not of a zero-width column", 0xff0000, frame.rgbAt(310, 40))
        assertEquals("nothing else is drawn", 0, frame.rgbAt(100, 40))
    }

    private fun near(expected: Float, actual: Float, message: String) = assertEquals(message, expected, actual, 1.5f)
}
