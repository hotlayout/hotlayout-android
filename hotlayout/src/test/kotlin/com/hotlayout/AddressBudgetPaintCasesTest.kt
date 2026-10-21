package com.hotlayout

import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.SilentText
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.widgets.ImageSourceRules
import com.hotlayout.render.widgets.ListBudget
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tables that are rules as functions: the address of a picture, the list budget, the numbers of a
 * theme and the paint of a text. The same rules on the real screen are in [AddressBudgetPaintWiringTest].
 */
class AddressBudgetPaintCasesTest {
    private val images get() = Contract.json("fixtures/image-source/image-source-cases.json")
    private val budget get() = Contract.json("fixtures/repeater-filter/list-budget-cases.json")
    private val numbers get() = Contract.json("fixtures/theme-numbers/theme-number-cases.json")
    private val silent get() = Contract.json("fixtures/accessible-name/silent-text-cases.json")

    // ---------------------------------------------------------------- the address of a picture

    private fun addressOf(row: JsonObject): String {
        row["url"]?.string?.let { return it }
        val spec = row["urlOfLength"]!!
        val prefix = spec["prefix"]!!.string!!
        return prefix + spec["pad"]!!.string!!.repeat(spec["length"]!!.number!!.toInt() - prefix.length)
    }

    @Test fun defaultCases() {
        val rows = Contract.rows(images, "defaultCases")
        assertEquals(78, rows.size)
        for (row in rows) {
            val address = addressOf(row)
            val problem = ActionCatalog.formProblem("httpsUrl", address)?.word
            assertEquals("the form: ${row.name()}", row["problem"]?.string, problem)
            // The default rule of a picture is exactly that form: a problem is a refusal, no problem is the address itself.
            val expected = if (row["problem"]?.string == null) address else null
            assertEquals("the default rule: ${row.name()}", expected, ImagePolicy.httpsOnly(address))
        }
    }

    @Test fun neverAnImageCases() {
        val rows = Contract.rows(images, "neverAnImageCases")
        assertEquals(16, rows.size)
        for (row in rows) assertEquals(row.name(), row["never"]?.bool, ImageSourceRules.neverAnImage(row["url"]!!.string!!))
    }

    @Test fun theReportOfAnAddressNamesAsLittleAsTheDocumentAllows() {
        val long = "https://example.com/" + "a".repeat(300)
        assertEquals(121, ImageSourceRules.documentAddress(long).length)
        assertEquals("https://x.example/a.png", ImageSourceRules.documentAddress("https://x.example/a.png"))
        assertEquals("https://cdn.example.com", ImageSourceRules.schemeAndHost("https://cdn.example.com/secret/path?token=abc#f"))
        assertEquals("https://127.0.0.1", ImageSourceRules.schemeAndHost("https://user:pw@127.0.0.1:8080/x?token=1"))
        assertEquals("http://localhost", ImageSourceRules.schemeAndHost("http://localhost:5173/a.png"))
        assertEquals("javascript:", ImageSourceRules.schemeAndHost("javascript:alert(document.cookie)"))
        assertTrue(ImageSourceRules.schemeAndHost("no scheme at all, a token=abc").startsWith("("))
    }

    // ---------------------------------------------------------------- the list budget

    /** A list of the table as a real `Repeater`: the collection is named after the list, the template holds the lists inside. */
    private fun listNode(spec: JsonValue, collections: MutableMap<String, JsonValue>): DocumentNode {
        val name = spec["name"]!!.string!!
        collections["c_$name"] = JsonValue.of((0 until spec["records"]!!.number!!.toInt()).map { mapOf("id" to it) })
        val inside = spec["inside"]?.array.orEmpty().map { listNode(it, collections) }
        val template = if (inside.isEmpty()) DocumentNode("t_$name", "Text") else DocumentNode("t_$name", "Column", children = inside)
        return DocumentNode(name, "Repeater", JsonObject.of("source" to "c_$name", "item" to "i_$name"), children = listOf(template))
    }

    @Test fun budgetCases() {
        val rows = Contract.rows(budget, "budgetCases")
        assertEquals(14, rows.size)
        for (row in rows) {
            val collections = LinkedHashMap<String, JsonValue>()
            val lists = row["lists"]!!.array!!.map { listNode(it, collections) }
            val root = DocumentNode("root", "Screen", children = lists)
            val plan = ListBudget.plan(root, collections, row["budget"]?.number?.toInt() ?: ListBudget.PER_SCREEN)
            val expected = row["drawn"]!!.obj!!.associate { (key, value) -> key to value.number!!.toInt() }
            val drawn = expected.keys.associateWith { plan.drawnByList[it] ?: 0 }
            assertEquals("drawn: ${row.name()}", expected, drawn)
            assertEquals("total: ${row.name()}", row["total"]!!.number!!.toInt(), plan.total)
            assertEquals("notDrawn: ${row.name()}", row["notDrawn"]!!.number!!.toInt(), plan.notDrawn)
        }
    }

    @Test fun theBudgetIsTheArtifactsNumber() {
        assertEquals(Contract.json("screen-schema.json")["x-listBudget"]!!["perScreen"]!!.number!!.toInt(), ListBudget.PER_SCREEN)
    }

