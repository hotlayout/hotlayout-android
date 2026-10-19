package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.StyledText
import com.hotlayout.render.color
import com.hotlayout.render.composeSpanStyle
import com.hotlayout.render.composeTextStyle
import com.hotlayout.render.cssLinearGradient
import com.hotlayout.render.motion.CountdownTicking
import com.hotlayout.render.motion.ValueTransitioned
import com.hotlayout.render.textAlign
import com.hotlayout.text.BindableText
import com.hotlayout.theme.GradientValue
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.StyleResolver
import com.hotlayout.theme.TextStyleValue

// `Text` with parts of its own style, emphasis, a list marker or a gradient on the glyphs. A text
// with none of these is drawn as one plain run. The rules are in `TextRichRules.kt`.

// ---- Siblings of a list item ------------------------------------------------------------------

/** The markers and the visibility of the children of ONE parent, in document order: what a numbered item needs to know about its neighbours. */
internal class ListSiblings(children: List<DocumentNode>, context: RenderContext) {
    val markers: List<DrawnMarker?> = children.map { if (it.type == "Text") ListMarkerRules.drawn(it.props["marker"]) else null }
    val visible: List<Boolean> = children.map { context.isShown(it) }
}

internal class ListPosition(private val siblings: ListSiblings, private val index: Int) {
    val number: Int? get() = ListMarkerRules.number(siblings.markers, index, siblings.visible)
    val groupSize: Int? get() = ListMarkerRules.groupSize(siblings.markers, index)
}

/** The number of a list item that is the template of a data list, and the size of its group (the drawn records). */
internal data class ListNumber(val number: Int, val groupSize: Int)

/** The context for the child at [index] among [siblings]. */
internal fun RenderContext.at(siblings: ListSiblings, index: Int): RenderContext = copy(listPosition = ListPosition(siblings, index))

// ---- What is drawn ----------------------------------------------------------------------------

/** A run with its look resolved: the typography with the weight of the part, the colour and the emphasis. */
internal data class DrawnRun(val text: String, val typography: TextStyleValue, val color: Rgba, val namesColor: Boolean, val emphasis: RunEmphasis)

/** Everything the drawing of a paragraph needs, resolved against the theme. */
internal class TextRichPlan(val runs: List<DrawnRun>, val base: TextStyleValue, val lineBlock: Double) {
    companion object {
        fun make(
            templateRuns: List<com.hotlayout.text.TemplateRun>, active: Map<String, TextSpanStyle>, base: TextStyleValue,
            color: Rgba, emphasis: TextEmphasis, resolver: StyleResolver,
        ): TextRichPlan {
            val merged = TextSpans.shown(TextSpans.runs(templateRuns, active))
            val drawn = merged.map { run ->
                val span = TextSpans.style(run, active)
                val runEmphasis = TextEmphasisRules.run(emphasis, span)
                var typography = span?.typography?.let(resolver::typography) ?: base
                runEmphasis.weight?.let { typography = typography.copy(fontWeight = it) }
                val own = span?.color?.let(resolver::color)
                DrawnRun(run.text, typography, own ?: color, span?.namesColor ?: false, runEmphasis)
            }
            // The pitch counts every declared part, shown or not.
            val metrics = LinkedHashMap<String, LineMetric>()
            for ((key, style) in TextSpans.declared(active)) {
                val typography = style.typography?.let(resolver::typography) ?: base
                metrics[key] = LineMetric(typography.fontSize, typography.lineHeight)
            }
            val strut = RichText.strut(LineMetric(base.fontSize, base.lineHeight), metrics)
            return TextRichPlan(drawn, base, strut.lineBlock)
        }
    }
}

// ---- The widget -------------------------------------------------------------------------------

