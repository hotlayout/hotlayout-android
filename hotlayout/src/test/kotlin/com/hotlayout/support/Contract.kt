package com.hotlayout.support

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import org.junit.Assert.assertTrue

/** Reads the contract copies in the test resources (`scripts/sync-contract.sh`). */
object Contract {
    private val cache = HashMap<String, JsonValue>()

    fun bytes(relativePath: String): ByteArray =
        Contract::class.java.getResourceAsStream("/contract/$relativePath")?.use { it.readBytes() }
            ?: error("Test resources have no contract/$relativePath: run scripts/sync-contract.sh")

    fun json(relativePath: String): JsonValue = synchronized(cache) {
        cache.getOrPut(relativePath) { JsonValue.parse(bytes(relativePath)) }
    }

    fun rows(table: JsonValue, group: String): List<JsonObject> = table[group]?.array.orEmpty().mapNotNull { it.obj }

    /** A flat data map of a row: `null` is absence, as the tables say. */
    fun scope(value: JsonValue?): DataScope =
        value?.obj?.filter { !it.second.isNull }?.associate { it.first to it.second }.orEmpty()

    /** The screen fixtures of the contract (the JSON files at the top of `fixtures`, not the tables in sub-folders). */
    val fixtureNames: List<String> by lazy {
        val listing = Contract::class.java.getResource("/contract/fixtures")?.let { java.io.File(it.toURI()) }
            ?: error("no fixtures folder in the test resources")
        listing.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()
    }

    /** The names of the groups of a shared table: its keys that hold a list of rows. */
    fun groups(table: JsonValue): Set<String> =
        table.obj?.filter { (_, value) -> value.array?.firstOrNull()?.obj != null }?.map { it.first }?.toSet().orEmpty()
}

fun JsonObject.name(): String = this["name"]?.string ?: "?"

/** A row that did not give the table's answer. */
data class Mismatch(val name: String, val expected: String, val actual: String) {
    override fun toString(): String = "«$name»\n   expected: ${visible(expected)}\n     actual: ${visible(actual)}"

    companion object {
        fun visible(text: String): String = text.map { if (it.code in 0x20..0x7E) it.toString() else "\\u{%04X}".format(it.code) }.joinToString("")
    }
}

/**
 * Asserts that the rows that differ are exactly the documented exceptions: an exception that no
 * longer differs fails too, so the list cannot go stale.
 */
fun assertOnlyKnownDifferences(mismatches: List<Mismatch>, known: Map<String, String>, group: String) {
    val unexpected = mismatches.filter { known[it.name] != it.actual }
    val stale = known.keys.filter { it.startsWith("$group: ") } - mismatches.map { it.name }.toSet()
    assertTrue("$group: ${unexpected.size} row(s) differ from the table:\n" + unexpected.joinToString("\n"), unexpected.isEmpty())
    assertTrue("$group: exceptions name rows that now pass or no longer exist: ${stale.sorted()}", stale.isEmpty())
}
