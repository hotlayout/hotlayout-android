package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The carousel that tells its stop, on the real screen, through `stopRunCases`: the show on the stop the value
 * names, a person's swipe that writes the stop once it has settled, a held finger that writes nothing, the dots
 * of the document that write the same path and move the carousel, and a carousel without the field that writes
 * nothing. The layout is the real one (the table's offsets are named by stop here, not by pixel); the way the
 * move is animated is not read back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class CarouselStopWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table = Contract.json("fixtures/carousel/carousel-cases.json")
    private val layoutStops = table["stopRunLayout"]?.get("stops")?.array.orEmpty().mapNotNull { it.number }
    private val nodes = table["stopRunScreen"]?.array.orEmpty()

    private var generation by mutableIntStateOf(0)
    private var document by mutableStateOf<HotlayoutDocument?>(null)
    private var hostData by mutableStateOf<DataScope>(emptyMap())
    private val log = ArrayList<List<JsonValue>>()
    private var contentSet = false

    /** A finger the table left on the row: let go before the next row. */
    private var held = false

    private val hasHorizontalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    private fun text(value: JsonValue): String = when (value) {
        is JsonValue.Null -> "null"
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Num -> if (value.value == Math.rint(value.value)) value.value.toLong().toString() else value.value.toString()
        is JsonValue.Str -> "\"" + value.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { text(it) }
        is JsonValue.Obj -> value.fields.joinToString(",", "{", "}") { (key, item) -> "\"$key\":${text(item)}" }
    }

    private fun screen(field: Boolean): HotlayoutDocument {
        val children = nodes.map { node ->
            if (field || node["id"]?.string != "row") {
                text(node)
            } else {
                val carousel = node["props"]?.get("carousel")?.obj
                val plain = JsonValue.Obj(JsonObject((carousel ?: JsonObject()).filter { it.first != "stopPath" }))
                text(JsonValue.Obj(JsonObject((node.obj ?: JsonObject()).map { (key, item) ->
                    if (key == "props") key to JsonValue.Obj(JsonObject((item.obj ?: JsonObject()).map { (k, v) -> if (k == "carousel") k to plain else k to v })) else key to item
                })))
            }
        }
        return Screens.document("a" to children.joinToString(","))
    }

    private fun show(row: JsonObject) {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, if (row["reducedMotion"]?.bool == true) 0f else 1f)
        log.clear()
        hostData = Contract.scope(row["session"])
        document = screen(row["field"]?.bool != false)
        generation += 1
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                Box(Modifier.size(360.dp, 640.dp)) {
                    key(generation) {
                        document?.let { HotlayoutScreen(it, data = hostData, onWrite = { path, value -> log.add(listOf(JsonValue.Str("write"), JsonValue.Str(path), value)) }) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollRange() = compose.onNode(hasHorizontalScroll).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]

    /**
     * The stops of the real layout in pixels, in the order of the table's stops: the start of every card (one card
     * wide plus the gap of 8), the last one the end of the scroll. Cards that are scrolled out of the area have no
     * bounds to read, so the width of a card comes from the end of the scroll: three cards, two gaps and the peek of 16.
     */
    private fun stopsPx(): List<Float> {
        val area = compose.onNode(hasHorizontalScroll).fetchSemanticsNode().boundsInRoot.width
        val maxScroll = scrollRange().maxValue()
        val distance = (maxScroll + area - 16f) / 3f + 8f
        return listOf(0f, distance, maxScroll)
    }

    private fun offsetPx(): Float = scrollRange().value()

    private fun stopIndex(): Int = stopsPx().let { stops -> stops.indices.minByOrNull { Math.abs(stops[it] - offsetPx()) }!! }

    /** An offset of the table's pixels in the real ones, between the stops in proportion. */
    private fun mine(offset: Double): Float {
        val stops = stopsPx()
        val index = layoutStops.indexOfLast { it <= offset }.coerceIn(0, layoutStops.size - 2)
        val share = ((offset - layoutStops[index]) / (layoutStops[index + 1] - layoutStops[index])).toFloat()
        return stops[index] + (stops[index + 1] - stops[index]) * share
    }

    private fun shownDetails(): List<String> = nodes.filter { it["id"]?.string?.startsWith("details-") == true }.mapNotNull { node ->
        val content = node["props"]?.get("content")?.get("value")?.string ?: return@mapNotNull null
        node["id"]?.string.takeIf { compose.onAllNodesWithText(content).fetchSemanticsNodes().isNotEmpty() }
    }

    private val touchWords = setOf("hold", "release", "scrollend", "quiet")

    private fun isTouch(step: JsonValue): Boolean = step.string in touchWords || step.obj?.get("swipe") != null || step.obj?.get("scroll") != null

    private fun runSteps(steps: List<JsonValue>) {
        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            val tap = step.obj?.get("tap")?.string
            if (tap != null) {
                compose.onNode(hasContentDescription(label(tap)) or hasText(label(tap))).performSemanticsAction(SemanticsActions.OnClick)
                index += 1
            } else if (step.string == "wheel") {
                val to = steps[index + 1].obj?.get("scroll")?.number ?: 0.0
                val delta = mine(to) - offsetPx()
                compose.onNode(hasHorizontalScroll).performMouseInput { scroll(delta, ScrollWheel.Horizontal) }
                index += 2
            } else {
                val run = steps.drop(index).takeWhile(::isTouch)
                val start = layoutStops[stopIndex()]
                compose.onNode(hasHorizontalScroll).performTouchInput { touchRun(run, start) }
                index += run.size
            }
            compose.waitForIdle()
        }
    }

    private fun TouchInjectionScope.touchRun(run: List<JsonValue>, start: Double) {
        var at = start
        fun moveTo(offset: Double) {
            moveBy(Offset(-(mine(offset) - mine(at)), 0f), delayMillis = 300)
            at = offset
        }
        for (step in run) {
            val swipe = step.obj?.get("swipe")?.array?.mapNotNull { it.number }
            val scroll = step.obj?.get("scroll")?.number
            when {
                swipe != null -> {
                    down(center)
                    swipe.forEach(::moveTo)
                    advanceEventTime(250)
                    up()
                }
                step.string == "hold" -> { down(center); held = true }
                scroll != null -> moveTo(scroll)
                step.string == "release" -> { advanceEventTime(250); up(); held = false }
            }
        }
    }

    private fun label(dot: String): String = "Card ${dot.removePrefix("dot-").toInt() + 1}"

    @Test fun theRowsOfTheTable() {
        val rows = Contract.rows(table, "stopRunCases")
        assertEquals(14, rows.size)
        for (row in rows) {
            show(row)
            runSteps(row["steps"]?.array.orEmpty())
            val expectedStop = layoutStops.indexOf(row["offset"]?.number ?: 0.0)
            assertEquals("${row.name()}: the stop", expectedStop, stopIndex())
            assertEquals("${row.name()}: what was written", row["log"]?.array.orEmpty().map { it.array.orEmpty() }, log)
            assertEquals("${row.name()}: the content under the carousel", row["shown"]?.array.orEmpty().mapNotNull { it.string }, shownDetails())
            if (held) compose.onNode(hasHorizontalScroll).performTouchInput { up() }
            held = false
        }
    }
}