@Composable
internal fun TextContent(call: WidgetCall, context: RenderContext) {
    val node = call.node
    val marker = ListMarkerRules.drawn(node.props["marker"])
    val style = NodeStyle.effective(node, context.scope)
    val gradient = TextGradientRules.reference(node.type, style)?.let(context.resolver::gradient)
    val active = TextSpans.active(node.props["content"], TextSpans.read(node.props["spans"]))
    val align = node.props["align"]?.string
    val maxLines = if (node.props["overflow"]?.string == "visible") Int.MAX_VALUE else node.props["maxLines"]?.number?.coerceIn(1.0, 20.0)?.toInt() ?: Int.MAX_VALUE
    val modifier = if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier
    if (marker == null && active.isEmpty() && gradient == null) {
        StyledText(
            text = context.text(node, "content"), style = context.inherited.typography, color = context.inherited.color, context = context,
            modifier = modifier, italic = context.inherited.italic, decoration = context.inherited.decoration, align = textAlign(align), maxLines = maxLines,
        )
        return
    }
    val location = "screen “${context.screenId}”: node “${node.id}”: content"
    val runs = BindableText.runs(node.props["content"], context.language(node, "content"), context.textEnvironment, context.sink, location)
    val emphasis = TextEmphasis(if (context.inherited.italic) "italic" else "normal", context.inherited.decoration ?: "none")
    val plan = TextRichPlan.make(runs, active, context.inherited.typography, context.inherited.color, emphasis, context.resolver)
    if (marker == null) {
        RichParagraph(plan, context, gradient, textAlign(align), maxLines, modifier)
        return
    }
    val position = call.position
    val number = call.number?.number ?: position?.number ?: 1
    val groupSize = call.number?.groupSize ?: position?.groupSize ?: 1
    MarkerRow(marker, plan, context, gradient, number, groupSize, textAlign(align), maxLines, modifier)
}

/**
 * The item: a column for the marker at the start, the gap, and the text hanging under its own
 * first line. The marker is drawn in the font of the text, without slant or line, pushed to the end
 * of its column; the screen reader hears the text only.
 */
@Composable
private fun MarkerRow(
    marker: DrawnMarker, plan: TextRichPlan, context: RenderContext, gradient: GradientValue?,
    number: Int, groupSize: Int, align: TextAlign, maxLines: Int, modifier: Modifier,
) {
    val geometry = ListMarkerRules.geometry(marker, plan.base.fontSize, groupSize)
    val glyph = ListMarkerRules.text(marker, number, context.locale.formatting)
    val glyphStyle = composeTextStyle(plan.base, context.inherited.color, context, align = TextAlign.End, lineBlock = plan.lineBlock)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(geometry.column.dp).clearAndSetSemantics { testTag = "marker:$glyph" }, contentAlignment = Alignment.TopEnd) {
            BasicText(AnnotatedString(glyph), style = glyphStyle, maxLines = 1, softWrap = false)
        }
        RichParagraph(plan, context, gradient, align, maxLines, Modifier.weight(1f))
    }
}

/** A CSS gradient over the box of the text it paints: one transition across every line. */
private fun gradientBrush(gradient: GradientValue, rightToLeft: Boolean): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = (cssLinearGradient(gradient, size, rightToLeft) as ShaderBrush).createShader(size)
}

/**
 * A paragraph of runs on one baseline, every line at the pitch of the tallest declared part. A
 * run that takes the gradient is painted by a brush; a part with a colour of its own keeps it.
 */
@Composable
private fun RichParagraph(plan: TextRichPlan, context: RenderContext, gradient: GradientValue?, align: TextAlign, maxLines: Int, modifier: Modifier) {
    val brush = gradient?.let { gradientBrush(it, context.rightToLeft) }
    val styles = plan.runs.map { run ->
        val takes = brush != null && !run.namesColor
        composeSpanStyle(run.typography, run.color, context, run.emphasis.fontStyle, run.emphasis.textDecoration, if (takes) brush else null)
    }
    val text = buildAnnotatedString { plan.runs.forEachIndexed { index, run -> withStyle(styles[index]) { append(run.text) } } }
    val whole = plan.runs.joinToString("") { it.text }
    BasicText(
        text = text,
        modifier = modifier.semantics { this.text = AnnotatedString(whole) },
        style = composeTextStyle(plan.base, context.inherited.color, context, align = align, lineBlock = plan.lineBlock),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
