package com.hotlayout

import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.data.DataLayer
import com.hotlayout.data.DataLayers
import com.hotlayout.data.DataScope
import com.hotlayout.data.EntitySources
import com.hotlayout.data.LiveRecord
import com.hotlayout.data.RecordLink
import com.hotlayout.data.RecordScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.json.jsonObjectOf
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.ScreenPresentation
import com.hotlayout.support.Contract
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared table of data layers, the record scope and the live record (`fixtures/scope/record-scope-cases.json`). */
class RecordScopeCasesTest {
    private val table get() = Contract.json("fixtures/scope/record-scope-cases.json")

    @Test fun layerOrderMirrorsTheArtifact() {
        val words = DataLayer.entries.map { it.word }
        assertEquals(words, table["layerOrder"]?.array.orEmpty().mapNotNull { it.string })
        assertEquals(words, Contract.json("screen-schema.json")["x-dataScope"]?.get("layers")?.array.orEmpty().mapNotNull { it.string })
    }

    @Test fun readCases() {
        val rows = Contract.rows(table, "readCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val layers = row["layers"]?.obj ?: JsonObject()
            fun layer(name: String): DataScope? = layers[name]?.let { value -> value.obj?.toMap().orEmpty() }
            val read = RecordScope.read(DataLayers(layer("record"), layer("session"), layer("host"), layer("data")), row["path"]?.string ?: "")
            assertEquals("value: ${row.name()}", row["value"] ?: JsonValue.Null, read?.second ?: JsonValue.Null)
            assertEquals("layer: ${row.name()}", row["layer"]?.string, read?.first?.word)
        }
    }

    @Test fun recordCases() {
        val rows = Contract.rows(table, "recordCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            var record: DataScope? = row["screenRecord"]?.obj?.toMap()
            for (instance in TableJson.instances(row["instances"])) record = RecordScope.overlay(record, RecordScope.instanceRecord(instance.item, instance.fields))
            assertEquals(row.name(), row["expected"] ?: JsonValue.Null, TableJson.record(record))
        }
    }

    @Test fun flowCases() {
        val rows = Contract.rows(table, "flowCases")
        assertEquals(20, rows.size)
        for (row in rows) {
            val name = row.name()
            val flow = Flow(ActionCasesTest.flowDocument(row, "flow"), null)
            assertEquals("start: $name", listOf(row["start"]?.string ?: ""), flow.stack.map { it.screenId })
            row["steps"]?.array.orEmpty().mapNotNull { it.obj }.forEachIndexed { index, step ->
                val label = "$name — step ${index + 1}"
                val action: JsonObject = when (step["do"]?.string) {
                    "navigate" -> {
                        val base = JsonObject(listOf("type" to JsonValue.Str("navigate"), "params" to JsonValue.Obj(JsonObject(listOf("screenId" to (step["to"] ?: JsonValue.Null))))))
                        if (step["carryRecord"]?.bool == true) base.with("carryRecord", JsonValue.Bool(true)) else base
                    }
                    "back" -> JsonObject.of("type" to "navigate.back")
                    "host" -> JsonObject(listOf("type" to (step["type"] ?: JsonValue.Str("submit"))))
                    else -> error("unknown step: $label")
                }
                val outcome = flow.press(action, TableJson.instances(step["instances"]))
                val expect = step["expect"]
                assertEquals("stack: $label", expect?.get("stack")?.array.orEmpty().mapNotNull { it.string }, flow.stack.map { it.screenId })
                assertEquals("record: $label", expect?.get("record") ?: JsonValue.Null, TableJson.record(flow.top?.record))
                expect?.get("hostRecord")?.let { assertEquals("host record: $label", it, TableJson.record(outcome.hostRecord)) }
            }
        }
    }

    private fun node(json: JsonValue): DocumentNode =
        DocumentNode("n", json["type"]?.string ?: "", json["props"]?.obj ?: JsonObject(), children = json["children"]?.array.orEmpty().map(::node))

