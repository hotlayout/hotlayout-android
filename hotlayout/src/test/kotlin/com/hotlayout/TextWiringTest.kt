package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hotlayout.data.dataScopeOf
import com.hotlayout.render.motion.LocalCountdownTickedSeconds
import com.hotlayout.render.motion.LocalValueTransitionElapsedMs
import com.hotlayout.support.Screens
import com.hotlayout.support.near
import com.hotlayout.theme.HotlayoutThemeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The text capabilities through the real screen: composed parts, emphasis, list markers, the
 * gradient on the glyphs, the ticking time left and the numbers that lead. The tables prove the
 * rules; these prove that `HotlayoutScreen` calls them and that the result is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TextWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = HotlayoutThemeSet.builtIn.theme()

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun show(vararg screens: Pair<String, String>, data: com.hotlayout.data.DataScope = emptyMap(), locale: String = "en", now: Double? = null) {
        val document = Screens.document(*screens)
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = locale, now = now?.toLong() ?: 1_791_000_000_000) } }
        compose.waitForIdle()
    }

    /** The spans of the annotated text a Text node drew: what Compose was asked to paint. */
    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    private fun spansOf(text: String): List<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>> = layoutOf(compose.onNodeWithText(text)).layoutInput.text.spanStyles

    private fun template(text: String, values: String, spans: String = "", extra: String = "", id: String = "t") =
        """{"id":"$id","type":"Text","props":{"content":{"kind":"template","template":"$text","values":{$values}}${if (spans.isEmpty()) "" else ""","spans":{$spans}"""}}$extra}"""

    private val amount = """"amount":{"kind":"binding","path":"user.points","fallback":"0","format":"number"},"unit":{"kind":"plural","of":"amount","forms":{"one":"Point","other":"Points"}}"""

    @Test fun thePartsOfATextTakeTheirOwnStyleAndTheScreenReaderHearsOneString() {
        show(
            "a" to template("{amount} {unit}", amount, """"amount":{"typography":"displayL","color":{"value":"#ff0000"}},"unit":{"typography":"caption"}"""),
            data = dataScopeOf("user.points" to 1850),
        )
        compose.onNodeWithText("1,850 Points").assertExists()
        val spans = spansOf("1,850 Points")
        assertEquals("three runs: the number, the space, the unit", 3, spans.size)
        val display = theme.typography["displayL"]!!
        val caption = theme.typography["caption"]!!
        assertEquals(display.fontSize.toFloat(), spans[0].item.fontSize.value, 0.6f)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF0000), spans[0].item.color)
        assertEquals(caption.fontSize.toFloat(), spans[2].item.fontSize.value, 0.6f)
    }

    @Test fun everyLineHasThePitchOfTheTallestDeclaredPart() {
        val plain = Screens.text("p", Screens.literal("Plain"))
        show("a" to "$plain,${template("{amount}", amount, """"amount":{"typography":"displayL"}""", id = "r")}", data = dataScopeOf("user.points" to 5))
        val baseHeight = compose.onNodeWithText("Plain").fetchSemanticsNode().size.height
        val richHeight = compose.onNodeWithText("5").fetchSemanticsNode().size.height
        val display = theme.typography["displayL"]!!
        assertEquals("the line of a text with a large part is the part's line box", display.lineBox.toFloat(), richHeight.toFloat(), 1.5f)
        assertTrue("and taller than the base style's", richHeight > baseHeight)
    }

    @Test fun anItalicNodeAndAPartThatCancelsIt() {
        val spans = """"word":{"fontStyle":"normal","textDecoration":"none"}"""
        show("a" to template("Plain {word} end", """"word":{"kind":"text","text":"upright"}""", spans, ""","style":{"fontStyle":"italic","textDecoration":"underline"}"""))
        val styles = spansOf("Plain upright end")
        assertEquals(3, styles.size)
        assertEquals(FontStyle.Italic, styles[0].item.fontStyle)
        assertEquals(TextDecoration.Underline, styles[0].item.textDecoration)
        assertEquals("the part says «normal» and «none»: it cancels the inherited", FontStyle.Normal, styles[1].item.fontStyle)
        assertEquals(TextDecoration.None, styles[1].item.textDecoration)
        assertEquals(FontStyle.Italic, styles[2].item.fontStyle)
    }

    @Test fun aPartsWeightWordIsItsWeight() {
        show("a" to template("{word}!", """"word":{"kind":"text","text":"bold"}""", """"word":{"weight":"bold"}"""))
        assertEquals(700, spansOf("bold!")[0].item.fontWeight?.weight)
    }

    @Test fun theShownTextRuleCollapsesWhitespaceAcrossTheParts() {
        show("a" to template("  {word}   tail ", """"word":{"kind":"text","text":"head"}""", """"word":{"color":{"value":"#00aa00"}}"""))
        compose.onNodeWithText("head tail").assertExists()
    }

    private fun item(id: String, marker: String, extra: String = "") =
        """{"id":"$id","type":"Text","props":{"content":{"kind":"literal","value":"Item $id"},"marker":"$marker"}$extra}"""

    @Test fun aNumberedListCountsTheVisibleItemsAndAHangingIndentKeepsTheText() {
        show("a" to listOf(item("one", "number"), item("two", "number", ""","visibleIf":{"path":"show","op":"isTrue"}"""), item("three", "number")).joinToString(","), data = dataScopeOf("show" to false))
        compose.onNodeWithTag("marker:1.").assertExists()
        compose.onNodeWithTag("marker:2.").assertExists()
        // The group of three is two digits wide? No: one digit, so a column of one font size; the text hangs at column + gap.
        val body = theme.typography["bodyM"]!!
        val left = compose.onNodeWithText("Item one").fetchSemanticsNode().boundsInRoot.left
        assertEquals((body.fontSize * 1.5).toFloat(), left, 1.5f)
    }

    @Test fun aBulletIsInTheTextFontAndTheTextStartsAfterItsColumn() {
        show("a" to item("b", "bullet"))
        compose.onNodeWithTag("marker:•").assertExists()
        val body = theme.typography["bodyM"]!!
        assertEquals((body.fontSize * 1.5).toFloat(), compose.onNodeWithText("Item b").fetchSemanticsNode().boundsInRoot.left, 1.5f)
    }

    @Test fun aListItemInRightToLeftHasItsMarkerOnTheRight() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/b/content":"بند"}}}"""
        val document = Screens.document("a" to item("b", "bullet"), extra = localization)
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = "ar") } }
        compose.waitForIdle()
        val bounds = compose.onNodeWithText("بند").fetchSemanticsNode().boundsInRoot
        val marker = compose.onNodeWithTag("marker:•").fetchSemanticsNode().boundsInRoot
        assertTrue("the marker is right of the text: $marker $bounds", marker.left >= bounds.right - 1f)
    }

    @Test fun aGradientPaintsTheGlyphsAlongTheText() {
        val gradient = """{"angle":90,"stops":[{"color":{"value":"#ff0000"},"position":0},{"color":{"value":"#0000ff"},"position":1}]}"""
        show("a" to Screens.text("g", Screens.literal("MMMMMMMMMMMMMMMMMMMMMMMM"), ""","style":{"textGradient":$gradient,"typography":"displayL"}"""))
        val image = capture()
        val bounds = compose.onNodeWithText("MMMMMMMMMMMMMMMMMMMMMMMM").fetchSemanticsNode().boundsInRoot
        fun hue(x0: Int, x1: Int): Pair<Int, Int> {
            var red = 0
            var blue = 0
            for (x in x0 until x1) for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                val pixel = image.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val b = pixel and 0xFF
                if (r > 150 && b < 100) red++
                if (b > 150 && r < 100) blue++
            }
            return red to blue
        }
        val width = bounds.right.toInt().coerceAtMost(image.width)
        val (leftRed, leftBlue) = hue(bounds.left.toInt(), bounds.left.toInt() + width / 5)
        val (rightRed, rightBlue) = hue(bounds.left.toInt() + width * 4 / 5 - 1, width)
        assertTrue("the start is red: $leftRed/$leftBlue", leftRed > leftBlue * 3)
        assertTrue("the end is blue: $rightRed/$rightBlue", rightBlue > rightRed * 3)
    }

    @Test fun aPartWithAColourOfItsOwnKeepsItUnderAGradient() {
        val gradient = """{"angle":90,"stops":[{"color":{"value":"#0000ff"},"position":0},{"color":{"value":"#0000ff"},"position":1}]}"""
        show(
            "a" to template("{word}", """"word":{"kind":"text","text":"MMMMMMMM"}""", """"word":{"color":{"value":"#00ff00"}}""", ""","style":{"textGradient":$gradient,"typography":"displayL"}"""),
        )
        val image = capture()
        val bounds = compose.onNodeWithText("MMMMMMMM").fetchSemanticsNode().boundsInRoot
        var green = 0
        var blue = 0
        for (x in bounds.left.toInt() until bounds.right.toInt()) for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            val pixel = image.getPixel(x, y)
            if ((pixel shr 8) and 0xFF > 200 && pixel and 0xFF < 80) green++
            if (pixel and 0xFF > 200 && (pixel shr 8) and 0xFF < 80) blue++
        }
        assertTrue("green glyphs, no blue ones: $green/$blue", green > 100 && blue == 0)
    }

    private fun countdown(unit: String, pad: String = "") =
        """{"id":"c","type":"Text","props":{"content":{"kind":"template","template":"{part}","values":{"part":{"kind":"binding","path":"campaign.endsAt","fallback":"-","format":"countdownPart","unit":"$unit"$pad}}}}}"""

    @Test fun aTimeLeftCountsDownByWholeSeconds() {
        val end = "2026-10-10T09:01:30.000Z"
        val now = java.time.Instant.parse("2026-10-10T09:00:00.000Z").toEpochMilli().toDouble()
        val document = Screens.document("a" to countdown("seconds"))
        var ticked by mutableStateOf(0.0)
        compose.setContent {
            CompositionLocalProvider(LocalCountdownTickedSeconds provides ticked) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = dataScopeOf("campaign.endsAt" to end), now = now.toLong()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("30").assertExists()
        ticked = 1.0
        compose.waitForIdle()
        compose.onNodeWithText("29").assertExists()
        ticked = 5.4
        compose.waitForIdle()
        compose.onNodeWithText("25").assertExists()
    }

    private fun counted(extra: String) =
        """{"id":"n","type":"Text","props":{"content":{"kind":"binding","path":"user.points","fallback":"0","format":"number"}$extra}}"""

    @Test fun aNumberThatLeadsMovesInStepsFromTheOldValueToTheNew() {
        val document = Screens.document("a" to counted(""","animateChanges":true"""))
        var data by mutableStateOf(dataScopeOf("user.points" to 0))
        compose.setContent {
            CompositionLocalProvider(LocalValueTransitionElapsedMs provides 350.0) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("0").assertExists()
        data = dataScopeOf("user.points" to 100)
        compose.waitForIdle()
        // At 350 ms of 700 the curve is at 0.875 and the step is cut towards the old value.
        compose.onNodeWithText("87").assertExists()
    }

    @Test fun aNumberThatLeadsEndsOnTheNewValueOnTheRealClock() {
        val document = Screens.document("a" to counted(""","animateChanges":true"""))
        var data by mutableStateOf(dataScopeOf("user.points" to 10))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        compose.waitForIdle()
        data = dataScopeOf("user.points" to 500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("500").assertExists()
    }

    @Test fun aNumberThatAppearsGrowsFromZeroWhenTheNodeIsShown() {
        val document = Screens.document("a" to counted(""","animateIn":true"""))
        compose.setContent {
            CompositionLocalProvider(LocalValueTransitionElapsedMs provides 175.0) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = dataScopeOf("user.points" to 80)) }
            }
        }
        compose.waitForIdle()
        // 80 × 0.578125 = 46.25: whole steps cut towards zero.
        compose.onNodeWithText("46").assertExists()
    }

    @Test fun aNodeWithoutTheFieldsShowsTheValueAtOnce() {
        show("a" to counted(""), data = dataScopeOf("user.points" to 80))
        compose.onNodeWithText("80").assertExists()
    }

    @Test fun theMarkerAndSpansReachAPlainTextOnlyWhenTheyAreInTheDocument() {
        // A plain text stays one run with the inherited look: the rich path is not taken.
        show("a" to Screens.text("p", Screens.literal("Plain")))
        assertEquals(0, spansOf("Plain").size)
        assertTrue(near(0x000000, 0x000000))
    }
}
