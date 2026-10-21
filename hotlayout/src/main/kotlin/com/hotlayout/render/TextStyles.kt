package com.hotlayout.render

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import com.hotlayout.render.dp
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue
import kotlin.math.ceil

/** The text alignment of a word of the contract: `start`, `center`, `end`; anything else is `start`. */
internal fun textAlign(word: String?): TextAlign = when (word) {
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    else -> TextAlign.Start
}

/**
 * A theme text style as a Compose style: the family the theme names (from the host's fonts), the
 * size, weight and letter spacing in logical pixels, the line box of the contract (`lineHeight` ×
 * size, shared equally above and below the glyphs), and the direction of the paragraph from the
 * LANGUAGE of the shown text, never from its first strong letter.
 *
 * Sizes are logical pixels, not scaled by the system font size: every reader of the contract
 * draws the same layout for the same document.
 */
@Composable
internal fun composeTextStyle(
    style: TextStyleValue,
    color: Rgba,
    context: RenderContext,
    italic: Boolean = false,
    decoration: String? = null,
    align: TextAlign = TextAlign.Start,
    /** The pitch of every line, when it is not the one of the style (a composed text takes its tallest part's). */
    lineBlock: Double? = null,
): TextStyle {
    val density = LocalDensity.current
    val familyName = context.theme.fontFamilies[if (style.role == "heading") "heading" else "body"]
    val family = familyName?.let(context.fonts::family) ?: FontFamily.Default
    return with(density) {
        TextStyle(
            color = color.color,
            fontSize = style.fontSize.toFloat().dp.toSp(),
            fontWeight = FontWeight(style.fontWeight.coerceIn(1, 1000)),
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = family,
            letterSpacing = style.letterSpacing.signedDp.toSp(),
            lineHeight = (lineBlock ?: style.lineBox).toFloat().dp.toSp(),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            textAlign = align,
            textDirection = if (context.rightToLeft) TextDirection.Rtl else TextDirection.Ltr,
            textDecoration = when (decoration) {
                "underline" -> TextDecoration.Underline
                "lineThrough" -> TextDecoration.LineThrough
                else -> null
            },
        )
    }
}

/**
 * Holds the line count of the text it wraps and keeps its height at the line box of the contract. The platform
 * adds the metrics of a fallback font (Arabic, when the theme's family has no Arabic letters) to the line AFTER
 * the line height has been applied, so a line of Arabic grows far past `lineHeight × size` and every screen in
 * Arabic would be taller than in English. The box is cut back to `lines × ceil(line pitch)` — what Latin text
 * measures to — and the drawing is shifted by half of the surplus, so the glyphs stay centred in their line.
 */
internal class LineBoxKeeper(private val lineBoxDp: Double) {
    private var lines = 1

    val onTextLayout: (TextLayoutResult) -> Unit = { lines = it.lineCount.coerceAtLeast(1) }

    val modifier: Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val pitch = ceil(lineBoxDp.toFloat().dp.toPx())
        val target = (pitch * lines).toInt()
        val height = if (placeable.height > target) target.coerceIn(constraints.minHeight, constraints.maxHeight) else placeable.height
        val lifted = (placeable.height - height) / 2
        layout(placeable.width, height) { placeable.place(0, -lifted) }
    }
}

/**
 * A one-line text that must show whole (the trailing column of a row, a list marker). With `softWrap = false` the
 * platform lays the line out at the rounded-up intrinsic width, and in a right-to-left paragraph the shaped line is
 * a fraction of a pixel wider than that: the last character wrapped to a second line and was cut («+20» → «…+»,
 * «٣.» → «٣»). The text is laid out at its intrinsic width plus a little slack instead.
 */
internal fun Modifier.neverNarrowerThanItsLine(endAligned: Boolean = false): Modifier = layout { measurable, constraints ->
    val intrinsic = measurable.maxIntrinsicWidth(Constraints.Infinity)
    val slack = 2.dp.roundToPx()
    val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - intrinsic).coerceIn(0, slack) else slack
    val placeable = measurable.measure(Constraints(minWidth = intrinsic + room, maxWidth = intrinsic + room, minHeight = 0, maxHeight = constraints.maxHeight))
    // The slack is not part of the box: the text keeps the place it had, the spare room lies on the side the text is
    // not drawn on (right for a text at the start of a left-to-right line or at the end of a right-to-left one).
    val textOnTheRight = (layoutDirection == LayoutDirection.Rtl) != endAligned
    layout(intrinsic, placeable.height) { placeable.place(if (textOnTheRight) -room else 0, 0) }
}

/** A run of text in one style; [maxLines] cut with an ellipsis. */
@Composable
internal fun StyledText(
    text: String,
    style: TextStyleValue,
    color: Rgba,
    context: RenderContext,
    modifier: Modifier = Modifier,
    italic: Boolean = false,
    decoration: String? = null,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    /** `false` for a text that must never shrink or break (the trailing column of a row): it takes the width it needs. */
    softWrap: Boolean = true,
) {
    val keeper = remember(style.lineBox) { LineBoxKeeper(style.lineBox) }
    BasicText(
        text = AnnotatedString(text),
        modifier = modifier.then(if (softWrap) Modifier else Modifier.neverNarrowerThanItsLine(align == TextAlign.End)).then(keeper.modifier).semantics { this.text = AnnotatedString(text) },
        style = composeTextStyle(style, color, context, italic, decoration, align),
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = keeper.onTextLayout,
    )
}

/**
 * The style of one run of a composed text: its own typography (size, weight, spacing, family), the
 * colour or the gradient brush, the slant and the line of the run. Every field is set, so the run
 * never takes the slant or the line of the paragraph.
 */
@Composable
internal fun composeSpanStyle(
    style: TextStyleValue,
    color: Rgba,
    context: RenderContext,
    fontStyle: String,
    decoration: String,
    brush: Brush? = null,
): SpanStyle {
    val density = LocalDensity.current
    val familyName = context.theme.fontFamilies[if (style.role == "heading") "heading" else "body"]
    val family = familyName?.let(context.fonts::family) ?: FontFamily.Default
    return with(density) {
        val size = style.fontSize.toFloat().dp.toSp()
        val weight = FontWeight(style.fontWeight.coerceIn(1, 1000))
        val slant = if (fontStyle == "italic") FontStyle.Italic else FontStyle.Normal
        val spacing = style.letterSpacing.signedDp.toSp()
        val line = when (decoration) {
            "underline" -> TextDecoration.Underline
            "lineThrough" -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }
        if (brush != null) {
            SpanStyle(brush = brush, fontSize = size, fontWeight = weight, fontStyle = slant, fontFamily = family, letterSpacing = spacing, textDecoration = line)
        } else {
            SpanStyle(color = color.color, fontSize = size, fontWeight = weight, fontStyle = slant, fontFamily = family, letterSpacing = spacing, textDecoration = line)
        }
    }
}
