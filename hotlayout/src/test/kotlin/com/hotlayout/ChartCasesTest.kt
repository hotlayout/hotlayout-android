package com.hotlayout

import com.hotlayout.render.widgets.ChartRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared chart table (`fixtures/chart/chart-cases.json`): how a record becomes a point, the scale, the highlighted
 * point, the bars, the line, the monotone curve and the label boxes, each group measured against the functions the view
 * draws with.
 */
class ChartCasesTest {
    private val table get() = Contract.json("fixtures/chart/chart-cases.json")
    private val tolerance = 1e-6

    private fun rows(group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows in the table", count, list.size)
        return list
    }

    private fun numbers(value: JsonValue?): List<Double> = value?.array.orEmpty().mapNotNull { it.number }

    private fun close(actual: Double, expected: Double?, message: String) {
        assertTrue("$message: no expected number", expected != null)
        assertEquals(message, expected!!, actual, tolerance)
    }

    @Test fun everyGroupIsRun() {
        assertEquals(
            setOf("pointCases", "highlightCases", "barCases", "lineCases", "smoothCases", "labelCases", "plotHeightCases"),
            Contract.groups(table) - "records",
        )
    }

    @Test fun pointCases() {
        for (row in rows("pointCases", 7)) {
            val collections = row["collections"]?.obj ?: JsonObject()
            val points = ChartRules.points(row["data"]) { collections[it]?.array }
            val expected = row["expect"]?.array.orEmpty().mapNotNull { it.obj }.map { ChartRules.Point(it["value"]?.number ?: -1.0, it["label"]?.string ?: "?") }
            assertEquals(row.name(), expected, points)
        }
    }

    @Test fun highlightCases() {
        for (row in rows("highlightCases", 6)) {
            val values = numbers(row["values"])
            val label = "$values ${row["highlight"]?.string}"
            assertEquals(label, row["expect"]?.number?.toInt(), ChartRules.highlightIndex(values, row["highlight"]?.string))
            val ratios = ChartRules.ratios(values)
            val expected = numbers(row["ratios"])
            assertEquals(label, expected.size, ratios.size)
            for ((got, want) in ratios.zip(expected)) close(got, want, label)
        }
    }

    @Test fun barCases() {
        for (row in rows("barCases", 5)) {
            val values = numbers(row["values"])
            val bars = ChartRules.bars(
                ChartRules.ratios(values, row["shown"]?.number ?: 1.0), row["width"]?.number ?: 0.0, row["height"]?.number ?: 0.0,
                row["gap"]?.number ?: 0.0, row["radius"]?.number ?: 0.0, ChartRules.highlightIndex(values, row["highlight"]?.string),
            )
            val expected = row["expect"]?.array.orEmpty().mapNotNull { it.obj }
            assertEquals(row.name(), expected.size, bars.size)
            for ((bar, want) in bars.zip(expected)) {
                close(bar.x, want["x"]?.number, row.name())
                close(bar.y, want["y"]?.number, row.name())
                close(bar.width, want["width"]?.number, row.name())
                close(bar.height, want["height"]?.number, row.name())
                close(bar.radius, want["radius"]?.number, row.name())
                assertEquals(row.name(), want["highlighted"]?.bool, bar.highlighted)
            }
        }
    }

    @Test fun lineCases() {
        for (row in rows("lineCases", 3)) {
            val values = numbers(row["values"])
            val line = ChartRules.line(
                ChartRules.ratios(values), row["width"]?.number ?: 0.0, row["height"]?.number ?: 0.0, row["lineWidth"]?.number ?: 0.0,
                ChartRules.highlightIndex(values, row["highlight"]?.string),
            )
            val expected = row["expect"]?.obj ?: JsonObject()
            val points = expected["points"]?.array.orEmpty().mapNotNull { it.obj }
            assertEquals(row.name(), points.size, line.points.size)
            for ((point, want) in line.points.zip(points)) {
                close(point.x, want["x"]?.number, row.name())
                close(point.y, want["y"]?.number, row.name())
            }
            close(line.inset, expected["inset"]?.number, row.name())
            close(line.baseline, expected["baseline"]?.number, row.name())
            val dot = expected["dot"]?.obj
            if (dot != null) {
                close(line.dot?.x ?: Double.NaN, dot["x"]?.number, row.name())
                close(line.dot?.y ?: Double.NaN, dot["y"]?.number, row.name())
                close(line.dot?.radius ?: Double.NaN, dot["radius"]?.number, row.name())
            } else {
                assertNull(row.name(), line.dot)
            }
        }
    }

