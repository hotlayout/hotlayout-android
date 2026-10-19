package com.hotlayout.demo

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.hotlayout.data.DataScope
import com.hotlayout.data.dataScopeOf
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import com.hotlayout.render.HotlayoutFonts
import com.hotlayout.theme.HotlayoutThemeSet

/** The screen fixtures of the contract, copied into the assets by `scripts/sync-contract.sh`. */
object Fixtures {
    fun names(context: Context): List<String> =
        context.assets.list("fixtures").orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    /** The document of a fixture; [screenId] replaces the initial screen. */
    fun document(context: Context, name: String, screenId: String?): HotlayoutDocument {
        var json = JsonValue.parse(context.assets.open("fixtures/$name.json").use { it.readBytes() })
        if (screenId != null) json = JsonValue.Obj(json.obj!!.with("initialScreenId", JsonValue.Str(screenId)))
        return loadDocument(json)
    }

    /** The fixture's own theme (`themes/<name>-theme.json` without the `-screens` suffix), if any. */
    fun theme(context: Context, name: String): HotlayoutThemeSet {
        val base = name.removeSuffix("-screens")
        val text = runCatching { context.assets.open("fixtures/themes/$base-theme.json").use { it.readBytes().decodeToString() } }.getOrNull()
        return text?.let { HotlayoutThemeSet.read(it) } ?: HotlayoutThemeSet.builtIn
    }
}

/**
 * The theme of the showcase names Inter. The library ships no fonts (the app provides them), so
 * the demo bundles Inter (SIL Open Font License, `fonts-license/OFL-Inter.txt`) as a variable font.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object DemoFonts : HotlayoutFonts {
    private val inter: FontFamily by lazy {
        val weights = (1..9).map { it * 100 }
        FontFamily(
            weights.map { weight ->
                Font(R.font.inter_variable, FontWeight(weight), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
            } + weights.map { weight ->
                Font(R.font.inter_italic_variable, FontWeight(weight), FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
            },
        )
    }

    override fun family(name: String): FontFamily? = if (name == "Inter") inter else null
}

/** Data for the fixtures that draw lists and bound texts: the lists are empty without it. */
object SampleData {
    fun scope(): DataScope {
        val names = listOf("Backpack", "Bottle", "Headphones", "Notebook", "Mug")
        val items = names.mapIndexed { index, name ->
            mapOf(
                "id" to name.lowercase(), "name" to name,
                "image" to "https://images.hotlayout.example/${listOf("wide", "square", "tall")[index % 3]}.png",
                "selectedId" to if (index < 3) "backpack" else "coffee", "category" to if (index < 3) "gear" else "home",
            )
        }
        val activities = listOf(
            mapOf("title" to "Coffee week", "day" to "Today", "points" to 120),
            mapOf("title" to "Movie night", "day" to "Today", "points" to 80),
            mapOf("title" to "Strong week", "day" to "Yesterday", "points" to 60),
            mapOf("title" to "Weekend trip", "day" to "Wed, Oct 14", "points" to 250),
        )
        val tasks = (1..5).map { mapOf("title" to "Challenge $it", "progress" to it * 3, "target" to 20) }
        return dataScopeOf("items" to items, "activities" to activities, "tasks" to tasks, "user.points" to 12_400)
    }
}
