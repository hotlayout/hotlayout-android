package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.GridGeometry
import com.hotlayout.render.widgets.RecordGroups
import com.hotlayout.render.widgets.RepeaterPlan
import com.hotlayout.render.widgets.gridColumns
import com.hotlayout.model.DocumentNode
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lists from data against the shared tables: the grid, the numbers and the groups (`repeater-filter/…`, `repeater-group/…`). */
class ListsCasesTest {
    private val filter get() = Contract.json("fixtures/repeater-filter/repeater-filter-cases.json")
    private val group get() = Contract.json("fixtures/repeater-group/repeater-group-cases.json")

    @Test fun gridCells() {
        val records = filter["records"]?.array.orEmpty()
        val rows = Contract.rows(filter, "gridCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val drawn = RepeaterPlan.drawnIndexes(records, "reward", row["where"], Contract.scope(row["outer"]), null)
            val cells = GridGeometry.cells(drawn.size, gridColumns(row["columns"]) ?: 0)
            val expected = row["cells"]?.array.orEmpty().map { cell -> cell.array.orEmpty().mapNotNull { it.number?.toInt() } }
            assertEquals(row.name(), expected, cells)
        }
    }

    @Test fun numbersCountTheRecordsDrawn() {
        val records = filter["records"]?.array.orEmpty()
        val rows = Contract.rows(filter, "numberCases")
        assertEquals(2, rows.size)
        for (row in rows) {
            val drawn = RepeaterPlan.drawnIndexes(records, "reward", row["where"], Contract.scope(row["outer"]), null)
            val numbers = RepeaterPlan.numbers(drawn.map { true })
            assertEquals(row.name(), row["numbers"]?.array.orEmpty().map { it.number?.toInt() }, numbers)
        }
    }

    @Test fun aHiddenTemplateTakesNoNumber() {
        assertEquals(listOf(1, null, 2, 3), RepeaterPlan.numbers(listOf(true, false, true, true)))
    }

    @Test fun cellWidthIsOneFormula() {
        assertEquals(176.5, GridGeometry.cellWidth(361.0, 2, 8.0), 1e-9)
        assertEquals(361.0 / 3, GridGeometry.cellWidth(361.0, 3, 0.0), 1e-9)
        assertEquals(74.0, GridGeometry.cellWidth(320.0, 4, 8.0), 1e-9)
    }

    @Test fun columnsReading() {
        assertNull(gridColumns(null))
        assertNull(gridColumns(JsonValue.Null))
        assertEquals(2, gridColumns(JsonValue.Str("x")))
        assertEquals(3, gridColumns(JsonValue.Num(3.0)))
        assertEquals(6, gridColumns(JsonValue.Num(9.0)))
        assertEquals(1, gridColumns(JsonValue.Num(0.0)))
    }

    @Test fun limitIsBoundedAndTheCeilingHolds() {
        fun limit(value: JsonValue?) = RepeaterPlan.limit(if (value == null) JsonObject() else JsonObject.of("limit" to value))
        assertEquals(1, limit(JsonValue.Num(0.0)))
        assertEquals(1, limit(JsonValue.Str("x")))
        assertEquals(50, limit(JsonValue.Num(80.0)))
        assertNull(limit(JsonValue.Null))
        assertNull(limit(null))
        val many = (0 until 80).map { JsonValue.of(mapOf("id" to it)) }
        assertEquals(50, RepeaterPlan.drawnIndexes(many, "x", null, emptyMap(), null).size)
    }

    @Test fun templateVisibilityIsJudgedPerRecord() {
        val template = DocumentNode("t", "Text", fields = JsonObject.of("visibleIf" to mapOf("path" to "task.done", "op" to "isTrue")))
        val node = DocumentNode("r", "Repeater", JsonObject.of("source" to "tasks", "item" to "task"), children = listOf(template))
        val scope = mapOf("tasks" to JsonValue.of(listOf(mapOf("done" to true), mapOf("done" to false), mapOf("done" to true))))
        val plan = RepeaterPlan(node, scope) { t, s -> com.hotlayout.data.Conditions.isVisible(t.visibleIf, s) }
        assertEquals(listOf(true, false, true), plan.instances.map { it.shown })
        assertEquals(listOf(1, null, 2), plan.instances.map { it.number })
    }

    @Test fun sameGroupValue() {
        val rows = Contract.rows(group, "sameCases")
        assertEquals(11, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.bool, RecordGroups.isSame(row["left"], row["right"]))
    }

    @Test fun groupsOfValues() {
        val rows = Contract.rows(group, "groupCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val groups = RecordGroups.groups(row["values"]?.array.orEmpty())
            val expected = row["expect"]?.array.orEmpty().mapNotNull { it.obj }.map { (it["start"]?.number ?: -1.0).toInt() to (it["size"]?.number ?: -1.0).toInt() }
            assertEquals(row.name(), expected, groups)
        }
    }

    @Test fun groupedLists() {
        val rows = Contract.rows(group, "listCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val props = ArrayList<Pair<String, Any?>>(listOf("source" to "activities", "item" to "activity", "groupBy" to "activity.day"))
            row["where"]?.let { props.add("where" to it) }
            row["limit"]?.let { props.add("limit" to it) }
            val node = DocumentNode("list", "Repeater", JsonObject.of(*props.toTypedArray()), children = listOf(DocumentNode("record", "Text"), DocumentNode("header", "Text")))
            val plan = RepeaterPlan(node, mapOf("activities" to (row["records"] ?: JsonValue.Arr(emptyList()))))
            val drawn = ArrayList<String>()
            for (instance in plan.instances) {
                if (instance.headerBefore) drawn.add("header:${instance.scope["activity.day"]?.string ?: ""}")
                if (instance.shown) drawn.add("record:${instance.scope["activity.title"]?.string ?: ""}")
            }
            assertEquals(row.name(), row["expect"]?.array.orEmpty().mapNotNull { it.string }, drawn)
        }
    }

    @Test fun groupRulesMirrorTheArtifact() {
        val block = Contract.json("screen-schema.json")["x-repeaterGroup"]
        assertEquals("groupBy", block?.get("prop")?.string)
        assertEquals(RecordGroups.HEADER_CHILD, block?.get("header")?.get("child")?.number?.toInt() ?: RecordGroups.HEADER_CHILD)
        for (row in Contract.rows(group, "ruleCases")) if (row["rule"]?.string == "header.child") {
            assertEquals(RecordGroups.HEADER_CHILD, row["expect"]?.number?.toInt())
        }
    }
}
