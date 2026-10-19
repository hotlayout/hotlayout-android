package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.ProgressRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.theme.GradientValue
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.StyleResolver
import com.hotlayout.theme.TextStyleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared progress table (`fixtures/progress/progress-cases.json`): the ratio, the ring box, the
 * arc, the bar, the paint, the steps, the label of the ring, round ends, the steps during a
 * transition and the glow, each group measured against the functions the views draw with.
 */
class ProgressCasesTest {
    private val table get() = Contract.json("fixtures/progress/progress-cases.json")

    private fun map(json: JsonValue?, key: String): Map<String, Double> =
        json?.get(key)?.obj?.associate { (name, value) -> name to (value.number ?: 0.0) }.orEmpty()

    private fun theme(json: JsonValue?): HotlayoutTheme {
        val colors = json?.get("color")?.obj?.mapNotNull { (name, value) -> value.string?.let(Rgba::hex)?.let { name to it } }?.toMap().orEmpty()
        val gradients = json?.get("gradient")?.obj?.associate { (name, value) ->
            name to GradientValue(
                value["angle"]?.number ?: 0.0,
                value["stops"]?.array.orEmpty().mapNotNull { stop ->
                    val shade = stop["color"]?.string?.let(Rgba::hex) ?: return@mapNotNull null
                    GradientValue.Stop(shade, stop["position"]?.number ?: return@mapNotNull null)
                },
            )
        }.orEmpty()
        return HotlayoutTheme("table", "light", map(json, "spacing"), map(json, "radius"), colors, emptyMap(), emptyMap(), gradients, emptyMap(), emptyMap())
    }

    private val resolver get() = StyleResolver(theme(table["theme"]))

    private fun rows(group: String, count: Int): List<JsonObject> {
        val list = Contract.rows(table, group)
        assertEquals("$group: rows in the table", count, list.size)
        return list
    }

    private fun close(actual: Double, expected: Double?, message: String, tolerance: Double = 1e-6) {
        assertTrue("$message: no expected number", expected != null)
        assertEquals(message, expected!!, actual, tolerance)
    }

    private fun hex(color: Rgba): String {
        fun byte(value: Double) = Math.round(value.coerceIn(0.0, 1.0) * 255).toInt()
        return "#%02x%02x%02x".format(byte(color.red), byte(color.green), byte(color.blue))
    }

    private fun assertColor(color: Rgba?, expected: JsonValue?, message: String) {
        assertTrue("$message: missing colour", color != null && expected != null)
        assertEquals(message, expected!!["hex"]?.string, hex(color!!))
        assertEquals("$message: opacity", expected["opacity"]?.number ?: 1.0, color.alpha, 1e-6)
    }

    @Test fun ratio() {
        for (row in rows("ratioCases", 15)) close(ProgressRules.ratio(row["value"]?.number, row["max"]?.number), row["expect"]?.number, row.name(), 1e-12)
    }

    /** The box of a ring as the table writes it: `padding` and `border` are one number for all sides. */
    private fun ringBox(row: JsonObject): ProgressRules.RingBox {
        val props = row["props"]?.obj ?: JsonObject()
        val style = row["style"]?.obj ?: JsonObject()
        val widthApplies = row["applied"]?.get("width")?.bool != false
        val heightApplies = row["applied"]?.get("height")?.bool != false
        val padding = (style["padding"]?.number ?: 0.0) * 2
        fun side(key: String, applies: Boolean) = style[key]?.number.takeIf { applies }
        return ProgressRules.RingBox(
            size = props["size"], unit = resolver.theme.spacing[ProgressRules.RING_UNIT] ?: 0.0,
            width = side("width", widthApplies), minWidth = side("minWidth", widthApplies), maxWidth = side("maxWidth", widthApplies),
            height = side("height", heightApplies), minHeight = side("minHeight", heightApplies), maxHeight = side("maxHeight", heightApplies),
            paddingInline = padding, paddingBlock = padding, border = style["border"]?.number ?: 0.0,
            thickness = ProgressRules.paint(props, resolver).thickness,
        )
    }

