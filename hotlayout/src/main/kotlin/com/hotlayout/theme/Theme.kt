package com.hotlayout.theme

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentReader
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.model.HotlayoutVersions
import com.hotlayout.model.ReportSink
import com.hotlayout.model.checkVersion

/** A colour with alpha, components 0…1 (sRGB). */
public data class Rgba(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {
    public fun withOpacity(opacity: Double): Rgba = copy(alpha = alpha * opacity)

    /** The colour as a packed ARGB integer. */
    public fun argb(): Int {
        fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    public companion object {
        /** `#rrggbb` or `#rrggbbaa`; any other shape is «no colour», never a guess. */
        public fun hex(text: String): Rgba? {
            if (!text.startsWith("#") || (text.length != 7 && text.length != 9)) return null
            val values = ArrayList<Double>()
            var index = 1
            while (index < text.length) {
                val pair = text.substring(index, index + 2)
                if (!pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                values.add(pair.toInt(16) / 255.0)
                index += 2
            }
            return Rgba(values[0], values[1], values[2], if (values.size == 4) values[3] else 1.0)
        }
    }
}

/**
 * A text style of the theme: size and spacing in logical pixels, line height a multiple of the
 * size, no letter spacing is zero.
 */
public data class TextStyleValue(
    val fontSize: Double,
    val lineHeight: Double,
    val fontWeight: Int,
    val letterSpacing: Double = 0.0,
    /** `heading` or `body`: which font family the style uses. */
    val role: String = "body",
) {
    /** The line box the contract asks for (`lineHeight` × size). */
    val lineBox: Double get() = fontSize * lineHeight
}

public data class ShadowLayer(val offsetX: Double, val offsetY: Double, val blur: Double, val spread: Double, val color: Rgba)

public data class GradientValue(
    /** Degrees as CSS reads them: 0 is «to top», 90 «to right». */
    val angle: Double,
    val stops: List<Stop>,
) {
    public data class Stop(val color: Rgba, val position: Double)
}

/** The colours of one role (`roles.buttonVariant.primary`): names of theme colours. */
public data class RoleSlots(val background: String?, val foreground: String?, val border: String?)

/** One theme of a theme contract: six dictionaries and the roles. */
public data class HotlayoutTheme(
    val id: String,
    val brightness: String,
    val spacing: Map<String, Double>,
    val radius: Map<String, Double>,
    val color: Map<String, Rgba>,
    val typography: Map<String, TextStyleValue>,
    val elevation: Map<String, List<ShadowLayer>>,
    val gradient: Map<String, GradientValue>,
    val fontFamilies: Map<String, String>,
    val roles: Map<String, Map<String, RoleSlots>>,
) {
    /**
     * The colour behind a name, with the optional state colours falling back to the token they
     * borrowed before they had a name (`x-optionalColors`).
     */
    public fun colorValue(token: String): Rgba? = color[token] ?: OPTIONAL_COLOR_FALLBACKS[token]?.let { color[it] }

    public companion object {
        public val OPTIONAL_COLOR_FALLBACKS: Map<String, String> =
            mapOf("locked" to "muted", "inProgress" to "accent", "completed" to "success")
    }
}

/** A theme contract: a set of themes and the one a reader takes by default. */
public data class HotlayoutThemeSet(
    val themeContractVersion: String,
    val defaultThemeId: String,
    val themes: Map<String, HotlayoutTheme>,
) {
    /** The theme the reader draws with: the requested one, else the default, else any. */
    public fun theme(id: String? = null): HotlayoutTheme =
        id?.let { themes[it] } ?: themes[defaultThemeId] ?: themes.values.firstOrNull() ?: builtIn.themes.values.first()

    public companion object {
        /** The theme this reader ships with: the `contract` block of `theme.json`. */
        public val builtIn: HotlayoutThemeSet by lazy {
            val bytes = HotlayoutThemeSet::class.java.getResourceAsStream("/com/hotlayout/built-in-theme.json")?.use { it.readBytes() }
                ?: error("HotLayout: the built-in theme is missing from the library resources")
            ThemeReader.read(JsonValue.parse(bytes), null, ReportSink()) ?: error("HotLayout: the built-in theme does not read")
        }

        /**
         * Reads a theme contract from the network. A newer minor's fields are skipped; a missing
         * entry is filled from the built-in theme of the same brightness; a theme that cannot be
         * read at all gives `null` (draw with [builtIn] and log the reports).
         */
        public fun read(json: JsonValue, sink: ReportSink = ReportSink()): HotlayoutThemeSet? =
            ThemeReader.read(DocumentReader.withoutForbiddenKeys(json), builtIn, sink)

        public fun read(text: String, sink: ReportSink = ReportSink()): HotlayoutThemeSet? =
            runCatching { JsonValue.parse(text) }.getOrNull()?.let { read(it, sink) }
    }
}

internal object ThemeReader {
    fun read(json: JsonValue, base: HotlayoutThemeSet?, sink: ReportSink): HotlayoutThemeSet? {
        val themesObject = json["themes"]?.obj
        if (json.obj == null || themesObject == null) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "theme", "the theme is not a theme contract; the built-in theme is used")
            return null
        }
        json["themeContractVersion"]?.string?.let { version ->
            if (!checkVersion(version, HotlayoutVersions.THEME).isReadable) {
                sink.add(HotlayoutReport.Kind.IgnoredInput, "theme", "theme contract $version is not readable by this reader; the built-in theme is used")
                return null
            }
        }
        val themes = LinkedHashMap<String, HotlayoutTheme>()
        for ((id, value) in themesObject) {
            val theme = value.obj
            if (theme == null) {
                sink.add(HotlayoutReport.Kind.IgnoredInput, "theme “$id”", "a theme that is not an object is skipped")
                continue
            }
            val brightness = if (theme["brightness"]?.string == "dark") "dark" else "light"
            val fallback = base?.themes?.values?.firstOrNull { it.brightness == brightness } ?: base?.theme()
            themes[id] = readTheme(id, theme, brightness, fallback, sink)
        }
        if (themes.isEmpty()) return null
        val defaultId = json["defaultThemeId"]?.string?.takeIf { themes.containsKey(it) } ?: themes.keys.sorted().first()
        return HotlayoutThemeSet(json["themeContractVersion"]?.string ?: HotlayoutVersions.THEME, defaultId, themes)
    }

    private fun readTheme(id: String, theme: JsonObject, brightness: String, fallback: HotlayoutTheme?, sink: ReportSink): HotlayoutTheme {
        val spacing = LinkedHashMap(fallback?.spacing.orEmpty())
        val radius = LinkedHashMap(fallback?.radius.orEmpty())
        val color = LinkedHashMap(fallback?.color.orEmpty())
        val typography = LinkedHashMap(fallback?.typography.orEmpty())
        val elevation = LinkedHashMap(fallback?.elevation.orEmpty())
        val gradient = LinkedHashMap(fallback?.gradient.orEmpty())
        val families = LinkedHashMap(fallback?.fontFamilies.orEmpty())
        val roles = LinkedHashMap(fallback?.roles.orEmpty())
        val place = "theme “$id”"
        fun ignored(where: String, reason: String) = sink.add(HotlayoutReport.Kind.IgnoredInput, "$place: $where", reason)

        for ((name, value) in theme["spacing"]?.obj ?: JsonObject()) {
            val number = value.number
            if (number != null && number >= 0) spacing[name] = number else ignored("spacing.$name", "not a length")
        }
        for ((name, value) in theme["radius"]?.obj ?: JsonObject()) {
            val number = value.number
            if (number != null && number >= 0) radius[name] = number else ignored("radius.$name", "not a length")
        }
        for ((name, value) in theme["color"]?.obj ?: JsonObject()) {
            val parsed = value.string?.let(Rgba::hex)
            if (parsed != null) color[name] = parsed else ignored("color.$name", "not a color")
        }
        for ((name, value) in theme["typography"]?.obj ?: JsonObject()) {
            val size = value["fontSize"]?.number
            val line = value["lineHeight"]?.number
            if (value.obj == null || size == null || line == null) {
                ignored("typography.$name", "not a text style")
                continue
            }
            typography[name] = TextStyleValue(
                fontSize = size,
                lineHeight = line,
                fontWeight = (value["fontWeight"]?.number ?: 400.0).toInt(),
                letterSpacing = value["letterSpacing"]?.number ?: 0.0,
                role = value["role"]?.string ?: "body",
            )
        }
        for ((name, value) in theme["elevation"]?.obj ?: JsonObject()) {
            val layers = value.array ?: continue
            elevation[name] = layers.mapNotNull { layer ->
                val shade = layer["color"]?.string?.let(Rgba::hex) ?: return@mapNotNull null
                if (layer.obj == null) return@mapNotNull null
                ShadowLayer(
                    layer["offsetX"]?.number ?: 0.0, layer["offsetY"]?.number ?: 0.0,
                    layer["blur"]?.number ?: 0.0, layer["spread"]?.number ?: 0.0,
                    shade.withOpacity(layer["opacity"]?.number ?: 1.0),
                )
            }
        }
        for ((name, value) in theme["gradient"]?.obj ?: JsonObject()) {
            val angle = value["angle"]?.number ?: continue
            val stops = value["stops"]?.array.orEmpty().mapNotNull { stop ->
                val shade = stop["color"]?.string?.let(Rgba::hex) ?: return@mapNotNull null
                val position = stop["position"]?.number ?: return@mapNotNull null
                GradientValue.Stop(shade, position)
            }
            if (stops.size >= 2) gradient[name] = GradientValue(angle, stops)
        }
        for ((name, value) in theme["fontFamilies"]?.obj ?: JsonObject()) value["primary"]?.string?.let { families[name] = it }
        for ((group, value) in theme["roles"]?.obj ?: JsonObject()) {
            val entries = LinkedHashMap(roles[group].orEmpty())
            for ((name, slots) in value.obj ?: JsonObject()) {
                entries[name] = RoleSlots(slots["background"]?.string, slots["foreground"]?.string, slots["border"]?.string)
            }
            roles[group] = entries
        }
        return HotlayoutTheme(id, brightness, spacing, radius, color, typography, elevation, gradient, families, roles)
    }
}
