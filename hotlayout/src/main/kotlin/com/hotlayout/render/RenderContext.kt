package com.hotlayout.render

import androidx.compose.runtime.Stable
import com.hotlayout.DataScope
import com.hotlayout.HotlayoutFonts
import com.hotlayout.ImagePolicy
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.LocaleResolution
import com.hotlayout.actions.ListInstance
import com.hotlayout.actions.WritePress
import com.hotlayout.model.DocumentLocalization
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.ReportSink
import com.hotlayout.model.WidthClass
import com.hotlayout.model.WidthRules
import com.hotlayout.render.motion.VisibilityFadeBoard
import com.hotlayout.render.motion.VisibilityFadeRules
import com.hotlayout.render.widgets.CarouselMemory
import com.hotlayout.render.widgets.ChoiceScope
import com.hotlayout.render.widgets.InputForm
import com.hotlayout.render.widgets.ListBudget
import com.hotlayout.render.widgets.ListNumber
import com.hotlayout.render.widgets.ListPosition
import com.hotlayout.render.widgets.PressRun
import com.hotlayout.text.BindableText
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
 * Stable: two equal contexts draw the same node, so a node whose context and node did not change
 * is skipped when its parent recomposes. The callbacks it carries are the screen's own, equal from
 * one composition to the next, and they always reach the host's latest lambdas.
 *
 * Shared by every widget family: a field belongs here when more than one family needs it.
 */
@Stable
internal data class RenderContext(
    val resolver: StyleResolver,
    val screenId: String,
    val localization: DocumentLocalization?,
    val locale: LocaleResolution,
    val scope: DataScope,
    val timeZone: TimeZone?,
    /** «Now» in epoch milliseconds, read once per frame of the screen. */
    val now: Double,
    /** The host gave «now» itself: it owns the clock, and a time left does not count down on its own. */
    val clockPinned: Boolean = false,
    val rightToLeft: Boolean,
    val inherited: InheritedLook,
    val sink: ReportSink,
    val images: ImagePolicy,
    val fonts: HotlayoutFonts,
    /** Hands a press to the screen together with the list instances the node is drawn inside; tells who runs the action and the host's task. */
    val pressRun: (JsonObject, DocumentNode, List<ListInstance>) -> PressRun,
    /** Writes a value under a data path into the session and to the host; `null` where nobody owns a session. */
    val write: ((String, JsonValue) -> Unit)? = null,
    /** Repeater instances this node is drawn inside, outer first (empty outside a list). */
    val instances: List<ListInstance> = emptyList(),
    /** The root of the screen being drawn and its data layers (the form of the screen: `requiresFilled`). */
    val screenRoot: DocumentNode? = null,
    val screenScope: DataScope? = null,
    /** Counts the shown screens: a busy control ends when it changes. */
    val shownSerial: Int = 0,
    /** The choice group the node is drawn inside (`ChoiceGroup`), or `null`. */
    val choice: ChoiceScope? = null,
    /** The place of the node among its siblings (a numbered list item needs its neighbours); read once by [NodeView]. */
    val listPosition: ListPosition? = null,
    /** The place of this node in a numbered list from data (`Text.marker: number`); `null` outside one. */
    val listNumber: ListNumber? = null,
    /** The top safe inset the app bar of the screen holds inside its box (set by the screen for the bar only). */
    val barInset: Double = 0.0,
    /** The app bar is scrolled: the body is past the threshold (`appBarScrolled`); only the bar itself reads it. */
    val barScrolled: Boolean = false,
    /** The bottom safe inset the navigation bar holds under its items; zero when it floats (the inset lies under its box). */
    val navInset: Double = 0.0,
    /** The line on top of the navigation bar, which the touch area of an item takes in. */
    val navLine: Double = 0.0,
    /** The accessible name of the back arrow of an app bar. */
    val backLabel: String,
    /** The text fields of the screen: the order for the «next» key and their focus (`null` outside a screen). */
    val form: InputForm? = null,
    /** What the carousels of the stack remember, the frame of the stack this screen is, and the indexes of the list instances the node stands in. */
    val carousels: CarouselMemory? = null,
    /** The fades of the nodes of the screens in the stack; `null` outside a screen (a node then follows its condition at once). */
    val fades: VisibilityFadeBoard? = null,
    val frameIndex: Int = 0,
    val instancePath: List<Int> = emptyList(),
    /** The list budget of the screen being drawn (`x-listBudget`); `null` outside a screen: a list then draws every record it would. */
    val listBudget: ListBudget.Plan? = null,
    /** The width of the box of a card of a carousel with `perView`, in logical pixels; read once by [NodeView]. */
    val cardWidth: Double? = null,
    /** The width class of the box of this screen (one per screen; a modal layer is always compact). The tree is already resolved for it. */
    val widthClass: WidthClass = WidthClass.Compact,
    /** `Screen.contentMaxWidth` (already clamped), for the zones whose content stands in a column; `null`: no column. */
    val contentMaxWidth: Double? = null,
    /** The navigation bar whose items stand in the app bar being drawn (`widePlacement`), or `null`. */
    val barNav: DocumentNode? = null,
    /** The navigation items are drawn at the top (in the app bar or a bar of their own): a row of glyph and label. */
    val navAtTop: Boolean = false,
) {
    val theme: HotlayoutTheme get() = resolver.theme

    /**
     * Whether a node is shown: the ONE place that answers it. The width class lists it (`showOn`) AND its
     * condition holds now; the two are independent. A widget never asks either alone.
     */
    fun isShown(node: DocumentNode, scope: DataScope = this.scope): Boolean {
        val condition = WidthRules.isShown(node, widthClass, scope)
        val key = fadeKey(node)
        // A node that fades keeps its place while it fades out and takes it only after the wait (`visibilityTransition`).
        return if (key == null || scope !== this.scope) condition else fades!!.inLayout(key, condition, fadeSignature)
    }

    /** The key of the fade of a node of this screen, or `null` when the node does not fade here (not the field, a zone of the screen). */
    fun fadeKey(node: DocumentNode): String? =
        if (fades != null && VisibilityFadeRules.reads(node, screenRoot)) VisibilityFadeBoard.key(frameIndex, screenId, node.id, instancePath) else null

    /** What a fade was made for: a change of the language or of the width class puts a node at rest at once. */
    val fadeSignature: String get() = "${locale.translation}|$widthClass"

    fun press(action: JsonObject, node: DocumentNode) {
        run(action, node)
    }

    /** A press that also reports who runs the action and the host's task (a swipe button stays busy until it ends). */
    fun run(action: JsonObject, node: DocumentNode): PressRun = pressRun(action, node, instances)

    /** Whether a `Button` / `IconButton` is disabled: `disabledIf` holds, or `requiresFilled` on an unfilled form. */
    fun isDisabled(node: DocumentNode): Boolean = WritePress.isDisabled(node, scope, screenRoot, screenScope ?: scope, widthClass)

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
