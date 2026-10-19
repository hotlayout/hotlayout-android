package com.hotlayout

import com.hotlayout.data.Conditions
import com.hotlayout.data.ListFilter
import com.hotlayout.json.JsonValue
import com.hotlayout.json.jsonObjectOf
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conditions against the shared tables: emptiness and an operand from another path
 * (`inputs/input-cases.json`) and the list filter (`repeater-filter/repeater-filter-cases.json`).
 */
class ConditionCasesTest {
    private val inputs get() = Contract.json("fixtures/inputs/input-cases.json")
    private val filter get() = Contract.json("fixtures/repeater-filter/repeater-filter-cases.json")

    @Test fun emptinessCases() {
        val rows = Contract.rows(inputs, "emptinessCases")
        assertEquals(14, rows.size)
        for (row in rows) {
            val scope = row["value"]?.let { mapOf("x" to it) }.orEmpty()
            assertEquals("isEmpty: ${row.name()}", row["isEmpty"]?.bool, Conditions.holds(jsonObjectOf("path" to "x", "op" to "isEmpty"), scope))
            assertEquals("isNotEmpty: ${row.name()}", row["isNotEmpty"]?.bool, Conditions.holds(jsonObjectOf("path" to "x", "op" to "isNotEmpty"), scope))
        }
    }

    @Test fun valuePathCases() {
        val rows = Contract.rows(inputs, "valuePathCases")
        assertEquals(22, rows.size)
        for (row in rows) {
            assertEquals(row.name(), row["holds"]?.bool, Conditions.isVisible(row["condition"], Contract.scope(row["data"])))
        }
    }

    @Test fun filterCases() {
        val records = filter["records"]?.array.orEmpty()
        val rows = Contract.rows(filter, "filterCases")
        assertEquals(21, rows.size)
        for (row in rows) {
            val passed = ListFilter.passedIndexes(row["records"]?.array ?: records, "reward", row["where"], Contract.scope(row["outer"]))
            assertEquals(row.name(), row["passed"]?.array.orEmpty().mapNotNull { it.number?.toInt() }, passed)
        }
    }

    @Test fun limitCases() {
        val records = filter["records"]?.array.orEmpty()
        val rows = Contract.rows(filter, "limitCases")
        assertEquals(4, rows.size)
        for (row in rows) {
            val drawn = ListFilter.passedIndexes(records, "reward", row["where"], Contract.scope(row["outer"]), row["limit"]?.number?.toInt())
            assertEquals(row.name(), row["drawn"]?.array.orEmpty().mapNotNull { it.number?.toInt() }, drawn)
        }
    }

    @Test fun filteredEmptinessCases() {
        val rows = Contract.rows(filter, "emptinessCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val scope = Contract.scope(row["outer"]).toMutableMap()
            when {
                row["noList"]?.bool == true -> scope.remove("rewards")
                row["emptyList"]?.bool == true -> scope["rewards"] = JsonValue.Arr(emptyList())
                else -> scope["rewards"] = row["records"] ?: filter["records"] ?: JsonValue.Null
            }
            assertEquals(row.name(), row["holds"]?.bool, Conditions.isVisible(row["condition"], scope))
        }
    }
}
