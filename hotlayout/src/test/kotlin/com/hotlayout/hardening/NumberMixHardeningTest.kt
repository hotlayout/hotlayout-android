package com.hotlayout.hardening

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.loadDocument
import com.hotlayout.render.motion.LocalCountdownTickedSeconds
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.render.motion.LocalValueTransitionElapsedMs
import com.hotlayout.support.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Every number of a document, a theme and the data is a double; the reader turns many of them into
 * whole numbers (segments, `maxLines`, `perView`, `rotate`, `columns`, `limit`, `glyphSize`, font
 * weights, pixel sizes). A value out of range must be checked or clamped BEFORE the conversion and
 * never throw (`roundToInt` throws on NaN, Compose throws on a size it cannot represent).
 *
 * Where `NumberConversionHardeningTest` puts ONE hostile value everywhere, this one mixes: each number of the
 * documents, the theme and the data becomes a value drawn (with a fixed seed) from the hostile set and from the
 * values that are finite and plausible but too big for a size (65 536, 100 000, 10⁶, 10⁹), so two huge numbers
 * that only break a layout together meet.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NumberMixHardeningTest(private val seed: Int, private val medium: Boolean) {
    @get:Rule val compose = createComposeRule()

    companion object {
        private val hostile = listOf(1e308, -1e308, 2147483648.0, -2147483649.0, 9007199254740993.0, 1e-300, 0.5, -0.0, 0.0, -1.0, 7.0)
        private val big = listOf(65_536.0, 100_000.0, 1e6, 1e9, 32_768.0, 8_193.0, -65_536.0, -100_000.0)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "seed {0}, big values {1}")
        fun parameters(): List<Array<Any>> = (1..25).flatMap { seed -> listOf(arrayOf<Any>(seed, false), arrayOf<Any>(seed, true)) }
    }

    private lateinit var random: java.util.Random

    private fun pick(): Double = (if (medium) big + hostile else hostile).let { it[random.nextInt(it.size)] }

    private fun JsonValue.mixed(): JsonValue = when (this) {
        is JsonValue.Num -> JsonValue.Num(pick())
        is JsonValue.Arr -> JsonValue.Arr(items.map { it.mixed() })
        is JsonValue.Obj -> JsonValue.Obj(JsonObject(fields.map { (k, v) -> k to v.mixed() }))
        else -> this
    }

    /** Every number path of the data dictionary, and three records of every collection whose fields are those of its item. */
    private fun data(): Map<String, JsonValue> {
        val contract = Contract.json("data-contract.json")["contract"]!!
        val paths = contract["paths"]!!.array!!
        val out = LinkedHashMap<String, JsonValue>()
        for (path in paths) {
            val name = path["path"]!!.string!!
            out[name] = if (path["type"]?.string == "number") JsonValue.Num(pick()) else path["sampleValue"] ?: JsonValue.Null
        }
        for (collection in contract["collections"]!!.array!!) {
            val item = collection["item"]!!.string!!
            val fields = paths.mapNotNull { p ->
                val name = p["path"]!!.string!!
                if (!name.startsWith("$item.")) return@mapNotNull null
                name.removePrefix("$item.") to (if (p["type"]?.string == "number") JsonValue.Num(pick()) else p["sampleValue"] ?: JsonValue.Null)
            }
            val records = (0 until 3).map { i -> JsonValue.Obj(JsonObject(fields + ("id" to JsonValue.Str("r$i")))) }
            out[collection["path"]!!.string!!] = JsonValue.Arr(records)
        }
        return out
    }

    private class Case(val name: String, val document: HotlayoutDocument, val theme: HotlayoutThemeSet, val data: Map<String, JsonValue>)

    private fun cases(): List<Case> {
        random = java.util.Random(seed.toLong() * 7919 + (if (medium) 1 else 0))
        val theme = HotlayoutThemeSet.read(Contract.json("theme.json")["contract"]!!.mixed()) ?: HotlayoutThemeSet.builtIn
        val data = data()
        val out = ArrayList<Case>()
        for (name in Contract.fixtureNames) {
            val json = JsonValue.parse(Contract.bytes("fixtures/$name.json"))
            val mutated = json.obj!!.toMap().mapValues { (key, v) -> if (key == "screens") v.mixed() else v }
            val base = JsonObject(mutated)
            val document = runCatching { loadDocument(JsonValue.Obj(base)) }.getOrNull() ?: continue
            for (screen in document.screens) {
                val one = runCatching { loadDocument(JsonValue.Obj(base.with("initialScreenId", JsonValue.Str(screen.screenId)))) }.getOrNull() ?: continue
                out.add(Case("$name: ${screen.screenId}", one, theme, data))
            }
        }
        return out
    }

    private fun drawAll(motion: Boolean): List<String> {
        val all = cases()
        assertTrue(all.size >= 80)
        val failures = ArrayList<String>()
        var current by mutableStateOf<Case?>(null)
        compose.setContent {
            val case = current ?: return@setContent
            key(case.name) {
                // The clocks are held so endless motion, countdowns and transitions stand at a moment of their run.
                CompositionLocalProvider(
                    LocalNodeMotionClockMs provides (if (motion) 700.0 else null),
                    LocalValueTransitionElapsedMs provides 350.0,
                    LocalCountdownTickedSeconds provides 3.0,
                ) {
                    Box(Modifier.size(393.dp, 852.dp)) {
                        HotlayoutScreen(case.document, theme = case.theme, data = case.data, locale = "en", now = 1_791_000_000_000)
                    }
                }
            }
        }
        for (case in all) {
            current = case
            try {
                compose.waitForIdle()
            } catch (error: Throwable) {
                val at = generateSequence(error) { it.cause }.flatMap { it.stackTrace.asSequence() }
                    .firstOrNull { it.className.startsWith("com.hotlayout.") && !it.className.startsWith("com.hotlayout.hardening") }
                failures.add("${case.name} (seed $seed, big $medium): ${error::class.simpleName}: ${error.message?.take(160)} at ${at?.fileName}:${at?.lineNumber}")
                return failures // the composition is broken after a throw; report the first one
            }
        }
        return failures
    }

    @Test fun everyScreenDrawsWithoutThrowing() {
        val failures = drawAll(motion = true)
        assertEquals("a screen threw with mixed numbers", emptyList<String>(), failures)
    }
}
