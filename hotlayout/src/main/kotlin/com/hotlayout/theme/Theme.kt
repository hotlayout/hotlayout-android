package com.hotlayout.theme

import com.hotlayout.HotlayoutReport
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.HotlayoutVersions
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.ReportSink
import com.hotlayout.model.checkVersion

/** A colour with alpha, components 0…1 (sRGB). */
internal data class Rgba(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {
    internal fun withOpacity(opacity: Double): Rgba = copy(alpha = alpha * opacity)

    /** The colour as a packed ARGB integer. */
    internal fun argb(): Int {
        fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    internal companion object {
        /** `#rrggbb` or `#rrggbbaa`; any other shape is «no colour», never a guess. */
        internal fun hex(text: String): Rgba? {
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
internal data class TextStyleValue(
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

internal data class ShadowLayer(val offsetX: Double, val offsetY: Double, val blur: Double, val spread: Double, val color: Rgba)

internal data class GradientValue(
    /** Degrees as CSS reads them: 0 is «to top», 90 «to right». */
    val angle: Double,
    val stops: List<Stop>,
) {
    internal data class Stop(val color: Rgba, val position: Double)
}

/** The colours of one role (`roles.buttonVariant.primary`): names of theme colours. */
internal data class RoleSlots(val background: String?, val foreground: String?, val border: String?)

/** One theme of a theme contract: six dictionaries and the roles. */
internal data class HotlayoutTheme(
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
    internal fun colorValue(token: String): Rgba? = color[token] ?: OPTIONAL_COLOR_FALLBACKS[token]?.let { color[it] }

    internal companion object {
        internal val OPTIONAL_COLOR_FALLBACKS: Map<String, String> =
            mapOf("locked" to "muted", "inProgress" to "accent", "completed" to "success")
    }
}


/** A number of a theme, an infinite one too (a JSON parser reads `1e309` as infinity): the reader replaces it, it does not skip it. `NaN` cannot come from JSON. */
private val JsonValue?.themeNumber: Double? get() = (this as? JsonValue.Num)?.value?.takeUnless { it.isNaN() }

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

        /**
         * A number outside its bounds (or not finite) is replaced: by the value of the same token and field of the built-in
         * theme of the same brightness when there is one, otherwise by the nearest bound; the replacement is reported
         * (`x-themeNumbers`). A number inside the bounds stays.
         */
        fun bounded(number: Double, range: ClosedFloatingPointRange<Double>, builtIn: Double?, where: String): Double {
            if (number in range) return number
            val borrowed = builtIn?.takeIf { it in range }
            val value = borrowed ?: if (number.isNaN()) range.start else number.coerceIn(range.start, range.endInclusive)
            ignored(where, "a number outside the bounds of the theme schema: replaced by ${if (borrowed != null) "the built-in value" else "the nearest bound"} ($value)")
            return value
        }

        for ((name, value) in theme["spacing"]?.obj ?: JsonObject()) {
            val number = value.themeNumber
            if (number != null) spacing[name] = bounded(number, ThemeBounds.SPACING, fallback?.spacing?.get(name), "spacing.$name") else ignored("spacing.$name", "not a length of 0…128")
        }
        for ((name, value) in theme["radius"]?.obj ?: JsonObject()) {
            val number = value.themeNumber
            if (number != null) radius[name] = bounded(number, ThemeBounds.RADIUS, fallback?.radius?.get(name), "radius.$name") else ignored("radius.$name", "not a length of 0…256")
        }
        for ((name, value) in theme["color"]?.obj ?: JsonObject()) {
            val parsed = value.string?.let(Rgba::hex)
            if (parsed != null) color[name] = parsed else ignored("color.$name", "not a color")
        }
        for ((name, value) in theme["typography"]?.obj ?: JsonObject()) {
            val rawSize = value["fontSize"]?.themeNumber
            val rawLine = value["lineHeight"]?.themeNumber
            if (value.obj == null || rawSize == null || rawLine == null) {
                ignored("typography.$name", "not a text style")
                continue
            }
            val builtIn = fallback?.typography?.get(name)
            val at = "typography.$name"
            val weight = bounded(value["fontWeight"]?.themeNumber ?: 400.0, ThemeBounds.FONT_WEIGHT, builtIn?.fontWeight?.toDouble(), "$at.fontWeight")
            // The built-in styles carry no letter spacing: an out-of-bounds one has no built-in value to take.
            val tracking = bounded(value["letterSpacing"]?.themeNumber ?: 0.0, ThemeBounds.LETTER_SPACING, null, "$at.letterSpacing")
            val size = bounded(rawSize, ThemeBounds.FONT_SIZE, builtIn?.fontSize, "$at.fontSize")
            val line = bounded(rawLine, ThemeBounds.LINE_HEIGHT, builtIn?.lineHeight, "$at.lineHeight")
            // The step of the weight is a rule of the theme schema, not of the bounds: a weight off the step is still no style.
            if (weight % 100.0 != 0.0) {
                ignored(at, "a font weight that is not a multiple of 100")
                continue
            }
            typography[name] = TextStyleValue(
                fontSize = size,
                lineHeight = line,
                fontWeight = weight.toInt(),
                letterSpacing = tracking,
                role = value["role"]?.string ?: "body",
            )
        }
        for ((name, value) in theme["elevation"]?.obj ?: JsonObject()) {
            val layers = value.array ?: continue
            // A part of a shadow borrowed from another shadow would be a third value nobody wrote: a layer number takes the bound.
            val read = layers.mapIndexedNotNull { index, layer ->
                val shade = layer["color"]?.string?.let(Rgba::hex) ?: return@mapIndexedNotNull null
                if (layer.obj == null) return@mapIndexedNotNull null
                val at = "elevation.$name[$index]"
                val offsetX = bounded(layer["offsetX"]?.themeNumber ?: 0.0, ThemeBounds.SHADOW_OFFSET, null, "$at.offsetX")
                val offsetY = bounded(layer["offsetY"]?.themeNumber ?: 0.0, ThemeBounds.SHADOW_OFFSET, null, "$at.offsetY")
                val blur = bounded(layer["blur"]?.themeNumber ?: 0.0, ThemeBounds.SHADOW_BLUR, null, "$at.blur")
                val spread = bounded(layer["spread"]?.themeNumber ?: 0.0, ThemeBounds.SHADOW_OFFSET, null, "$at.spread")
                val opacity = bounded(layer["opacity"]?.themeNumber ?: 1.0, ThemeBounds.UNIT, null, "$at.opacity")
                ShadowLayer(offsetX, offsetY, blur, spread, shade.withOpacity(opacity))
            }
            elevation[name] = read
        }
        for ((name, value) in theme["gradient"]?.obj ?: JsonObject()) {
            val angle = value["angle"]?.themeNumber ?: continue
            if (angle !in ThemeBounds.ANGLES) {
                ignored("gradient.$name", "an angle that is not one of the schema's")
                continue
            }
            val stops = value["stops"]?.array.orEmpty().mapIndexedNotNull { index, stop ->
                val shade = stop["color"]?.string?.let(Rgba::hex) ?: return@mapIndexedNotNull null
                val position = stop["position"]?.themeNumber ?: return@mapIndexedNotNull null
                GradientValue.Stop(shade, bounded(position, ThemeBounds.UNIT, null, "gradient.$name.stops[$index].position"))
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

/** The bounds of every number of a theme: `theme.json` `x-schema`. A number outside them (or not finite) is replaced (`x-themeNumbers`). */
internal object ThemeBounds {
    val SPACING = 0.0..128.0
    val RADIUS = 0.0..256.0
    val FONT_SIZE = 8.0..96.0
    val LINE_HEIGHT = 0.8..3.0
    val LETTER_SPACING = -2.0..8.0
    val FONT_WEIGHT = 100.0..900.0
    val SHADOW_OFFSET = -64.0..64.0
    val SHADOW_BLUR = 0.0..128.0
    val UNIT = 0.0..1.0
    val ANGLES = setOf(0.0, 90.0, 180.0, 270.0, 45.0, 135.0, 225.0, 315.0)
}
