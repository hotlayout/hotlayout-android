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

/**
 * The sample values of the data dictionary (`data-contract.json`, bundled by the sync script): the
 * demo's pictures are the dictionary's own `data:image/…` samples, so frames never need the network.
 */
object ContractSamples {
    private var cache: Map<String, JsonValue>? = null

    private fun samples(context: Context): Map<String, JsonValue> = cache ?: run {
        val root = JsonValue.parse(context.assets.open("data-contract.json").use { it.readBytes() })
        val paths = root["contract"]?.get("paths")?.array.orEmpty()
        paths.mapNotNull { entry -> entry["path"]?.string?.let { it to (entry["sampleValue"] ?: JsonValue.Null) } }.toMap().also { cache = it }
    }

    /** The sample picture of a path (`item.image`), as a `data:` address. */
    fun image(context: Context, path: String): String? = samples(context)[path]?.string?.takeIf { it.startsWith("data:image/") }

    /** The pictures of the demo's asset tokens (`logo`, `banner`, `placeholder`) and the policy that lets `data:` addresses through. */
    fun policy(context: Context): com.hotlayout.render.ImagePolicy {
        val picture = image(context, "item.image")
        val assets = listOf("logo", "banner", "placeholder").mapNotNull { name -> picture?.let { name to it } }.toMap()
        return com.hotlayout.render.ImagePolicy(assets) { address ->
            if (address.startsWith("data:image/") && address.length <= 64 * 1024) address else com.hotlayout.render.ImagePolicy.httpsOnly(address)
        }
    }
}

/** Data for the fixtures that draw lists and bound texts: the lists are empty without it. */
object SampleData {
    fun scope(context: Context): DataScope {
        val picture = ContractSamples.image(context, "item.image")
        val names = listOf("Backpack", "Bottle", "Headphones", "Notebook", "Mug")
        val items = names.mapIndexed { index, name ->
            mapOf(
                "id" to name.lowercase(), "name" to name,
                "image" to (picture ?: "https://images.hotlayout.example/${listOf("wide", "square", "tall")[index % 3]}.png"),
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
        return dataScopeOf("items" to items, "activities" to activities, "tasks" to tasks, "user.points" to 12_400,
            // The dates the data dictionary promises: without them a date text shows its fallback.
            "user.memberSince" to "2026-01-15T12:00:00.000Z", "campaign.endsAt" to "2026-11-30T12:00:00.000Z", "offer.endsAt" to "2026-10-31T12:00:00.000Z",
            // The sample values of the data dictionary for the text fixtures (money, parts, countdown).
            "user.firstName" to "Alex", "user.level" to 3, "offer.price" to 24580.75, "task.progress" to 3, "task.target" to 5, "task.rewardPoints" to 100,
            "account.balance" to 24580.75, "account.income" to 42000, "account.spent" to -1200.5, "account.limit" to 500)
    }
}
