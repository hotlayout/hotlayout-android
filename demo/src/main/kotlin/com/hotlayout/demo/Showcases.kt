package com.hotlayout.demo

import android.content.Context
import com.hotlayout.DataScope
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.ImagePolicy
import com.hotlayout.JsonValue
import com.hotlayout.loadDocument

/**
 * The three showcase apps of the product site (Obsidian, Sunny, Orbit): a document, its theme and the answer of a
 * pretend backend, bundled under `assets/showcase/<app>/`. They are opened like fixtures, by the names
 * `showcase-obsidian`, `showcase-sunny` and `showcase-orbit`.
 */
object Showcases {
    const val FIRST = "showcase-obsidian"

    /** The address the site-relative pictures of the documents (`/landing/demo/…`) are served from. */
    private const val SITE = "https://hotlayout.com"

    val names: List<String> = listOf("showcase-obsidian", "showcase-sunny", "showcase-orbit")

    fun isShowcase(name: String): Boolean = name in names

    private fun app(name: String) = name.removePrefix("showcase-")

    private fun read(context: Context, path: String): JsonValue = JsonValue.parse(context.assets.open("showcase/$path").use { it.readBytes() })

    fun document(context: Context, name: String, screenId: String?): HotlayoutDocument {
        var json = read(context, "${app(name)}/document.json")
        if (screenId != null) json = JsonValue.Obj(json.obj!!.with("initialScreenId", JsonValue.Str(screenId)))
        return loadDocument(json)
    }

    fun theme(context: Context, name: String): HotlayoutThemeSet =
        HotlayoutThemeSet.read(context.assets.open("showcase/${app(name)}/theme.json").use { it.readBytes().decodeToString() }) ?: HotlayoutThemeSet.builtIn

    /** Whether the default theme of the app is dark: the status bar icons are light until a screen asks otherwise. */
    fun isDark(context: Context, name: String): Boolean {
        val set = read(context, "${app(name)}/theme.json")
        val id = set["defaultThemeId"]?.string
        return set["themes"]?.get(id ?: "")?.get("brightness")?.string == "dark"
    }

    /** The moment «now» of the app: the days counted to a date do not depend on the day the demo runs. */
    fun now(context: Context, name: String): Long {
        val root = read(context, if (name == "showcase-orbit") "orbit/sample-data.json" else "${app(name)}/data.json")
        return java.time.Instant.parse(root["now"]!!.string!!).toEpochMilli()
    }

    /**
     * What the backend answers: the values of the data paths and the collections of the lists. Obsidian and Sunny answer
     * per language, Orbit per [scenario] (`default`, `newMember`, `afterPayment`).
     */
    fun data(context: Context, name: String, locale: String?, scenario: String?): DataScope {
        val root = read(context, if (name == "showcase-orbit") "orbit/sample-data.json" else "${app(name)}/data.json")
        val answer = if (name == "showcase-orbit") {
            root["scenarios"]!![scenario ?: "default"] ?: root["scenarios"]!!["default"]!!
        } else {
            val language = locale?.substringBefore('-')?.substringBefore('_')
            root[language ?: "en"] ?: root["en"]!!
        }
        val scope = LinkedHashMap<String, JsonValue>()
        answer["data"]?.obj?.forEach { (path, value) -> scope[path] = value }
        answer["collections"]?.obj?.forEach { (collection, value) -> scope[collection] = value }
        return scope
    }

    /** The pictures of the apps are on the site; a document names them by a site-relative or an absolute `https:` address. */
    fun images(context: Context): ImagePolicy = ImagePolicy(resolve = { address ->
        ImagePolicy.httpsOnly(if (address.startsWith("/")) SITE + address else address)
    })
}
