package com.hotlayout.render.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.hotlayout.data.DataScope
import com.hotlayout.icons.IconPath
import com.hotlayout.icons.IconSet
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.RenderContext
import com.hotlayout.render.WidgetCall
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.TextStyleValue

/** What stands in the box of an icon: nothing, a drawing of the set, or an empty box of the same size. */
internal enum class IconBoxState(val word: String) { None("none"), Drawn("drawn"), Empty("empty") }

/** The icon of a node after the data was asked (`x-iconFromData`). */
internal data class IconResolution(
    /** The value taken as the icon name; `null` when the node has no icon at all. */
    val resolved: JsonValue?,
    val box: IconBoxState,
) {
    val name: String? get() = if (box == IconBoxState.Drawn) resolved?.string else null

    /** Whether the drawing is flipped: a directional icon of the set in right-to-left text. */
    fun isMirrored(rightToLeft: Boolean): Boolean = rightToLeft && name?.let(IconSet.mirrored::contains) == true
}

/** A part of a text with an icon beside it. */
internal enum class BesidePart(val word: String) { Text("text"), Icon("icon") }

/**
 * The rules of an icon beside a text: one rule for a button caption, a badge caption and the
 * trailing text of a list row (`x-style.layout.iconBesideText`).
 */
internal object IconBesideTextRules {
    const val GAP_TOKEN = "xs"

    /**
     * The icon of a node. The document's icon is read first: without it the node has no icon and
     * the path is not read. Data replaces the name when it holds a value (`null`, absence and the
     * empty string are «no value»); a value that is not a drawable name gives an empty box.
     */
    fun resolve(props: JsonObject, field: String, scope: DataScope): IconResolution {
        val own = props[field]
        if (own == null || own.isNull) return IconResolution(null, IconBoxState.None)
        var value = own
        val fromData = props["iconPath"]?.string?.let { scope[it] }
        if (fromData != null && !fromData.isNull && fromData.string != "") value = fromData
        val drawable = value.string?.let { IconSet.elements(it) != null } ?: false
        return IconResolution(value, if (drawable) IconBoxState.Drawn else IconBoxState.Empty)
    }

    /** The parts in reading order. A position word the reader does not know is `start`. */
    fun logicalOrder(icon: IconResolution, position: String?): List<BesidePart> = when {
        icon.box == IconBoxState.None -> listOf(BesidePart.Text)
        position == "end" -> listOf(BesidePart.Text, BesidePart.Icon)
        else -> listOf(BesidePart.Icon, BesidePart.Text)
    }

    /** The same parts from the left to the right of the screen. */
    fun visualOrder(logical: List<BesidePart>, rightToLeft: Boolean): List<BesidePart> = if (rightToLeft) logical.reversed() else logical

    /** The icon box beside a text: as wide as the font size, as tall as the text line. */
    fun glyphBox(typography: TextStyleValue): Triple<Double, Double, Double> = Triple(typography.fontSize, typography.fontSize, typography.lineBox)

    /** The width the icon adds: its box and the gap. */
    fun addsInline(typography: TextStyleValue, gap: Double): Double = typography.fontSize + gap
}

/**
 * The glyph box of an icon size word (`iconGlyphTypography`): as wide as the font size and as tall
 * as the line of the text style.
 */
internal fun iconWordBox(word: String?, theme: HotlayoutTheme): Pair<Double, Pair<Double, Double>> {
    val token = mapOf("sm" to "bodyS", "md" to "titleM", "lg" to "displayL")[word ?: "md"] ?: "titleM"
    val style = theme.typography[token] ?: TextStyleValue(18.0, 1.3333, 700)
    return style.fontSize to (style.fontSize to style.lineBox)
}

/** `glyphSize`: a whole number in 8…128, else no field. */
internal fun ownGlyphSize(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it == Math.rint(it) && it in 8.0..128.0 }

