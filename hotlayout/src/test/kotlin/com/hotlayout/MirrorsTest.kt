package com.hotlayout

import com.hotlayout.icons.IconBody
import com.hotlayout.icons.IconSet
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentReader
import com.hotlayout.model.HotlayoutVersions
import com.hotlayout.support.Contract
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.HotlayoutThemeSet
import com.hotlayout.theme.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library carries copies of contract data (the built-in theme, the icon set, the lists the
 * reader needs at run time). Every copy is compared with the artifacts, so that a sync of the
 * contract cannot leave the reader behind silently.
 */
class MirrorsTest {
    @Test fun versionsMatchTheArtifacts() {
        assertEquals(HotlayoutVersions.SCHEMA, Contract.json("screen-schema.json")["x-schemaVersion"]?.string)
        assertEquals(HotlayoutVersions.THEME, Contract.json("theme.json")["x-themeContractVersion"]?.string)
        assertEquals(HotlayoutVersions.ICON_SET, Contract.json("icons.json")["iconSetVersion"]?.string)
        assertEquals(HotlayoutVersions.ICON_SET, IconSet.shippedVersion)
    }

    @Test fun builtInThemeIsTheContractBlock() {
        val artifact = Contract.json("theme.json")["contract"]
        val shipped = JsonValue.parse(MirrorsTest::class.java.getResourceAsStream("/com/hotlayout/built-in-theme.json")!!.readBytes())
        assertEquals(artifact, shipped)
        val set = HotlayoutThemeSet.builtIn
        assertEquals(artifact?.get("defaultThemeId")?.string, set.defaultThemeId)
        assertEquals(artifact?.get("themes")?.obj?.keys?.toSet(), set.themes.keys)
        assertEquals(Rgba.hex(artifact?.get("themes")?.get("light")?.get("color")?.get("primary")?.string ?: ""), set.theme("light").colorValue("primary"))
    }

    @Test fun optionalColorFallbacksMirrorTheArtifact() {
        val fallbacks = Contract.json("theme.json")["x-optionalColors"]?.get("fallbacks")?.obj
        assertEquals(fallbacks?.associate { it.first to it.second.string }, HotlayoutTheme.OPTIONAL_COLOR_FALLBACKS)
    }

    @Test fun iconSetIsTheArtifact() {
        val artifact = Contract.json("icons.json")
        assertEquals(artifact["icons"]?.obj?.keys?.toSet(), IconSet.names.toSet())
        val schema = Contract.json("screen-schema.json")
        assertEquals(schema["x-mirroredIcons"]?.array.orEmpty().mapNotNull { it.string }.toSet(), IconSet.mirrored)
        assertEquals(artifact["grid"]?.get("size")?.number, IconSet.GRID_SIZE)
        assertEquals(artifact["grid"]?.get("strokeWidth")?.number, IconSet.DEFAULT_STROKE_WIDTH)
        assertEquals(artifact["format"]?.get("elements")?.obj?.keys?.toSet(), IconBody.geometry.keys)
    }

    /** Every icon of the set parses into at least one element: a dropped body would draw an empty box. */
    @Test fun everyIconDraws() {
        for (name in IconSet.names) {
            val elements = IconSet.elements(name).orEmpty()
            assertFalse(name, elements.isEmpty())
            for (element in elements) assertFalse(name, element.path.commands.isEmpty())
        }
        assertNull(IconSet.elements("no-such-icon"))
    }

    @Test fun widgetListMirrorsTheArtifact() {
        val widgets = Contract.json("screen-schema.json")["x-compatibility"]?.get("widgets")?.obj
        assertEquals(widgets?.keys?.toSet(), DocumentReader.knownWidgets)
    }
}
