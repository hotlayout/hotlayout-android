package com.hotlayout.hardening

import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.support.Contract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A theme arrives over the network. Every number it holds is read within the bounds of the theme
 * schema (`theme.json` `x-schema`) or not at all, so no later conversion of it (to pixels, `sp`, a
 * corner size, an `Int` weight) can overflow or throw.
 */
class ThemeNumbersHardeningTest {
    private val hostile = listOf(1e308, -1e308, 2147483648.0, -2147483649.0, 1e-300, 1000.0, -1.0)

    private fun JsonValue.withNumbers(value: Double): JsonValue = when (this) {
        is JsonValue.Num -> JsonValue.Num(value)
        is JsonValue.Arr -> JsonValue.Arr(items.map { it.withNumbers(value) })
        is JsonValue.Obj -> JsonValue.Obj(JsonObject(fields.map { (k, v) -> k to v.withNumbers(value) }))
        else -> this
    }

    private fun outOfRange(value: Double): List<String> {
        val set = HotlayoutThemeSet.read(Contract.json("theme.json")["contract"]!!.withNumbers(value)) ?: return emptyList()
        val out = ArrayList<String>()
        fun check(where: String, number: Double, min: Double, max: Double) {
            if (!(number in min..max)) out.add("$where = $number (allowed $min…$max)")
        }
        for (id in set.themes.keys) {
            val theme = set.theme(id)
            theme.spacing.forEach { (k, v) -> check("$id spacing.$k", v, 0.0, 128.0) }
            theme.radius.forEach { (k, v) -> check("$id radius.$k", v, 0.0, 256.0) }
            theme.typography.forEach { (k, v) ->
                check("$id typography.$k.fontSize", v.fontSize, 8.0, 96.0)
                check("$id typography.$k.lineHeight", v.lineHeight, 0.8, 3.0)
                check("$id typography.$k.letterSpacing", v.letterSpacing, -2.0, 8.0)
                check("$id typography.$k.fontWeight", v.fontWeight.toDouble(), 100.0, 900.0)
            }
            theme.elevation.forEach { (k, layers) ->
                layers.forEachIndexed { i, layer ->
                    check("$id elevation.$k[$i].offsetX", layer.offsetX, -64.0, 64.0)
                    check("$id elevation.$k[$i].offsetY", layer.offsetY, -64.0, 64.0)
                    check("$id elevation.$k[$i].blur", layer.blur, 0.0, 128.0)
                    check("$id elevation.$k[$i].spread", layer.spread, -64.0, 64.0)
                    check("$id elevation.$k[$i].opacity", layer.color.alpha, 0.0, 1.0)
                }
            }
            theme.gradient.forEach { (k, g) -> g.stops.forEachIndexed { i, s -> check("$id gradient.$k.stops[$i].position", s.position, 0.0, 1.0) } }
        }
        return out
    }

    @Test fun everyNumberOfAThemeIsReadWithinTheSchemasBounds() {
        val failures = hostile.flatMap { value -> outOfRange(value).take(6).map { "with $value: $it" } }
        assertEquals("theme numbers read out of the schema's range", emptyList<String>(), failures)
    }
}