    @Test fun ringGeometry() {
        for (row in rows("ringCases", 33)) {
            val geometry = ProgressRules.ringGeometry(ringBox(row))
            val expect = row["expect"]
            close(geometry.diameter, expect?.get("diameter")?.number, "${row.name()}: diameter")
            close(geometry.thickness, expect?.get("thickness")?.number, "${row.name()}: thickness")
            expect?.get("boxInline")?.number?.let { close(geometry.boxInline, it, "${row.name()}: boxInline") }
            expect?.get("boxBlock")?.number?.let { close(geometry.boxBlock, it, "${row.name()}: boxBlock") }
            expect?.get("freeInline")?.number?.let { close(geometry.boxInline - geometry.diameter, it, "${row.name()}: freeInline") }
            expect?.get("freeBlock")?.number?.let { close(geometry.boxBlock - geometry.diameter, it, "${row.name()}: freeBlock") }
        }
    }

    @Test fun ringArc() {
        for (row in rows("arcCases", 5)) {
            val (start, sweep) = ProgressRules.ringArc(row["ratio"]?.number ?: 0.0)
            close(start, row["expect"]?.get("startDeg")?.number, row.name())
            close(sweep, row["expect"]?.get("sweepDeg")?.number, row.name())
        }
    }

    @Test fun barGeometry() {
        for (row in rows("barCases", 12)) {
            val thickness = ProgressRules.paint(row["props"]?.obj ?: JsonObject(), resolver).thickness
            val geometry = ProgressRules.barGeometry(row["trackInline"]?.number ?: 0.0, thickness, row["ratio"]?.number ?: 0.0, resolver.theme.radius["pill"] ?: 0.0)
            val expect = row["expect"]
            close(geometry.trackBlock, expect?.get("trackBlock")?.number, "${row.name()}: trackBlock")
            close(geometry.fillInline, expect?.get("fillInline")?.number, "${row.name()}: fillInline")
            close(geometry.trackRadius, expect?.get("trackRadius")?.number, "${row.name()}: trackRadius")
            close(geometry.fillRadius, expect?.get("fillRadius")?.number, "${row.name()}: fillRadius")
        }
    }

    @Test fun paint() {
        for (row in rows("paintCases", 21)) {
            val props = row["props"]?.obj ?: JsonObject()
            val ratio = row["ratio"]?.number ?: 0.0
            val paint = ProgressRules.paint(props, resolver)
            val expect = row["expect"]
            assertColor(paint.track, expect?.get("track"), "${row.name()}: track")
            val fill = expect?.get("fill")
            when (val actual = paint.fill) {
                is ProgressRules.Fill.Solid -> {
                    assertEquals(row.name(), "color", fill?.get("kind")?.string)
                    assertColor(actual.color, fill, "${row.name()}: fill")
                }
                is ProgressRules.Fill.Gradient -> {
                    assertEquals(row.name(), "gradient", fill?.get("kind")?.string)
                    val wanted = fill?.get("stops")?.array.orEmpty()
                    assertEquals(row.name(), wanted.size, actual.stops.size)
                    for ((stop, expected) in ProgressRules.gradientStops(actual.stops, ratio).zip(wanted)) {
                        assertColor(stop.first, expected, "${row.name()}: stop")
                        close(stop.second, expected["at"]?.number, "${row.name()}: stop position")
                    }
                }
            }
        }
    }

    private fun segmentsOf(row: JsonObject, track: Double, ratio: Double): ProgressRules.SegmentGeometry? {
        val props = row["props"]?.obj ?: JsonObject()
        val segments = ProgressRules.segmentsCount(props["segments"]) ?: return null
        val thickness = ProgressRules.paint(props, resolver).thickness
        return ProgressRules.segmentGeometry(track, thickness, ratio, segments, resolver.theme.spacing[ProgressRules.SEGMENT_GAP] ?: 0.0, resolver.theme.radius["pill"] ?: 0.0)
    }

