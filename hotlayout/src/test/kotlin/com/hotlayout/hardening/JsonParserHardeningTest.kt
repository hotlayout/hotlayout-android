package com.hotlayout.hardening

import com.hotlayout.HotlayoutLoadException
import com.hotlayout.JsonObject
import com.hotlayout.JsonParseException
import com.hotlayout.JsonValue
import com.hotlayout.json.JsonParser
import com.hotlayout.loadDocument
import com.hotlayout.model.DocumentReader
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Limits of the JSON parser and of the document gate. A document, a theme and data arrive over the
 * network: no input may exhaust the stack, take more than linear time or produce a string the
 * platform cannot hold.
 */
class JsonParserHardeningTest {
    // ---------------------------------------------------------------- nesting

    /** Runs [block] on a thread with a small stack (a background thread of an app, not the 8 MB main thread). */
    private fun <T> onSmallStack(stackBytes: Long = 256 * 1024, block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val thread = Thread(null, { result.set(runCatching(block)) }, "small-stack", stackBytes)
        thread.start()
        thread.join()
        return result.get().getOrThrow()
    }

    private fun nestedArrays(depth: Int): String = "[".repeat(depth) + "]".repeat(depth)

    private fun nestedObjects(depth: Int): String = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    @Test fun nestingAtTheParseLimitIsReadOnASmallStack() {
        val depth = JsonParser.MAX_PARSE_DEPTH + 1 // levels 0…MAX
        val arrays = onSmallStack { JsonValue.parse(nestedArrays(depth)) }
        assertNotNull(arrays.array)
        val objects = onSmallStack { JsonValue.parse(nestedObjects(JsonParser.MAX_PARSE_DEPTH)) } // the value sits at the last level
        assertNotNull(objects.obj)
    }

    @Test fun nestingAboveTheParseLimitIsASyntaxErrorNotAStackOverflow() {
        for (depth in listOf(JsonParser.MAX_PARSE_DEPTH + 2, 10_000, 1_000_000)) {
            for (text in listOf(nestedArrays(depth), nestedObjects(depth), "[".repeat(depth))) {
                try {
                    onSmallStack { JsonValue.parse(text) }
                    fail("depth $depth was accepted")
                } catch (error: JsonParseException) {
                    assertTrue(error.reason, error.reason == "nesting too deep")
                }
            }
        }
    }

    private fun node(id: String, children: String = ""): String =
        """{"id":"$id","type":"Column","props":{"gap":"none"},"children":[$children]}"""

    /** A screen whose root has [levels] levels of nodes under it. */
    private fun tree(levels: Int, prefix: String): String {
        var inner = ""
        for (level in levels downTo 1) inner = node("$prefix$level", inner)
        return """{"id":"${prefix}root","type":"Screen","props":{"gap":"none"},"children":[$inner]}"""
    }

    private fun document(vararg screens: Pair<String, String>): String =
        """{"schemaVersion":"9.27.0","documentId":"d","name":"n","initialScreenId":"${screens.first().first}","screens":[${
            screens.joinToString(",") { (id, root) -> """{"screenId":"$id","name":"$id","root":$root}""" }
        }]}"""

    private fun depthUnder(node: com.hotlayout.model.DocumentNode): Int = 1 + (node.children.maxOfOrNull(::depthUnder) ?: 0)

    @Test fun thirtyTwoLevelsUnderTheRootAreRead() {
        val document = onSmallStack { loadDocument(document("a" to tree(DocumentReader.MAX_PARSE_DEPTH, "n"))) }
        assertEquals(DocumentReader.MAX_PARSE_DEPTH + 1, depthUnder(document.screens.single().root))
    }

    @Test fun thirtyThreeLevelsUnderTheRootAreRefused() {
        try {
            onSmallStack { loadDocument(document("a" to tree(DocumentReader.MAX_PARSE_DEPTH + 1, "n"))) }
            fail("a tree of 33 levels under the root was read")
        } catch (_: HotlayoutLoadException.MaxDepthExceeded) {
        }
    }

    /** The deepest tree the JSON parser lets through is refused by the gate before any recursion over nodes. */
    @Test fun theDeepestParsableTreeIsRefusedWithoutAStackOverflow() {
        val levels = (JsonParser.MAX_PARSE_DEPTH - 8) / 2 // `children` array + node object per level, under screens/root
        try {
            onSmallStack { loadDocument(document("a" to tree(levels, "n"))) }
            fail("refused expected")
        } catch (_: HotlayoutLoadException.MaxDepthExceeded) {
        }
    }

