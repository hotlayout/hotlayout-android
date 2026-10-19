package com.hotlayout.render

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue

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
            letterSpacing = style.letterSpacing.toFloat().dp.toSp(),
            lineHeight = style.lineBox.toFloat().dp.toSp(),
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
) {
    BasicText(
        text = AnnotatedString(text),
        modifier = modifier.semantics { this.text = AnnotatedString(text) },
        style = composeTextStyle(style, color, context, italic, decoration, align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