    @Test fun segments() {
        for (row in rows("segmentCases", 21)) {
            val ratio = ProgressRules.ratio(row["value"]?.number, row["max"]?.number)
            val geometry = segmentsOf(row, row["trackInline"]?.number ?: 0.0, ratio)
            val expect = row["expect"]?.obj
            if (expect == null) {
                assertNull("${row.name()}: the bar is continuous", geometry)
                continue
            }
            assertTrue("${row.name()}: steps expected", geometry != null)
            close(geometry!!.boxes.firstOrNull()?.inline ?: -1.0, expect["inline"]?.number, "${row.name()}: inline")
            expect["starts"]?.array?.let { starts ->
                assertEquals(row.name(), starts.size, geometry.boxes.size)
                for ((box, start) in geometry.boxes.zip(starts)) close(box.start, start.number, "${row.name()}: start")
            }
            assertEquals("${row.name()}: filled", expect["filled"]?.number?.toInt(), geometry.filled)
            assertEquals("${row.name()}: current", expect["current"]?.number?.toInt(), geometry.current)
            assertEquals("${row.name()}: states", expect["states"]?.string, geometry.states)
            close(geometry.radius, expect["radius"]?.number, "${row.name()}: radius")
            close(geometry.filledInline, expect["filledInline"]?.number, "${row.name()}: filledInline")
        }
    }

    @Test fun segmentsKindIsDecidedByThePlace() {
        for (row in rows("segmentPlaceCases", 6)) {
            val segments = ProgressRules.segmentsCount(row["props"]?.get("segments"))
            assertTrue(row.name(), segments != null)
            val gap = resolver.theme.spacing[ProgressRules.SEGMENT_GAP] ?: 0.0
            val place = row["placeInline"]?.number ?: 0.0
            val expect = row["expect"]
            val kind = ProgressRules.segmentsKind(place, segments!!, gap)
            assertEquals(row.name(), expect?.get("kind")?.string, if (kind == ProgressRules.SegmentsKind.Steps) "steps" else "continuous")
            close(ProgressRules.segmentsMinInline(segments, gap), expect?.get("ownMinInline")?.number, "${row.name()}: own minimum")
            expect?.get("inline")?.number?.let { close((place - (segments - 1) * gap) / segments, it, "${row.name()}: step") }
        }
    }

    @Test fun segmentPaint() {
        for (row in rows("segmentPaintCases", 6)) {
            val props = row["props"]?.obj ?: JsonObject()
            val expect = row["expect"]
            assertColor(ProgressRules.paint(props, resolver).track, expect?.get("unfilled"), "${row.name()}: unfilled")
            val current = ProgressRules.currentColor(props, resolver)
            val wanted = expect?.get("current")
            if (wanted != null && !wanted.isNull) assertColor(current, wanted, "${row.name()}: current") else assertNull("${row.name()}: the current step looks unfilled", current)
        }
    }

    @Test fun segmentGradientRunsAlongTheFilledSteps() {
        for (row in rows("segmentGradientCases", 3)) {
            val props = row["props"]?.obj ?: JsonObject()
            val ratio = ProgressRules.ratio(row["value"]?.number, row["max"]?.number)
            val geometry = segmentsOf(row, row["trackInline"]?.number ?: 0.0, ratio)
            val fill = ProgressRules.paint(props, resolver).fill as? ProgressRules.Fill.Gradient
            assertTrue(row.name(), geometry != null && fill != null)
            val expect = row["expect"]
            close(0.0, expect?.get("lineFrom")?.number, row.name())
            close(geometry!!.filledInline, expect?.get("lineTo")?.number, "${row.name()}: line end")
            for ((stop, expected) in fill!!.stops.zip(expect?.get("stops")?.array.orEmpty())) {
                assertEquals(row.name(), expected["hex"]?.string, hex(stop.color))
                close(stop.position * geometry.filledInline, expected["at"]?.number, "${row.name()}: stop")
            }
            assertEquals(row.name(), true, expect?.get("gapsCutOut")?.bool)
        }
    }