    @Test fun smoothCases() {
        for (row in rows("smoothCases", 4)) {
            val points = row["points"]?.array.orEmpty().mapNotNull { it.obj }.map { ChartRules.XY(it["x"]?.number ?: 0.0, it["y"]?.number ?: 0.0) }
            val tangents = ChartRules.tangents(points)
            val expected = numbers(row["expectTangents"])
            assertEquals(row.name(), expected.size, tangents.size)
            for ((got, want) in tangents.zip(expected)) close(got, want, row.name())
            // The curve never leaves the values of its neighbours: between two points it stays between their heights.
            val segments = ChartRules.smoothSegments(points)
            assertEquals(row.name(), maxOf(0, points.size - 1), segments.size)
            for ((index, segment) in segments.withIndex()) {
                val from = points[index]
                val low = minOf(from.y, segment.to.y) - tolerance
                val high = maxOf(from.y, segment.to.y) + tolerance
                for (step in 0..20) {
                    val t = step / 20.0
                    val u = 1 - t
                    val y = u * u * u * from.y + 3 * u * u * t * segment.c1.y + 3 * u * t * t * segment.c2.y + t * t * t * segment.to.y
                    assertTrue("${row.name()}: segment $index at $t", y in low..high)
                }
            }
        }
    }

    @Test fun labelCases() {
        for (row in rows("labelCases", 3)) {
            val boxes = ChartRules.labelBoxes(numbers(row["centers"]), row["width"]?.number ?: 0.0)
            val expected = row["expect"]?.array.orEmpty().mapNotNull { it.obj }
            assertEquals(row.name(), expected.size, boxes.size)
            for ((box, want) in boxes.zip(expected)) {
                assertEquals(row.name(), want["index"]?.number?.toInt(), box.index)
                close(box.left, want["left"]?.number, row.name())
                close(box.width, want["width"]?.number, row.name())
            }
        }
    }

    @Test fun plotHeightCases() {
        for (row in rows("plotHeightCases", 3)) {
            val height = ChartRules.plotHeight(row["height"]?.number ?: 0.0, row["labels"]?.bool == true, row["labelLine"]?.number ?: 0.0, row["labelGap"]?.number ?: 0.0)
            close(height, row["expect"]?.number, "$row")
        }
    }

    @Test fun fieldReaders() {
        assertEquals("last", ChartRules.readHighlight(JsonValue.Str("last")))
        assertEquals("none", ChartRules.readHighlight(JsonValue.Str("middle")))
        assertEquals("none", ChartRules.readHighlight(null))
        assertTrue(ChartRules.readSmooth(JsonValue.Str("smooth")))
        assertTrue(!ChartRules.readSmooth(JsonValue.Str("straight")))
        assertEquals(2.0, ChartRules.readLineWidth(null), 0.0)
        assertEquals(1.0, ChartRules.readLineWidth(JsonValue.Num(0.0)), 0.0)
        assertEquals(8.0, ChartRules.readLineWidth(JsonValue.Num(99.0)), 0.0)
        assertEquals(2.0, ChartRules.readLineWidth(JsonValue.Str("3")), 0.0)
    }

    @Test fun fewerThanTwoPointsIsAnEmptyChartAndOver31AreCut() {
        val one = JsonValue.of(mapOf("kind" to "literal", "points" to listOf(mapOf("value" to 3))))
        assertEquals(emptyList<ChartRules.Point>(), ChartRules.points(one) { null })
        val many = JsonValue.of(mapOf("kind" to "literal", "points" to (0 until 40).map { mapOf("value" to it) }))
        assertEquals(31, ChartRules.points(many) { null }.size)
        assertEquals(emptyList<ChartRules.Point>(), ChartRules.points(JsonValue.of(mapOf("kind" to "table"))) { null })
        assertEquals(emptyList<ChartRules.Point>(), ChartRules.points(null) { null })
    }
}
