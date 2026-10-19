package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.layout.AlignWord
import com.hotlayout.render.layout.AlignedColumn
import com.hotlayout.render.layout.AlignedRow
import com.hotlayout.render.layout.FlowChild
import com.hotlayout.render.layout.FlowPull
import com.hotlayout.render.layout.JustifyWord
import com.hotlayout.render.layout.RowKind
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `pull` against `offset/offset-cases.json`: the arithmetic of a flow, which nodes read the field, and the real layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class PullCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/offset/offset-cases.json")
    private fun num(value: JsonValue?) = value?.number ?: 0.0

    @Test fun pullFlowCases() {
        val rows = Contract.rows(table, "pullCases")
        assertEquals(10, rows.size)
        for (row in rows) {
            val items = row["children"]?.array.orEmpty().mapNotNull { it.obj }
            val (starts, size) = FlowPull.flow(items.map { num(it["size"]) }, items.map { num(it["pull"]) }, num(row["gap"]))
            assertEquals(row.name(), row["starts"]?.array.orEmpty().map { num(it) }, starts)
            assertEquals(row.name(), num(row["size"]), size, 0.0)
        }
    }

    @Test fun pullReadCases() {
        val rows = Contract.rows(table, "pullReadCases")
        assertEquals(20, rows.size)
        for (row in rows) {
            val node = DocumentNode("n", row["nodeType"]?.string ?: "Text", fields = row["node"]?.obj ?: JsonObject())
            val read = FlowPull.read(node, row["parent"]?.string ?: "", row["parentProps"]?.obj ?: JsonObject())
            val expect = row["expect"]
            if (expect?.obj == null) {
                assertNull(row.name(), read)
            } else {
                assertEquals(row.name(), expect["by"], read?.first)
                assertEquals(row.name(), expect["axis"]?.string, if (read?.second == FlowPull.Axis.Row) "row" else "column")
            }
        }
    }

    private class Run(val axis: String, val sizes: List<Double>, val pulls: List<Double>, val gap: Double)

    private var current by mutableStateOf<Run?>(null)
    private val found = HashMap<Int, Pair<Double, Double>>()
    private var shown = false

    /** Each row of `pullCases` through the real layouts: children are boxes of the sizes, the container is as long as the table says. */
    @Test fun pulledFlowsAreLaidOutWhereTheTableSays() {
        for (row in Contract.rows(table, "pullCases")) {
            val items = row["children"]?.array.orEmpty().mapNotNull { it.obj }
            val starts = row["starts"]?.array.orEmpty().map { num(it) }
            for (axis in listOf("column", "row")) {
                found.clear()
                current = Run(axis, items.map { num(it["size"]) }, items.map { num(it["pull"]) }, num(row["gap"]))
                if (!shown) {
                    shown = true
                    compose.setContent { current?.let { Host(it) } }
                }
                compose.waitForIdle()
                for (index in items.indices) {
                    val at = found[index] ?: error("child $index not drawn")
                    assertEquals("${row.name()} [$axis, child $index]", starts[index], if (axis == "row") at.first else at.second, 0.51)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Host(run: Run) {
        androidx.compose.runtime.key(run) {
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                run.sizes.forEachIndexed { index, size ->
                    val box = if (run.axis == "row") Modifier.width(size.dp).height(20.dp) else Modifier.fillMaxWidth().height(size.dp)
                    Box(
                        box.then(FlowChild(pull = run.pulls[index], kind = RowKind.Named, stretch = true)).onGloballyPositioned {
                            val at = it.positionInParent()
                            found[index] = at.x.toDouble() to at.y.toDouble()
                        },
                    )
                }
            }
            if (run.axis == "row") {
                AlignedRow(run.gap, JustifyWord.Start, AlignWord.Start, null, false, Modifier.fillMaxWidth(), content)
            } else {
                AlignedColumn(run.gap, AlignWord.Start, JustifyWord.Start, null, null, false, Modifier.fillMaxWidth(), content)
            }
        }
    }
}