    private fun links(value: JsonValue?): List<RecordLink> = value?.array.orEmpty().mapNotNull { it.obj }.map {
        RecordLink(it["source"]?.string ?: "", it["item"]?.string ?: "", it["fields"]?.obj ?: JsonObject())
    }

    private fun listsOf(row: JsonObject): EntitySources =
        LiveRecord.entitySourcesOfLists(row["lists"]?.array.orEmpty().mapNotNull { it.obj }.map { (it["source"]?.string ?: "") to (it["item"]?.string ?: "") })

    @Test fun entitySourceCases() {
        val rows = Contract.rows(table, "entitySourceCases")
        assertEquals(3, rows.size)
        for (row in rows) {
            val found = LiveRecord.entitySources(row["screens"]?.array.orEmpty().map(::node))
            val expected = (row["expected"]?.obj ?: JsonObject()).associate { (entity, list) -> entity to list.array.orEmpty().mapNotNull { it.string } }
            assertEquals(row.name(), expected, found)
        }
    }

    @Test fun liveRecordCases() {
        val rows = Contract.rows(table, "liveRecordCases")
        assertEquals(23, rows.size)
        for (row in rows) {
            val sources = listsOf(row)
            var chain = links(row["chain"])
            row["updates"]?.array.orEmpty().mapNotNull { it.obj }.forEachIndexed { index, update ->
                val label = "${row.name()} — update ${index + 1}"
                val collections = update["collections"]?.obj ?: JsonObject()
                val next = LiveRecord.refresh(chain, { collections[it] }, sources)
                assertEquals("same: $label", update["same"]?.bool == true, next == chain)
                chain = next
                val record = LiveRecord.record(chain)
                assertEquals("record: $label", update["record"] ?: JsonValue.Null, TableJson.record(record))
                update["read"]?.let { read ->
                    val answer = RecordScope.read(DataLayers(record = record, session = Contract.scope(read["session"])), read["path"]?.string ?: "")
                    assertEquals("read value: $label", read["value"], answer?.second)
                    assertEquals("read layer: $label", read["layer"]?.string, answer?.first?.word)
                }
            }
        }
    }

    @Test fun liveFlowCases() {
        val rows = Contract.rows(table, "liveFlowCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val screens = (row["screens"]?.obj ?: JsonObject()).map { (id, presentation) ->
                FlowDocument.Screen(id, if (presentation.string == "modal") ScreenPresentation.Modal else ScreenPresentation.Page)
            }
            val flow = Flow(FlowDocument("live", screens, row["start"]?.string, emptyMap(), listsOf(row)), null)
            row["steps"]?.array.orEmpty().mapNotNull { it.obj }.forEachIndexed { index, step ->
                val label = "${row.name()} — step ${index + 1}"
                when (step["do"]?.string) {
                    "navigate" -> {
                        var action = JsonObject(listOf("type" to JsonValue.Str("navigate"), "params" to jsonObjectOf("screenId" to step["to"])))
                        if (step["carryRecord"]?.bool == true) action = action.with("carryRecord", JsonValue.Bool(true))
                        flow.press(action, TableJson.instances(step["instances"]))
                    }
                    "back" -> flow.press(JsonObject.of("type" to "navigate.back"))
                    "data" -> {
                        val collections = step["collections"]?.obj ?: JsonObject()
                        val before = flow.stack.map { it.screenId }
                        flow.refresh { collections[it] }
                        assertEquals("a data step is not a show: $label", before, flow.stack.map { it.screenId })
                    }
                    else -> error("unknown step: $label")
                }
                val expect = step["expect"]
                assertEquals("stack: $label", expect?.get("stack")?.array.orEmpty().mapNotNull { it.string }, flow.stack.map { it.screenId })
                assertEquals("records: $label", expect?.get("records")?.array.orEmpty(), flow.stack.map { TableJson.record(it.record) })
            }
        }
    }
}