    @Test fun aGroupHeaderIsNoInstanceAndTheListsInsideItCountAfterTheirInstance() {
        val inner = DocumentNode("inner", "Repeater", JsonObject.of("source" to "c", "item" to "ci"), children = listOf(DocumentNode("it", "Text")))
        val header = DocumentNode("header", "Column", children = listOf(inner))
        val outer = DocumentNode(
            "outer", "Repeater", JsonObject.of("source" to "c", "item" to "o", "groupBy" to "o.day"),
            children = listOf(DocumentNode("row", "Text"), header),
        )
        val records = JsonValue.of(listOf(mapOf("day" to "a"), mapOf("day" to "a"), mapOf("day" to "b")))
        val plan = ListBudget.plan(DocumentNode("root", "Screen", children = listOf(outer)), mapOf("c" to records), budget = 100)
        // Three outer instances; the header stands above two of them (the first of each group), and the list in it draws 3 records each.
        assertEquals(3, plan.drawnByList["outer"])
        assertEquals(6, plan.drawnByList["inner"])
        assertEquals(9, plan.total)
    }

    // ---------------------------------------------------------------- the numbers of a theme

    private fun plain(value: JsonValue): Any? = when (value) {
        is JsonValue.Obj -> LinkedHashMap<String, Any?>().also { map -> value.fields.forEach { (key, item) -> map[key] = plain(item) } }
        is JsonValue.Arr -> value.items.map { plain(it) }.toMutableList()
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Bool -> value.value
        JsonValue.Null -> null
    }

    private fun text(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":${text(it.value)}" }
        is List<*> -> value.joinToString(",", "[", "]") { text(it) }
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Double -> if (value.isInfinite()) (if (value > 0) "1e309" else "-1e309") else value.toString()
        else -> value.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readWith(row: JsonObject, reports: MutableList<HotlayoutReport>): HotlayoutTheme_ {
        val place = row["place"]!!
        val brightness = row["brightness"]!!.string!!
        val contract = plain(Contract.json("theme.json")["contract"]!!) as MutableMap<String, Any?>
        val themes = contract["themes"] as MutableMap<String, Any?>
        val id = themes.entries.first { (it.value as Map<String, Any?>)["brightness"] == brightness }.key
        val theme = themes[id] as MutableMap<String, Any?>
        val dictionary = place["dictionary"]!!.string!!
        val token = place["token"]!!.string!!
        val given: Any? = row["value"]!!.let { if (it is JsonValue.Str) (if (it.value.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY) else it.number!! }
        val section = theme.getOrPut(dictionary) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
        val field = place["field"]?.string
        when (dictionary) {
            "spacing", "radius" -> section[token] = given
            "typography" -> {
                val style = section.getOrPut(token) { linkedMapOf("fontSize" to 14.0, "lineHeight" to 1.4, "fontWeight" to 400.0) } as MutableMap<String, Any?>
                style[field!!] = given
            }
            "elevation" -> ((section[token] as List<MutableMap<String, Any?>>)[place["layer"]!!.number!!.toInt()])[field!!] = given
            "gradient" -> (((section[token] as Map<String, Any?>)["stops"]) as List<MutableMap<String, Any?>>)[place["stop"]!!.number!!.toInt()][field!!] = given
        }
        val set = HotlayoutThemeSet.read(text(contract), reports) ?: error("the theme does not read: ${row.name()}")
        return HotlayoutTheme_(set, id)
    }

    /** The number the reader draws with at the place of the row. */
    private fun drawnAt(row: JsonObject, set: HotlayoutTheme_): Double {
        val place = row["place"]!!
        val theme = set.set.theme(set.id)
        val token = place["token"]!!.string!!
        val field = place["field"]?.string
        return when (place["dictionary"]!!.string!!) {
            "spacing" -> theme.spacing.getValue(token)
            "radius" -> theme.radius.getValue(token)
            "typography" -> theme.typography.getValue(token).let {
                when (field) {
                    "fontSize" -> it.fontSize
                    "lineHeight" -> it.lineHeight
                    "fontWeight" -> it.fontWeight.toDouble()
                    "letterSpacing" -> it.letterSpacing
                    else -> error(field.toString())
                }
            }
            "elevation" -> theme.elevation.getValue(token)[place["layer"]!!.number!!.toInt()].let {
                when (field) {
                    "offsetX" -> it.offsetX
                    "offsetY" -> it.offsetY
                    "blur" -> it.blur
                    "spread" -> it.spread
                    "opacity" -> it.color.alpha
                    else -> error(field.toString())
                }
            }
            "gradient" -> theme.gradient.getValue(token).stops[place["stop"]!!.number!!.toInt()].position
            else -> error("dictionary")
        }
    }

    private class HotlayoutTheme_(val set: HotlayoutThemeSet, val id: String)

    @Test fun numberCases() {
        val rows = Contract.rows(numbers, "numberCases")
        assertEquals(31, rows.size)
        for (row in rows) {
            val reports = ArrayList<HotlayoutReport>()
            val set = readWith(row, reports)
            assertEquals(row.name(), row["expected"]!!.number!!, drawnAt(row, set), 1e-9)
            val place = row["place"]!!
            val token = place["token"]!!.string!!
            val replaced = reports.filter { token in it.location && place["dictionary"]!!.string!! in it.location }
            if (row["replacedBy"]?.string == null) {
                assertEquals("no report: ${row.name()}", emptyList<HotlayoutReport>(), replaced)
            } else {
                val word = if (row["replacedBy"]!!.string == "built-in") "built-in" else "nearest bound"
                assertTrue("a report of the replacement by the $word: ${row.name()}: $replaced", replaced.any { word in it.reason })
            }
        }
    }

    // ---------------------------------------------------------------- the paint of a text

    @Test fun paintCases() {
        val rows = Contract.rows(silent, "paintCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val opacities = row["opacities"]!!.array!!.map { it.number }
            assertEquals(row.name(), row["paintsNothing"]!!.bool, SilentText.paintsNothing(opacities, row["colorOpacity"]?.number))
        }
    }
}