    /**
     * `x-style.layout.composition.depth.readerAboveParseLimit` is `rejects-the-screen`: a screen
     * deeper than the limit is not drawn, and the other screens of the document still are.
     */
    @Test fun aTooDeepScreenIsRejectedAloneAndTheOtherScreensStillLoad() {
        val text = document("home" to tree(3, "h"), "deep" to tree(DocumentReader.MAX_PARSE_DEPTH + 1, "d"))
        val loaded = try {
            loadDocument(text)
        } catch (error: HotlayoutLoadException.MaxDepthExceeded) {
            fail("one screen deeper than the limit refused the whole document: ${error.message}")
            return
        }
        assertEquals(listOf("home"), loaded.screens.map { it.screenId })
        assertTrue(loaded.reports.any { "deep" in it.location || "deep" in it.reason })
    }

    /**
     * `loadDocument(JsonValue)` is public: a host may hand a tree built by its own parser (or by
     * [JsonValue.of]) that this parser never saw. Its depth must not reach a recursion either.
     */
    @Test fun aHostBuiltValueOfAnyDepthDoesNotOverflowTheStack() {
        var deep: JsonValue = JsonValue.Num(1.0)
        repeat(200_000) { deep = JsonValue.Arr(listOf(deep)) }
        val root = JsonValue.Obj(
            JsonObject(
                listOf(
                    "id" to JsonValue.Str("r"), "type" to JsonValue.Str("Screen"),
                    "props" to JsonValue.Obj(JsonObject(listOf("gap" to JsonValue.Str("none"), "deep" to deep))),
                ),
            ),
        )
        val screen = JsonValue.Obj(JsonObject(listOf("screenId" to JsonValue.Str("a"), "name" to JsonValue.Str("a"), "root" to root)))
        val documentValue = JsonValue.Obj(
            JsonObject(listOf("schemaVersion" to JsonValue.Str("9.27.0"), "documentId" to JsonValue.Str("d"), "screens" to JsonValue.Arr(listOf(screen)))),
        )
        try {
            onSmallStack(1024 * 1024) { loadDocument(documentValue) }
        } catch (_: HotlayoutLoadException) {
            // A refusal is a right answer too.
        } catch (overflow: StackOverflowError) {
            fail("a deep host-built value overflowed the stack in loadDocument(JsonValue)")
        }
    }

    // ---------------------------------------------------------------- numbers

    @Test fun numbersBeyondTheDoubleRangeAreNotFiniteValues() {
        for (text in listOf("1e400", "-1e400", "1" + "0".repeat(400), "-" + "9".repeat(5000))) {
            val value = JsonValue.parse(text)
            assertTrue(text.take(20), value is JsonValue.Num)
            assertNull("${text.take(20)} must not read as a number", value.number)
        }
        assertEquals(0.0, JsonValue.parse("1e-400").number!!, 0.0)
        assertEquals(1.7976931348623157e308, JsonValue.parse("1.7976931348623157e308").number!!, 0.0)
    }

    @Test fun nonJsonNumberSpellingsAreRefused() {
        for (text in listOf("NaN", "Infinity", "-Infinity", "+1", ".5", "1.", "1e", "1e+", "0x10", "-", "--1", "1.0.0", "1_000")) {
            try {
                JsonValue.parse(text)
                fail("«$text» was accepted")
            } catch (_: JsonParseException) {
            }
        }
    }

    /** RFC 8259: a number has no leading zeros. `JSON.parse` in the builder refuses them too. */
    @Test fun leadingZerosAreRefused() {
        for (text in listOf("01", "-01", "00", "[007]", "{\"a\":0123}")) {
            try {
                JsonValue.parse(text)
                fail("«$text» was accepted")
            } catch (_: JsonParseException) {
            }
        }
        assertEquals(0.0, JsonValue.parse("0").number!!, 0.0)
        assertEquals(-0.5, JsonValue.parse("-0.5").number!!, 0.0)
    }

    // ---------------------------------------------------------------- keys

    @Test fun aRepeatedKeyKeepsItsFirstPlaceAndTakesTheLastValueAsJsonParseDoes() {
        val value = JsonValue.parse("""{"a":1,"b":2,"a":3}""")
        assertEquals(listOf("a", "b"), value.obj!!.keys)
        assertEquals(3.0, value["a"]!!.number!!, 0.0)
    }

    @Test fun aRepeatedTypeOfANodeIsReadOnceWithTheLastValue() {
        val document = loadDocument(
            """{"schemaVersion":"9.27.0","documentId":"d","screens":[{"screenId":"a","root":{"id":"r","type":"Screen","props":{},"children":[
               {"id":"t","type":"Text","type":"Image","props":{}}]}}]}""",
        )
        assertEquals("Image", document.screens.single().root.children.single().type)
    }

    @Test fun protoKeysAreDroppedAtAnyDepthEvenWhenRepeated() {
        val document = loadDocument(
            """{"schemaVersion":"9.27.0","documentId":"d","__proto__":{"x":1},"screens":[{"screenId":"a","root":{"id":"r","type":"Screen",
               "props":{"__proto__":{"gap":"xl"},"gap":"none","__proto__":1},"children":[]}}]}""",
        )
        val root = document.screens.single().root
        assertFalse(root.props.containsKey("__proto__"))
        assertEquals("none", root.props["gap"]?.string)
    }