    @Test fun ringLabel() {
        val labelTheme = table["labelTheme"]
        val base = resolver.theme
        val typography = labelTheme?.get("typography")?.obj?.associate { (name, value) ->
            name to TextStyleValue(value["fontSize"]?.number ?: 0.0, value["lineHeight"]?.number ?: 1.0, (value["fontWeight"]?.number ?: 400.0).toInt())
        }.orEmpty()
        val colors = base.color + labelTheme?.get("color")?.obj?.mapNotNull { (name, value) -> value.string?.let(Rgba::hex)?.let { name to it } }.orEmpty()
        val labelResolver = StyleResolver(base.copy(typography = typography, color = colors))
        for (row in rows("ringLabelCases", 20)) {
            val props = row["props"]?.obj ?: JsonObject()
            val expect = row["expect"]
            val value = row["value"]?.number ?: 0.0
            val max = row["max"]?.number ?: 100.0
            val ratio = ProgressRules.ratio(value, max)
            assertEquals("${row.name()}: text", expect?.get("text")?.string, ProgressRules.labelText(props["labelFormat"]?.string, value, max, ratio))
            val geometry = ProgressRules.ringGeometry(ringBox(row))
            close(geometry.diameter, expect?.get("diameter")?.number, "${row.name()}: diameter")
            close(geometry.boxInline, expect?.get("boxInline")?.number, "${row.name()}: the label does not size the ring")
            close(geometry.boxBlock, expect?.get("boxBlock")?.number, "${row.name()}: the label does not size the ring")
            val style = ProgressRules.labelStyle(props, labelResolver)
            val wanted = expect?.get("typography")
            assertEquals("${row.name()}: token", wanted?.get("token")?.string, style.token)
            close(style.typography.fontSize, wanted?.get("fontSize")?.number, "${row.name()}: size")
            close(style.typography.fontWeight.toDouble(), wanted?.get("fontWeight")?.number, "${row.name()}: weight")
            close(style.typography.lineHeight, wanted?.get("lineHeight")?.number, "${row.name()}: line height")
            close(style.typography.lineBox, wanted?.get("lineBlock")?.number, "${row.name()}: line block", 1e-3)
            assertColor(style.color, expect?.get("color"), "${row.name()}: colour")
        }
        for (row in rows("ringLabelNoneCases", 2)) {
            val props = row["props"]?.obj ?: JsonObject()
            assertNull(row.name(), ProgressRules.labelText(props["labelFormat"]?.string, 74.0, 119.0, 0.62))
            close(ProgressRules.ringGeometry(ringBox(row)).diameter, row["expect"]?.get("diameter")?.number, row.name())
        }
    }

    @Test fun roundEnds() {
        for (row in rows("capCases", 20)) {
            val expected = row["expected"]
            val ratio = row["ratio"]?.number ?: 0.0
            if (row["widget"]?.string == "CircularProgress") {
                val cap = ProgressRules.ringCap(ratio, row["diameter"]?.number ?: 0.0, row["thickness"]?.number ?: 0.0)
                assertEquals(row.name(), expected?.get("kind")?.string, cap.kind.word)
                close(cap.startDeg, expected?.get("startDeg")?.number, "${row.name()}: start")
                close(cap.endDeg, expected?.get("endDeg")?.number, "${row.name()}: end")
                close(cap.capAngle, expected?.get("capAngle")?.number, "${row.name()}: thetaC", 1e-6)
            } else {
                val thickness = row["thickness"]?.number ?: 0.0
                close(ProgressRules.barCapFillInline(row["track"]?.number ?: 0.0, thickness, ratio), expected?.get("fillInline")?.number, "${row.name()}: fill")
                close(ProgressRules.barCapRadius(thickness), expected?.get("radius")?.number, "${row.name()}: radius")
            }
        }
    }

