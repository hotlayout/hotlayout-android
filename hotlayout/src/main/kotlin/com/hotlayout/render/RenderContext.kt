package com.hotlayout.render

import androidx.compose.ui.text.font.FontFamily
import com.hotlayout.actions.ListInstance
import com.hotlayout.data.Conditions
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentLocalization
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.ReportSink
import com.hotlayout.text.BindableText
import com.hotlayout.text.LocaleResolution
import com.hotlayout.text.Localization
import com.hotlayout.text.ShownText
import com.hotlayout.text.TemplateLanguage
import com.hotlayout.text.TextEnvironment
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.InheritedLook
import com.hotlayout.theme.StyleResolver
import java.util.TimeZone

/**
 * What every node of a screen needs to draw: the theme, the data, the language and the way back to
 * the screen for presses. Passed down explicitly, one value per node.
 *
 * This file is shared by every widget family: add a field only when more than one family needs
 * it, and keep the defaults so that existing call sites do not change.
 */
internal data class RenderContext(
    val resolver: StyleResolver,
    val screenId: String,
    val localization: DocumentLocalization?,
    val locale: LocaleResolution,
    val scope: DataScope,
    val timeZone: TimeZone?,
    /** «Now» in epoch milliseconds, read once per frame of the screen. */
    val now: Double,
    val rightToLeft: Boolean,
    val inherited: InheritedLook,
    val sink: ReportSink,
    val images: ImagePolicy,
    val fonts: HotlayoutFonts,
    /** Hands a press to the screen together with the list instances the node is drawn inside. */
    val pressHandler: (JsonObject, DocumentNode, List<ListInstance>) -> Unit,
    /** Writes a value under a data path into the session and to the host; `null` where nobody owns a session. */
    val write: ((String, JsonValue) -> Unit)? = null,
    /** Repeater instances this node is drawn inside, outer first (empty outside a list). */
    val instances: List<ListInstance> = emptyList(),
    /** The place of this node in a numbered list from data (`Text.marker: number`); `null` outside one. */
    val listNumber: com.hotlayout.render.widgets.ListNumber? = null,
) {
    val theme: HotlayoutTheme get() = resolver.theme

    /**
     * Whether a node is shown: the ONE place that answers it. Its condition holds now; the width
     * class (`showOn`) joins here, never in a widget.
     */
    fun isShown(node: DocumentNode, scope: DataScope = this.scope): Boolean = Conditions.isVisible(node.visibleIf, scope)

    fun press(action: JsonObject, node: DocumentNode) = pressHandler(action, node, instances)

    val textEnvironment: TextEnvironment
        get() = TextEnvironment(
            scope = scope,
            formattingLocale = locale.formatting,
            fallbackLocale = locale.translation ?: locale.directionLocale,
            timeZone = timeZone,
            now = now,
            rightToLeft = rightToLeft,
        )

    /** The language of one string field: translations live under `<screenId>/<nodeId>/<field>` and `<key>.<path>`. */
    fun language(node: DocumentNode, field: String): TemplateLanguage {
        val key = Localization.key(screenId, node.id, field)
        val localization = localization
        val translation = locale.translation
        return TemplateLanguage(translation, localization?.defaultLocale) { sub ->
            Localization.translate(localization, translation, if (sub.isEmpty()) key else "$key.$sub")
        }
    }

    /** The SHOWN text of a string field (`x-style.layout.shownText`). */
    fun text(node: DocumentNode, field: String): String = ShownText.of(
        BindableText.text(node.props[field], language(node, field), textEnvironment, sink, "screen “$screenId”: node “${node.id}”: $field"),
    )

    fun location(node: DocumentNode): String = "screen “$screenId”: node “${node.id}”"
}

/** Which image addresses the reader may load. The host decides; by default only `https:`. */
public class ImagePolicy(
    /** Addresses of the host's asset tokens (`logo`, `banner`, `placeholder`). */
    public val assets: Map<String, String> = emptyMap(),
    /** Turns an address of the document into one to load, or `null` to draw the placeholder. */
    public val resolve: (String) -> String? = ::httpsOnly,
) {
    public companion object {
        public fun httpsOnly(address: String): String? {
            if (!address.startsWith("https://") || address.length > 2048) return null
            val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return null
            return address.takeIf { uri.scheme == "https" && !uri.host.isNullOrEmpty() }
        }
    }
}

/**
 * The fonts of the host app: the theme names a family (`fontFamilies.body.primary`, «Inter»), the
 * library ships no fonts. A family the host does not give is drawn with the system font.
 */
public fun interface HotlayoutFonts {
    public fun family(name: String): FontFamily?

    public companion object {
        public val System: HotlayoutFonts = HotlayoutFonts { null }
    }
}

/** How a parent places a child: stretched to its width or at its natural width. */
internal data class Placement(val stretchWidth: Boolean) {
    companion object {
        val Natural = Placement(false)
        val Stretched = Placement(true)
    }
}

/** Whether a child of a column keeps its natural width (`x-style.layout.column.naturalWidth`). */
internal fun takesNaturalWidth(node: DocumentNode): Boolean {
    if (node.style?.get("width") != null) return true
    return when (node.type) {
        "Button" -> node.props["fullWidth"]?.bool == false
        "IconButton", "Badge", "Icon", "CircularProgress" -> true
        "Divider", "Spacer" -> node.props["orientation"]?.string == "vertical"
        else -> false
    }
}
