package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.layout.AlignRules
import com.hotlayout.render.layout.AlignWord
import com.hotlayout.render.layout.AlignedBoxInput
import com.hotlayout.render.layout.AlignedChild
import com.hotlayout.render.layout.AlignedColumn
import com.hotlayout.render.layout.AlignedRow
import com.hotlayout.render.layout.FlowChild
import com.hotlayout.render.layout.JustifyWord
import com.hotlayout.render.layout.MainAxis
import com.hotlayout.render.layout.ScreenZonesInput
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared table of alignment (`align/align-cases.json`): which axis is the main one, what gives a
 * column a height, how far children move, where they stand in a box (the rules as functions and the
 * real layouts, in both directions of the script), the body of a screen, and the data of the artifact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class AlignCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/align/align-cases.json")
    private fun num(value: JsonValue?): Double = value?.number ?: 0.0

    @Test fun tableShape() {
        val groups = listOf("readCases", "heightSourceCases", "shiftCases", "boxCases", "screenCases", "ruleCases")
        assertEquals(139, groups.sumOf { Contract.rows(table, it).size })
    }

    @Test fun readCases() {
        for (row in Contract.rows(table, "readCases")) {
            val node = row["node"]?.obj ?: JsonObject()
            val read = AlignRules.read(node["type"]?.string ?: "", node["props"]?.obj ?: JsonObject())
            val expect = row["expect"]
            if (expect?.get("mainAxis")?.string == null) {
                assertNull(row.name(), read)
            } else {
                assertEquals(row.name(), expect["mainAxis"]?.string == "block", read?.mainAxis == MainAxis.Block)
                assertEquals(row.name(), expect["align"]?.string, read?.align?.word)
                assertEquals(row.name(), expect["justify"]?.string, read?.justify?.word)
            }
        }
    }

    @Test fun heightSourceCases() {
        for (row in Contract.rows(table, "heightSourceCases")) {
            val node = row["node"]?.obj ?: JsonObject()
            val type = node["type"]?.string ?: ""
            val props = node["props"]?.obj ?: JsonObject()
            val fields = JsonObject.of().let { if (node["fill"] != null) JsonObject.of("fill" to node["fill"]!!) else it }
            val own = row["own"]
            val parent = row["parent"]?.string
            val ownHeight = own?.get("height")?.bool == true
            val ownMin = own?.get("minHeight")?.bool == true
            val expect = row["expect"]
            val read = AlignRules.read(type, props)
            val isColumn = read != null && read.mainAxis == MainAxis.Block && type != "Screen"
            val source = if (isColumn) AlignRules.heightSource(fields, parent, ownHeight, ownMin) else null
            assertEquals(row.name(), expect?.get("source")?.string, source?.word)
            assertEquals(row.name(), expect?.get("space")?.bool, AlignRules.hasFreeSpace(type, props, fields, parent, ownHeight, ownMin))
        }
    }

    @Test fun shiftCases() {
        for (row in Contract.rows(table, "shiftCases")) {
            val shifts = AlignRules.shifts(num(row["free"]), num(row["count"]).toInt(), JustifyWord.of(row["justify"]?.string) ?: JustifyWord.Start)
            val expected = row["expect"]?.array.orEmpty().map { num(it) }
            assertEquals(row.name(), expected.size, shifts.size)
            for ((a, b) in shifts.zip(expected)) assertEquals(row.name(), b, a, 1e-9)
        }
    }

    // --- boxes -----------------------------------------------------------------------------

    private fun input(row: JsonObject) = AlignedBoxInput(
        axis = if (row["axis"]?.string == "block") MainAxis.Block else MainAxis.Inline,
        inline = num(row["inline"]), block = row["block"]?.number, minBlock = row["minBlock"]?.number, gap = num(row["gap"]),
        align = AlignWord.of(row["align"]?.string) ?: AlignWord.Start, justify = JustifyWord.of(row["justify"]?.string) ?: JustifyWord.Start,
        rightToLeft = row["rtl"]?.bool == true,
        children = row["children"]?.array.orEmpty().mapNotNull { it.obj }.map { AlignedChild(num(it["width"]), num(it["height"]), it["stretch"]?.bool == true) },
    )

    private fun expectedRects(row: JsonObject) = row["expect"]?.get("rects")?.array.orEmpty().mapNotNull { it.obj }.map { listOf(num(it["x"]), num(it["y"]), num(it["width"]), num(it["height"])) }

    @Test fun boxCasesAsRules() {
        val rows = Contract.rows(table, "boxCases")
        for (row in rows) {
            val box = AlignRules.alignedChildRects(input(row))
            assertEquals(row.name(), num(row["expect"]?.get("block")), box.block, 1e-9)
            assertEquals(row.name(), num(row["expect"]?.get("free")), box.free, 1e-9)
            val expected = expectedRects(row)
            assertEquals(row.name(), expected.size, box.rects.size)
            for ((rect, want) in box.rects.zip(expected)) {
                assertEquals(row.name(), want, listOf(rect.x, rect.y, rect.width, rect.height))
            }
        }
    }

    /**
     * The same rows through the real layouts: children are fixed-size boxes, the host is [AlignedColumn] or
     * [AlignedRow] (children of a row have a named width, which does not shrink), the script runs in the direction of the row. Positions are read back from the left edge of the host.
     */
    @Test fun boxCasesThroughTheLayouts() {
        for (row in Contract.rows(table, "boxCases")) {
            val boxes = measure(input(row))
            val expected = expectedRects(row)
            assertEquals(row.name(), expected.size, boxes.size)
            for ((index, pair) in boxes.zip(expected).withIndex()) {
                val (got, want) = pair
                for (k in 0..3) assertEquals("${row.name()} [child $index, ${listOf("x", "y", "width", "height")[k]}]", want[k], got[k], 0.51)
            }
        }
    }

    private var current by androidx.compose.runtime.mutableStateOf<AlignedBoxInput?>(null)
    private val found = HashMap<Int, List<Double>>()
    private var shown = false

    private fun measure(input: AlignedBoxInput): List<List<Double>> {
        found.clear()
        current = input
        if (!shown) {
            shown = true
            compose.setContent { current?.let { Host(it) } }
        }
        compose.waitForIdle()
        return input.children.indices.map { found[it] ?: error("child $it was not drawn") }
    }

    @androidx.compose.runtime.Composable
    private fun Host(input: AlignedBoxInput) {
        val direction = if (input.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
        androidx.compose.runtime.key(input) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.requiredWidth(input.inline.dp)) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        input.children.forEachIndexed { index, child ->
                            val size = if (child.stretch && input.axis == MainAxis.Block) Modifier.fillMaxWidth().height(child.height.dp) else Modifier.width(child.width.dp).height(child.height.dp)
                            Box(
                                size.then(FlowChild(stretch = child.stretch, kind = com.hotlayout.render.layout.RowKind.Named)).onGloballyPositioned {
                                    val at = it.positionInParent()
                                    found[index] = listOf(at.x.toDouble(), at.y.toDouble(), it.size.width.toDouble(), it.size.height.toDouble())
                                },
                            )
                        }
                    }
                    if (input.axis == MainAxis.Block) {
                        AlignedColumn(input.gap, input.align, input.justify, input.block, input.minBlock, false, Modifier.fillMaxWidth(), content)
                    } else {
                        AlignedRow(input.gap, input.justify, input.align, input.block, false, Modifier.fillMaxWidth(), content)
                    }
                }
            }
        }
    }

    // --- the screen --------------------------------------------------------------------------

    @Test fun screenCases() {
        for (row in Contract.rows(table, "screenCases")) {
            val heights = row["children"]?.array.orEmpty().map { num(it) }
            val gap = num(row["gap"])
            val content = if (heights.isEmpty()) null else heights.sum() + gap * (heights.size - 1)
            val padding = row["padding"]
            val bottom = row["bottom"]
            val zones = AlignRules.screenZones(
                ScreenZonesInput(
                    height = num(row["height"]), shrink = row["shrink"]?.bool == true, appBarHeight = row["appBarHeight"]?.number,
                    paddingTop = num(padding?.get("top")), paddingBottom = num(padding?.get("bottom")), gap = gap, bodyContentHeight = content,
                    pinnedHeight = bottom?.get("pinnedHeight")?.number, navBarHeight = bottom?.get("navBarHeight")?.number,
                ),
            )
            val free = AlignRules.screenBodyFreeSpace(zones)
            val shifts = AlignRules.shifts(free, heights.size, JustifyWord.of(row["justify"]?.string) ?: JustifyWord.Start)
            var cursor = zones.contentTop
            val tops = heights.mapIndexed { index, height -> (cursor + shifts[index]).also { cursor += height + gap } }
            val expect = row["expect"]
            assertEquals(row.name(), num(expect?.get("body")?.get("top")), zones.bodyTop, 1e-9)
            assertEquals(row.name(), num(expect?.get("body")?.get("height")), zones.bodyHeight, 1e-9)
            assertEquals(row.name(), num(expect?.get("free")), free, 1e-9)
            assertEquals(row.name(), expect?.get("scrolls")?.bool, zones.scrolls)
            val expectedTops = expect?.get("tops")?.array.orEmpty().map { num(it) }
            assertEquals(row.name(), expectedTops.size, tops.size)
            for ((a, b) in tops.zip(expectedTops)) assertEquals(row.name(), b, a, 1e-9)
        }
    }

    // --- the data of the artifact ------------------------------------------------------------

    @Test fun ruleCases() {
        val alignment = Contract.json("screen-schema.json")["x-style"]?.get("layout")?.get("alignment")
        for (row in Contract.rows(table, "ruleCases")) {
            var value = alignment
            for (key in (row["rule"]?.string ?: "").split(".")) value = value?.get(key)
            assertEquals(row.name(), row["expect"], value)
        }
    }

    @Test fun wordsMirrorTheArtifact() {
        val layout = Contract.json("screen-schema.json")["x-style"]?.get("layout")
        val alignment = layout?.get("alignment")
        assertEquals(JustifyWord.entries.map { it.word }, alignment?.get("main")?.get("values")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(com.hotlayout.render.layout.HeightSource.entries.map { it.word }, alignment?.get("main")?.get("column")?.get("heightSources")?.array.orEmpty().mapNotNull { it.string })
        assertEquals("start", alignment?.get("main")?.get("withoutProp")?.string)
        assertEquals("start", alignment?.get("cross")?.get("block")?.get("withoutProp")?.string)
        assertEquals("center", alignment?.get("cross")?.get("inline")?.get("withoutProp")?.string)
        assertEquals("start", layout?.get("column")?.get("naturalWidthAlign")?.get("withoutProp")?.string)
        assertTrue(alignment?.get("cross")?.get("block")?.get("rule")?.string == "column.naturalWidthAlign")
    }
}