/** `Icon`: a drawing of the set in the inherited colour; `glyphSize` names the drawing and the box grows. */
@Composable
internal fun IconWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val (fontSize, word) = iconWordBox(node.props["size"]?.string, context.theme)
    val own = ownGlyphSize(node.props["glyphSize"]?.number)
    val glyph = own ?: fontSize
    val width = own ?: word.first
    val height = own ?: word.second
    val icon = IconBesideTextRules.resolve(node.props, "name", context.scope)
    IconGlyph(icon.name, glyph, width, height, context.inherited.color.let { Color(it.argb()) }, context.rightToLeft)
}

/**
 * One icon of the set drawn at [glyph] points inside a box of [width] × [height], as vector paths.
 * An unknown name draws an empty box of the same size; a mirrored name is flipped in right-to-left
 * text. Hidden from accessibility: the widget around it names it.
 */
@Composable
internal fun IconGlyph(name: String?, glyph: Double, width: Double, height: Double, color: Color, rightToLeft: Boolean, modifier: Modifier = Modifier) {
    val elements = name?.let(IconSet::elements).orEmpty()
    val mirrored = rightToLeft && name != null && name in IconSet.mirrored
    Canvas(modifier.size(width.dp, height.dp).clearAndSetSemantics { testTag = "icon:${name ?: ""}" }) {
        val side = glyph.toFloat() * density
        val scale = side / IconSet.GRID_SIZE.toFloat()
        translate((size.width - side) / 2, (size.height - side) / 2) {
            scale(if (mirrored) -1f else 1f, 1f, pivot = Offset(side / 2, side / 2)) {
                for (element in elements) {
                    val path = element.path.toComposePath(scale)
                    if (element.filled) drawPath(path, color)
                    if (element.stroked) {
                        drawPath(path, color, style = Stroke(width = element.strokeWidth.toFloat() * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/** The path in Compose geometry, scaled from the grid. */
internal fun IconPath.toComposePath(scale: Float): Path {
    val path = Path()
    fun f(value: Double) = value.toFloat() * scale
    for (command in commands) {
        when (command) {
            is IconPath.Command.Move -> path.moveTo(f(command.x), f(command.y))
            is IconPath.Command.Line -> path.lineTo(f(command.x), f(command.y))
            is IconPath.Command.Quad -> path.quadraticTo(f(command.cx), f(command.cy), f(command.x), f(command.y))
            is IconPath.Command.Cubic -> path.cubicTo(f(command.c1x), f(command.c1y), f(command.c2x), f(command.c2y), f(command.x), f(command.y))
            is IconPath.Command.Ellipse -> path.addOval(Rect(f(command.x), f(command.y), f(command.x + command.width), f(command.y + command.height)))
            is IconPath.Command.RoundedRect -> path.addRoundRect(
                RoundRect(
                    Rect(f(command.x), f(command.y), f(command.x + command.width), f(command.y + command.height)),
                    CornerRadius(f(command.rx), f(command.ry)),
                ),
            )
            IconPath.Command.Close -> path.close()
        }
    }
    return path
}

/**
 * An icon beside a text, in the colour of the text, with the gap between, the box centred on the
 * line of the text. The order is logical: a row in right-to-left layout puts the parts the other way.
 */
@Composable
internal fun IconBesideText(
    icon: IconResolution,
    position: String?,
    typography: TextStyleValue,
    gap: Double,
    color: Color,
    context: RenderContext,
    label: @Composable () -> Unit,
) {
    if (icon.box == IconBoxState.None) {
        label()
        return
    }
    val glyph: @Composable () -> Unit = { IconGlyph(icon.name, typography.fontSize, typography.fontSize, typography.lineBox, color, context.rightToLeft) }
    Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
        if (IconBesideTextRules.logicalOrder(icon, position).first() == BesidePart.Icon) {
            glyph()
            Box { label() }
        } else {
            Box { label() }
            glyph()
        }
    }
}
