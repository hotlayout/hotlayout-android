package com.hotlayout.demo

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.hotlayout.DataScope
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutFonts
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.JsonValue
import com.hotlayout.dataScopeOf
import com.hotlayout.loadDocument

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
 * The themes of the showcases name Inter, Geist and Manrope. The library ships no fonts (the app provides them), so
 * the demo bundles them (SIL Open Font License, `fonts-license/`) as variable fonts.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object DemoFonts : HotlayoutFonts {
    /**
     * One face of the variable font at one weight. Compose's own variable-font entry (`Font(..., variationSettings)`)
     * builds a typeface whose style weight stays the file's default, and the platform picks fallback fonts (Arabic,
     * which Inter lacks) by that style weight — so bold Arabic came out regular. Building the typeface here with
     * `setWeight` gives the fallback the weight too. A host that ships a variable font must do the same; a host that
     * ships static files per weight gets it for free.
     */
    private class WeightedFace(
        private val resourceId: Int,
        override val weight: FontWeight,
        override val style: FontStyle,
    ) : androidx.compose.ui.text.font.AndroidFont(
        androidx.compose.ui.text.font.FontLoadingStrategy.Blocking,
        Loader,
        FontVariation.Settings(FontVariation.weight(weight.weight)),
    ) {
        object Loader : androidx.compose.ui.text.font.AndroidFont.TypefaceLoader {
            override fun loadBlocking(context: Context, font: androidx.compose.ui.text.font.AndroidFont): android.graphics.Typeface? {
                val face = font as WeightedFace
                val file = java.io.File(context.cacheDir, "font-${face.resourceId}.ttf")
                if (!file.exists()) {
                    context.resources.openRawResource(face.resourceId).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                return android.graphics.Typeface.Builder(file)
                    .setFontVariationSettings("'wght' ${face.weight.weight}")
                    .setWeight(face.weight.weight)
                    .setItalic(face.style == FontStyle.Italic)
                    .build()
            }

            override suspend fun awaitLoad(context: Context, font: androidx.compose.ui.text.font.AndroidFont): android.graphics.Typeface? =
                loadBlocking(context, font)
        }
    }

    /** One family of upright weights 100–900, and of italics when the file has them. */
    private fun family(upright: Int, italic: Int?): FontFamily {
        val weights = (1..9).map { it * 100 }
        return FontFamily(
            weights.map { WeightedFace(upright, FontWeight(it), FontStyle.Normal) } +
                if (italic == null) emptyList() else weights.map { WeightedFace(italic, FontWeight(it), FontStyle.Italic) },
        )
    }

    private val inter: FontFamily by lazy { family(R.font.inter_variable, R.font.inter_italic_variable) }
    private val geist: FontFamily by lazy { family(R.font.geist_variable, null) }
    private val manrope: FontFamily by lazy { family(R.font.manrope_variable, null) }

    override fun family(name: String): FontFamily? = when (name) {
        "Inter" -> inter
        "Geist", "Geist Variable" -> geist
        "Manrope" -> manrope
        else -> null
    }
}

/**
 * The sample values of the data dictionary (`data-contract.json`, bundled by the sync script) and the sample
 * pictures of the catalog (the `webp` files of `assets/sample`, the builder's own): frames never need the network.
 */
object ContractSamples {
    private var cache: Map<String, JsonValue>? = null

    /** The dictionary's samples by path. */
    fun samples(context: Context): Map<String, JsonValue> = cache ?: run {
        val root = JsonValue.parse(context.assets.open("data-contract.json").use { it.readBytes() })
        val paths = root["contract"]?.get("paths")?.array.orEmpty()
        paths.mapNotNull { entry -> entry["path"]?.string?.let { it to (entry["sampleValue"] ?: JsonValue.Null) } }.toMap().also { cache = it }
    }

    /** The sample picture of a path (`item.image`), as a `data:` address. */
    fun image(context: Context, path: String): String? = samples(context)[path]?.string?.takeIf { it.startsWith("data:image/") }

    /** One of the bundled catalog pictures (`headphones`, `backpack`, `coffee-set`) as a `data:` address. */
    fun picture(context: Context, name: String): String {
        val bytes = context.assets.open("sample/$name.webp").use { it.readBytes() }
        return "data:image/webp;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** The pictures of the demo's asset tokens (`logo`, `banner`, `placeholder`) and the policy that lets `data:` addresses through. */
    fun policy(context: Context): com.hotlayout.ImagePolicy {
        val assets = mapOf("logo" to picture(context, "coffee-set"), "banner" to picture(context, "backpack"), "placeholder" to picture(context, "headphones"))
        return com.hotlayout.ImagePolicy(assets) { address ->
            if (address.startsWith("data:image/") && address.length <= 64 * 1024) address else com.hotlayout.ImagePolicy.httpsOnly(address)
        }
    }
}

/**
 * Data for the fixtures that draw lists and bound texts: every path of the data dictionary with its sample value, and
 * the dictionary's collections with the same records the Flutter demo shows (`sample_data.dart`), so a pair of frames
 * compares the readers and not the data.
 */
object SampleData {
    fun scope(context: Context): DataScope {
        val headphones = ContractSamples.picture(context, "headphones")
        val backpack = ContractSamples.picture(context, "backpack")
        val coffee = ContractSamples.picture(context, "coffee-set")
        val taskImage = ContractSamples.image(context, "task.image")
        val offerImage = ContractSamples.image(context, "offer.image")
        fun task(id: String, title: String, progress: Int, target: Int, completed: Boolean, points: Int, status: String, at: String = "2026-10-03T10:14:35.000Z") = mapOf(
            "id" to id, "title" to title, "description" to "Any amount, any merchant", "image" to taskImage, "progress" to progress,
            "target" to target, "completed" to completed, "completedAt" to at, "rewardPoints" to points, "status" to status,
        )
        val tasks = listOf(
            task("task-1", "Spend 500 with your card", 3, 5, false, 100, "inProgress"),
            task("task-2", "Set up a recurring payment", 1, 1, true, 50, "completed"),
            task("task-3", "Invite a friend", 0, 1, false, 200, "locked"),
        )
        val groupTasks = listOf(
            task("group-task-1", "Pay a utility bill", 3, 5, true, 100, "completed"),
            task("group-task-2", "Top up your balance", 3, 5, false, 100, "inProgress"),
            task("group-task-3", "Order a card", 3, 5, false, 100, "locked"),
        )
        val doneTasks = listOf(task("done-1", "Pay a gas bill", 2, 2, true, 80, "completed", "2026-10-02T08:40:00.000Z"))
        val groups = listOf(
            mapOf("title" to "Weekly streak", "description" to "Complete all tasks before the end of the month", "totalTasks" to 5, "completedTasks" to 3,
                "completed" to false, "rewardPoints" to 500, "tasks" to groupTasks),
            mapOf("title" to "Summer marathon", "description" to "Complete all tasks before the end of the month", "totalTasks" to 4, "completedTasks" to 4,
                "completed" to true, "rewardPoints" to 500, "tasks" to groupTasks),
        )
        val items = listOf(
            mapOf("name" to "Bluetooth speaker", "image" to headphones, "description" to "Noise cancelling, 30h battery", "price" to 2400, "selectedId" to "speaker"),
            mapOf("name" to "Canvas backpack", "image" to backpack, "description" to "Fits a 15-inch laptop", "price" to 3200, "selectedId" to "backpack"),
            mapOf("name" to "Coffee grinder set", "image" to coffee, "description" to "Two cups and a grinder", "price" to 1500, "selectedId" to "coffee-set"),
        )
        val offers = listOf(
            mapOf("title" to "Cashback week", "description" to "Pay with your card on Saturday and Sunday", "image" to offerImage, "endsAt" to "2026-10-31T23:59:59.000Z",
                "discountPercent" to 10, "price" to 80, "isActive" to true, "code" to "WEEKEND20"),
            mapOf("title" to "Free delivery in October", "description" to "Pay with your card on Saturday and Sunday", "image" to offerImage, "endsAt" to "2026-10-31T23:59:59.000Z",
                "discountPercent" to 0, "price" to 45.5, "isActive" to true, "code" to "OCTSHIP"),
        )
        val activities = listOf(
            mapOf("title" to "Coffee week", "points" to 120, "day" to "Today"),
            mapOf("title" to "Movie night", "points" to -300, "day" to "Today"),
            mapOf("title" to "Strong week", "points" to 80, "day" to "Yesterday"),
        )
        val collections = mapOf<String, Any?>("tasks" to tasks, "doneTasks" to doneTasks, "activities" to activities, "taskGroups" to groups, "items" to items, "offers" to offers)
        // The dictionary's path samples first (every path a document may bind), the collections over them.
        val paths = ContractSamples.samples(context).mapValues { it.value }
        return paths + dataScopeOf(*collections.map { it.key to it.value }.toTypedArray())
    }
}
