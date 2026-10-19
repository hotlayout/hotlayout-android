package com.hotlayout

import com.hotlayout.json.JsonParseException
import com.hotlayout.json.JsonValue
import com.hotlayout.json.jsonObjectOf
import com.hotlayout.model.DocumentReader
import com.hotlayout.model.HotlayoutLoadException
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.model.ReportSink
import com.hotlayout.model.VersionVerdict
import com.hotlayout.model.checkDocumentVersion
import com.hotlayout.model.checkVersion
import com.hotlayout.model.loadDocument
import com.hotlayout.support.Contract
import com.hotlayout.theme.HotlayoutThemeSet
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.Sides
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentTest {
    /** Every screen fixture loads: unknown pieces end in reports, not in a failure. */
    @Test fun everyFixtureLoads() {
        assertTrue(Contract.fixtureNames.size > 30)
        for (name in Contract.fixtureNames) {
            val document = loadDocument(Contract.bytes("fixtures/$name.json"))
            assertFalse(name, document.screens.isEmpty())
            assertFalse("$name: ${document.reports}", document.reports.any { it.kind == HotlayoutReport.Kind.UnknownWidget })
        }
    }

    @Test fun versionVerdicts() {
        assertEquals(VersionVerdict.Ok, checkVersion("9.0.0"))
        assertEquals(VersionVerdict.Ok, checkVersion("9.27.5"))
        assertEquals(VersionVerdict.NewerMinor("9.99.0"), checkVersion("9.99.0"))
        assertEquals(VersionVerdict.NewerMinor("9.100.0"), checkVersion("9.100.0"))
        assertEquals(VersionVerdict.NewerMajor("10.0.0"), checkVersion("10.0.0"))
        assertEquals(VersionVerdict.OlderMajor("8.20.0"), checkVersion("8.20.0"))
        for (bad in listOf("9.1", "+9.1.0", " 9.1.0", "9.1.0-beta", "9..0", "")) assertEquals(bad, VersionVerdict.Unparsable(bad), checkVersion(bad))
        assertEquals(VersionVerdict.Unparsable(null), checkDocumentVersion(jsonObjectOf("schemaVersion" to 9)))
        assertTrue(VersionVerdict.NewerMinor("9.30.0").isReadable)
        try {
            loadDocument("""{"schemaVersion":"10.0.0","screens":[]}""")
            fail("a newer major is not readable")
        } catch (error: HotlayoutLoadException.IncompatibleVersion) {
            assertEquals(VersionVerdict.NewerMajor("10.0.0"), error.verdict)
        }
    }

    @Test fun piecesOfTheWrongShapeAreReportedNotFatal() {
        val json = """
            {"schemaVersion":"9.27.0","documentId":"d","name":"n","initialScreenId":"a",
             "entryPoints":{"__proto__":"a","promo":"a"},
             "screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{"gap":"sm"},
               "children":[42,{"id":"x"},{"id":"w","type":"FancyWidget","props":{},"children":[{"id":"t","type":"Text","props":"oops"}]}]}}]}
        """
        val document = loadDocument(json)
        val root = document.screens[0].root
        assertEquals(listOf("w"), root.children.map { it.id })
        assertEquals(listOf("Text"), root.children[0].children.map { it.type })
        assertEquals(mapOf("promo" to "a"), document.entryPoints)
        assertTrue(document.reports.any { it.kind == HotlayoutReport.Kind.UnknownWidget })
        assertTrue(document.reports.count { it.kind == HotlayoutReport.Kind.IgnoredInput } >= 3)
    }

    @Test fun tooDeepIsRefusedBeforeRecursion() {
        var node = """{"id":"leaf","type":"Text","props":{}}"""
        repeat(40) { node = """{"id":"c$it","type":"Column","props":{},"children":[$node]}""" }
        try {
            loadDocument("""{"schemaVersion":"9.27.0","documentId":"d","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":$node}]}""")
            fail("too deep")
        } catch (error: HotlayoutLoadException.MaxDepthExceeded) {
            assertEquals("a", error.screenId)
        }
    }

    @Test fun parserIsStrictAboutSyntaxAndBoundsNesting() {
        for (bad in listOf("{", "[1,]", "{\"a\":1,}", "01x", "\"\\x\"", "tru", "{} {}")) {
            try {
                JsonValue.parse(bad)
                fail(bad)
            } catch (_: JsonParseException) {
            }
        }
        try {
            JsonValue.parse("[".repeat(300) + "]".repeat(300))
            fail("nesting")
        } catch (_: JsonParseException) {
        }
        assertEquals(JsonValue.Str("é😀\uFFFD"), JsonValue.parse("\"\\u00e9\\ud83d\\ude00\\ud800\""))
        assertEquals(listOf("b", "a"), JsonValue.parse("""{"b":1,"a":2,"b":3}""").obj?.keys)
    }

    @Test fun firstMatchingStateLaysOverTheBase() {
        val node = DocumentReader.node(
            JsonValue.parse(
                """{"id":"n","type":"Container","props":{},"style":{"padding":"md","background":{"kind":"color","color":"surface"}},
                "styleWhen":[{"when":{"path":"task.done","op":"isTrue"},"style":{"background":{"kind":"color","color":"success"}}},
                             {"when":{"path":"task.done","op":"isTrue"},"style":{"padding":"xl"}}]}""",
            ),
            "test", "root", ReportSink(),
        )!!
        val done = NodeStyle.effective(node, mapOf("task.done" to JsonValue.Bool(true)))
        assertEquals(JsonValue.Str("md"), done["padding"])
        assertEquals(JsonValue.Str("success"), done["background"]?.get("color"))
        assertEquals(JsonValue.Str("surface"), NodeStyle.effective(node, emptyMap())["background"]?.get("color"))
    }

    @Test fun tokenOrOwnValue() {
        val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme("light"))
        assertEquals(Rgba.hex("#4f46e5"), resolver.color(JsonValue.Str("primary")))
        assertEquals(0.5, resolver.color(jsonObjectOf("token" to "primary", "opacity" to 0.5))?.alpha)
        assertEquals(128.0 / 255, resolver.color(jsonObjectOf("value" to "#ff000080"))?.alpha ?: 0.0, 0.0001)
        assertNull(resolver.color(JsonValue.Str("no-such-token")))
        assertNull(resolver.color(jsonObjectOf("value" to "red;position:fixed")))
        assertEquals(128.0, resolver.spacing(JsonValue.Num(500.0)))
        assertEquals(Sides(0.0, 12.0, 0.0, 0.0), resolver.sides(jsonObjectOf("start" to "md")))
        val theme = HotlayoutThemeSet.builtIn.theme("light")
        val withoutLocked = theme.copy(color = theme.color - "locked")
        assertEquals(withoutLocked.colorValue("muted"), withoutLocked.colorValue("locked"))
    }

    @Test fun aThemeOfAnotherMajorIsNotRead() {
        assertNull(HotlayoutThemeSet.read("""{"themeContractVersion":"5.0.0","defaultThemeId":"x","themes":{"x":{}}}"""))
        val partial = HotlayoutThemeSet.read("""{"themeContractVersion":"4.0.0","defaultThemeId":"x","themes":{"x":{"color":{"primary":"#112233","bad":"red"}}}}""")!!
        assertEquals(Rgba.hex("#112233"), partial.theme().colorValue("primary"))
        // A missing entry is filled from the built-in theme of the same brightness.
        assertEquals(HotlayoutThemeSet.builtIn.theme("light").colorValue("surface"), partial.theme().colorValue("surface"))
    }
}
