package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hotlayout.model.loadDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Alignment, weights, wrap, scrolling and pull on the REAL screen: the rules are proved by the tables, this proves they are called. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class AlignWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: String, content: String, extra: String = "") =
        """{"id":"$id","type":"Text","props":{"content":{"kind":"literal","value":"$content"}}$extra}"""

    /** A screen of one body whose root has [rootProps] and holds [children]. */
    private fun show(rootProps: String, vararg children: String, locale: String = "en", extra: String = "") {
        val localization = if (locale == "en") "" else ""","localization":{"defaultLocale":"en","locales":["en","$locale"],"strings":{"$locale":{"a/t1/content":"Narrow"}}}"""
        val document = loadDocument(
            """{"schemaVersion":"9.27.0","documentId":"t","name":"T","initialScreenId":"a"$localization,"screens":[{"screenId":"a","name":"a","root":{"id":"root","type":"Screen","props":{$rootProps},"children":[${children.joinToString(",")}]}}]}""",
        )
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = locale) } }
        compose.waitForIdle()
    }

    private fun bounds(content: String): Rect = compose.onNodeWithText(content).fetchSemanticsNode().boundsInRoot

    private fun near(expected: Float, actual: Float, message: String) = assertEquals(message, expected, actual, 1.5f)

    @Test fun aNamedHeightColumnDistributesItsFreeHeight() {
        val column = """{"id":"c","type":"Column","props":{"gap":"none","justify":"center"},"style":{"height":400},"children":[${text("t1", "A")},${text("t2", "B")}]}"""
        show("\"gap\":\"none\"", column, text("t3", "Below"))
        val a = bounds("A")
        val b = bounds("B")
        val content = b.bottom - a.top
        near((400 - content) / 2, a.top, "justify center puts half of the free height above")
        near(400f, bounds("Below").top, "the column is as tall as its named height")
    }

    @Test fun aColumnAsTallAsItsContentHasNoFreeSpace() {
        val column = """{"id":"c","type":"Column","props":{"gap":"none","justify":"end"},"children":[${text("t1", "A")},${text("t2", "B")}]}"""
        show("\"gap\":\"none\"", column)
        near(0f, bounds("A").top, "no source of height: justify end changes nothing")
    }

    @Test fun theScreenDistributesTheFreeHeightOfItsBody() {
        show("\"gap\":\"none\",\"justify\":\"end\"", text("t1", "Only"))
        near(640f, bounds("Only").bottom, "the only child stands at the bottom of the visible body")
    }

    @Test fun betweenOnTheScreenSpreadsTheChildren() {
        show("\"gap\":\"none\",\"justify\":\"between\"", text("t1", "First"), text("t2", "Last"))
        near(0f, bounds("First").top, "the first child at the top")
        near(640f, bounds("Last").bottom, "the last child at the bottom")
    }

    @Test fun naturalWidthChildrenStandWhereAlignPutsThem() {
        show("\"gap\":\"none\",\"align\":\"end\"", text("t1", "Narrow", ""","style":{"width":100}"""), text("t2", "Wide"))
        near(260f, bounds("Narrow").left, "a child of natural width at the end")
        near(0f, bounds("Wide").left, "a stretched child keeps the full width")
    }

    @Test fun alignEndMirrorsInRightToLeft() {
        show("\"gap\":\"none\",\"align\":\"end\"", text("t1", "Narrow", ""","style":{"width":100}"""), locale = "ar")
        assertTrue("the logical end is the left edge in right-to-left text: ${bounds("Narrow")}", bounds("Narrow").right <= 100.5f)
    }

    @Test fun aRowWithBetweenSpreadsItsChildren() {
        val row = """{"id":"r","type":"Row","props":{"gap":"none","justify":"between"},"children":[${text("t1", "L", ""","style":{"width":50}""")},${text("t2", "M", ""","style":{"width":50}""")},${text("t3", "R", ""","style":{"width":50}""")}]}"""
        show("\"gap\":\"none\"", row)
        near(0f, bounds("L").left, "first at the start")
        near(155f, bounds("M").left, "the middle one in the middle")
        near(310f, bounds("R").left, "last at the end")
    }

    @Test fun weightsShareTheWidthOfARow() {
        val row = """{"id":"r","type":"Row","props":{"gap":"none"},"children":[${text("t1", "One", ""","weight":1""")},${text("t2", "Three", ""","weight":3""")}]}"""
        show("\"gap\":\"none\"", row)
        near(90f, bounds("One").width, "one share of four")
        near(270f, bounds("Three").width, "three shares of four")
    }

    @Test fun unweightedChildrenShrinkInProportionWhenTheyDoNotFit() {
        val long = "word ".repeat(30).trim()
        val row = """{"id":"r","type":"Row","props":{"gap":"none"},"children":[${text("t1", long)},${text("t2", long + " x")}]}"""
        show("\"gap\":\"none\"", row)
        val first = bounds(long).width
        assertTrue("the row does not overflow: $first", first <= 180.5f && bounds(long + " x").right <= 360.5f)
    }

    @Test fun aWrappingRowStartsASecondLine() {
        val row = """{"id":"r","type":"Row","props":{"gap":"none","wrap":true},"children":[${text("t1", "A", ""","style":{"width":200}""")},${text("t2", "B", ""","style":{"width":200}""")}]}"""
        show("\"gap\":\"none\"", row)
        assertTrue("B wraps under A", bounds("B").top > bounds("A").bottom - 1)
        near(0f, bounds("B").left, "the second line starts at the start")
    }

    @Test fun aScrollingRowKeepsItsChildrenOnOneLine() {
        val row = """{"id":"r","type":"Row","props":{"gap":"none","scrollable":true},"children":[${text("t1", "A", ""","style":{"width":300}""")},${text("t2", "B", ""","style":{"width":300}""")}]}"""
        show("\"gap\":\"none\"", row)
        near(300f, bounds("B").left, "B stands after A on the same line, beyond the edge")
        near(bounds("A").top, bounds("B").top, "one line")
    }

    @Test fun pullDrawsAChildEarlierAndTheFollowersWithIt() {
        val column = """{"id":"c","type":"Column","props":{"gap":"none"},"children":[${text("t1", "A")},${text("t2", "B", ""","pull":8""")},${text("t3", "C")}]}"""
        show("\"gap\":\"none\"", column)
        near(bounds("A").bottom - 8, bounds("B").top, "B overlaps A by its pull")
        near(bounds("B").bottom, bounds("C").top, "C follows B")
    }

    @Test fun aRowChildWithPullStandsEarlierAndTheNextOneFollows() {
        val row = """{"id":"r","type":"Row","props":{"gap":"none"},"children":[${text("t1", "A", ""","style":{"width":100}""")},${text("t2", "B", ""","style":{"width":100},"pull":10""")},${text("t3", "C", ""","style":{"width":100}""")}]}"""
        show("\"gap\":\"none\"", row)
        near(90f, bounds("B").left, "pulled by ten")
        near(190f, bounds("C").left, "the follower moves with it")
    }

    @Test fun aRowContainerSwapsTheAxes() {
        val container = """{"id":"k","type":"Container","props":{"direction":"row","gap":"none","align":"end","justify":"end"},"style":{"height":100},"children":[${text("t1", "A", ""","style":{"width":50}""")}]}"""
        show("\"gap\":\"none\"", container)
        near(310f, bounds("A").left, "justify moves along the line")
        near(100f, bounds("A").bottom, "align moves across it")
    }
}