    @Test fun stepsDuringATransition() {
        for (row in rows("segmentFillCases", 12)) {
            val segments = (row["segments"]?.number ?: 0.0).toInt()
            val units = ProgressRules.segmentUnits(row["fromRatio"]?.number ?: 0.0, row["toRatio"]?.number ?: 0.0, row["eased"]?.number ?: 0.0, segments)
            val expected = row["expected"]
            close(units, expected?.get("units")?.number, "${row.name()}: units", 1e-9)
            val fills = ProgressRules.segmentFills(units, segments)
            val wanted = expected?.get("fills")?.array.orEmpty().mapNotNull { it.number }
            assertEquals(row.name(), wanted.size, fills.size)
            for ((fill, want) in fills.zip(wanted)) close(fill, want, "${row.name()}: fill", 1e-9)
            assertEquals("${row.name()}: current", expected?.get("current")?.number?.toInt(), ProgressRules.segmentCurrent(fills))
        }
        for (row in rows("segmentFillInlineCases", 4)) {
            close(ProgressRules.segmentFillInline(row["units"]?.number ?: 0.0, row["step"]?.number ?: 0.0, row["gap"]?.number ?: 0.0), row["expected"]?.number, row.name(), 1e-9)
        }
        for (row in rows("transitionShareCases", 5)) {
            close(ProgressRules.transitionShare(row["from"]?.number ?: 0.0, row["to"]?.number ?: 0.0, row["shown"]?.number ?: 0.0), row["expected"]?.number, row.name(), 1e-9)
        }
    }

    @Test fun glow() {
        for (row in rows("glowStepCases", 7)) {
            val fills = row["fills"]?.array.orEmpty().mapNotNull { it.number }
            val step = ProgressRules.glowingStep(fills, row["currentPainted"]?.bool ?: false)
            val expected = row["expected"]
            when (step) {
                ProgressRules.GlowingStep.None -> assertEquals(row.name(), "none", expected?.get("kind")?.string)
                is ProgressRules.GlowingStep.Current -> {
                    assertEquals(row.name(), "current", expected?.get("kind")?.string)
                    assertEquals(row.name(), expected?.get("index")?.number?.toInt(), step.index)
                }
                is ProgressRules.GlowingStep.Front -> {
                    assertEquals(row.name(), "front", expected?.get("kind")?.string)
                    assertEquals(row.name(), expected?.get("index")?.number?.toInt(), step.index)
                    close(step.fill, expected?.get("fill")?.number, row.name())
                }
            }
        }
        for (row in rows("glowRuleCases", 6)) {
            val expect = row["expect"]
            when (row["rule"]?.string) {
                "strength.soft.sigma" -> close(ProgressRules.Glow.Soft.sigma, expect?.number, row.name())
                "strength.soft.opacity" -> close(ProgressRules.Glow.Soft.opacity, expect?.number, row.name())
                "strength.strong.sigma" -> close(ProgressRules.Glow.Strong.sigma, expect?.number, row.name())
                "strength.strong.opacity" -> close(ProgressRules.Glow.Strong.opacity, expect?.number, row.name())
                "on" -> assertEquals(row.name(), setOf("ProgressBar", "CircularProgress"), expect?.array.orEmpty().mapNotNull { it.string }.toSet())
                "segments.others" -> {
                    assertEquals(row.name(), "no glow", expect?.string)
                    assertEquals(row.name(), ProgressRules.GlowingStep.Front(0, 1.0), ProgressRules.glowingStep(listOf(1.0, 0.0, 0.0), false))
                }
                else -> throw AssertionError("rule not read: ${row.name()}")
            }
        }
        assertNull(ProgressRules.Glow.of(JsonValue.Str("blinding")))
        assertNull(ProgressRules.Glow.of(null))
    }
}