    /** Many keys that share one hash code: the map degrades to a tree, not to quadratic time. */
    @Test fun keysThatCollideInTheHashTableStayLinear() {
        // "Aa" and "BB" have the same String hash; 2^17 keys made of them all collide.
        val keys = ArrayList<String>()
        fun build(prefix: String, left: Int) {
            if (left == 0) { keys.add(prefix); return }
            build(prefix + "Aa", left - 1)
            build(prefix + "BB", left - 1)
        }
        build("", 17)
        val text = keys.joinToString(",", "{", "}") { "\"$it\":1" }
        val started = System.nanoTime()
        val value = JsonValue.parse(text)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(keys.size, value.obj!!.size)
        assertTrue("${keys.size} colliding keys took $seconds s", seconds < 10)
    }

    // ---------------------------------------------------------------- strings

    private fun String.hasLoneSurrogate(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    @Test fun escapedSurrogatesMakeOnlyWholeCharacters() {
        val cases = mapOf(
            "\"\\ud83d\\ude00\"" to "\uD83D\uDE00",
            "\"\\ud800\"" to "\uFFFD",
            "\"\\udc00\"" to "\uFFFD",
            "\"\\ud800\\u0041\"" to "\uFFFDA",
            "\"\\ud800\\ud800\\udc00\"" to "\uFFFD\uD800\uDC00",
            "\"\\udc00\\ud800\"" to "\uFFFD\uFFFD",
            "\"a\\ud800\"" to "a\uFFFD",
            "\"\\ud800\\\"" to null, // the escape after the surrogate is broken
        )
        for ((json, expected) in cases) {
            if (expected == null) {
                try { JsonValue.parse(json); fail(json) } catch (_: JsonParseException) {}
                continue
            }
            val text = JsonValue.parse(json).string!!
            assertEquals(json, expected, text)
            assertFalse(json, text.hasLoneSurrogate())
        }
    }

    @Test fun invalidUtf8BytesMakeOnlyWholeCharacters() {
        val bodies = listOf(
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong NUL
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // an encoded surrogate (CESU-8)
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // beyond U+10FFFF
            byteArrayOf(0xE2.toByte(), 0x82.toByte()), // cut in the middle
        )
        for (body in bodies) {
            val bytes = byteArrayOf('"'.code.toByte()) + body + byteArrayOf('"'.code.toByte())
            val text = JsonValue.parse(bytes).string!!
            assertFalse(body.joinToString { "%02X".format(it) }, text.hasLoneSurrogate())
            assertTrue(text.all { it == '\uFFFD' })
        }
    }

    @Test fun rawControlCharactersAndUnknownEscapesAreRefused() {
        for (json in listOf("\"a\u0000b\"", "\"a\nb\"", "\"\\x41\"", "\"\\u12\"", "\"\\u12G4\"", "\"abc", "\"\\")) {
            try { JsonValue.parse(json); fail(json) } catch (_: JsonParseException) {}
        }
    }

    // ---------------------------------------------------------------- time

    private fun secondsToParse(bytes: ByteArray): Double {
        JsonValue.parse(bytes) // warm up the code path
        val started = System.nanoTime()
        JsonValue.parse(bytes)
        return (System.nanoTime() - started) / 1e9
    }

    private fun shapes(size: Int): Map<String, ByteArray> = mapOf(
        "one long string" to ("\"" + "a".repeat(size) + "\"").toByteArray(),
        "a string of escapes" to ("\"" + "\\u0041\\n".repeat(size / 8) + "\"").toByteArray(),
        "a long array of numbers" to ("[" + (0 until size / 8).joinToString(",") { "${it}.5e1" } + "]").toByteArray(),
        "an object of many keys" to ("{" + (0 until size / 16).joinToString(",") { "\"k$it\":true" } + "}").toByteArray(),
        "many small nested objects" to ("[" + (0 until size / 64).joinToString(",") { """{"a":{"b":[1,2,{"c":"x"}]}}""" } + "]").toByteArray(),
        "deep nesting repeated" to ("[" + (0 until size / 400).joinToString(",") { nestedArrays(200) } + "]").toByteArray(),
    )

    /** Eight times the input costs at most about eight times the time (with a wide margin for the JIT and the GC). */
    @Test fun parseTimeStaysLinear() {
        val small = shapes(1 shl 20)
        val large = shapes(8 shl 20)
        for ((name, bytes) in large) {
            val base = maxOf(secondsToParse(small.getValue(name)), 0.005)
            val big = secondsToParse(bytes)
            assertTrue("$name: ${bytes.size} bytes took $big s", big < 10)
            assertTrue("$name: 8× the input took ${big / base}× the time ($base s → $big s)", big / base < 40)
        }
    }
}
