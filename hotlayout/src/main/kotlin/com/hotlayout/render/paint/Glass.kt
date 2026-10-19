package com.hotlayout.render.paint

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import java.util.concurrent.atomic.AtomicInteger

/** The glass of a node (`style.backdropBlur`): the deviation of the blur under its box, a whole number 1…40. */
internal object GlassRules {
    fun radius(value: JsonValue?): Int? = value?.number?.takeIf { it.isFinite() && it == Math.rint(it) && it in 1.0..40.0 }?.toInt()

    /** Whether any node of the tree names a glass in its style or in a state of it. */
    fun uses(node: DocumentNode, depth: Int = 0): Boolean {
        if (depth > 64) return false
        return names(node.fields["style"]) || names(node.fields["styleWhen"]) || node.children.any { uses(it, depth + 1) }
    }

    private fun names(value: JsonValue?, depth: Int = 0): Boolean {
        if (value == null || depth > 6) return false
        value.obj?.let { obj -> return obj.any { (key, inner) -> (key == "backdropBlur" && radius(inner) != null) || names(inner, depth + 1) } }
        return value.array?.any { names(it, depth + 1) } ?: false
    }

    /** Blur of what lies under a box needs a render effect: API 31. Below it the box keeps its own background and nothing blurs. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31
}

/** What the glass nodes of a page blur: the page drawn once WITHOUT them, recorded in a layer. */
internal class GlassSource(val layer: GraphicsLayer) {
    /** True while the page is being recorded for the glass: a glass node then draws nothing. */
    var recording = false

    /** The place of the page in the root of the window. */
    var origin = Offset.Zero
}

internal val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

/** The last glass drawn, for the tests: how many times, with which deviation (in pixels), over which box. */
internal object GlassProbe {
    val draws = AtomicInteger()
    @Volatile var lastRadiusPx = 0f
    @Volatile var lastBox: Rect = Rect.Zero
}

/** The source of the glass of a page: only a page whose tree names a glass records itself, and only where a blur exists. */
@Composable
internal fun rememberGlassSource(root: DocumentNode): GlassSource? {
    val uses = remember(root) { GlassRules.supported && GlassRules.uses(root) }
    if (!uses) return null
    return GlassSource(rememberGraphicsLayer())
}

@Composable
internal fun ProvideGlass(source: GlassSource?, content: @Composable () -> Unit) {
    if (source == null) content() else CompositionLocalProvider(LocalGlassSource provides source, content)
}

/**
 * Records the page twice: once without the glass nodes into [GlassSource.layer], then for the
 * screen, where each glass node draws the recorded page blurred under its own box.
 */
internal fun Modifier.glassSource(source: GlassSource): Modifier = this
    .onGloballyPositioned { source.origin = it.positionInRoot() }
    .drawWithContent {
        source.recording = true
        try {
            source.layer.record(drawContext.density, drawContext.layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
        } finally {
            source.recording = false
        }
        drawContent()
    }

/**
 * Glass: what lies under the box, blurred, clipped by the radius of the node; the background of the
 * node lies over it (the caller puts this before the background). Without a source (a page that
 * names no glass, or an API below 31) the box is drawn as it is.
 */
@Composable
internal fun Modifier.glass(blur: Int?, shape: Shape): Modifier {
    val source = LocalGlassSource.current ?: return this
    if (blur == null) return this
    val layer = rememberGraphicsLayer()
    val origin = remember { FloatArray(2) }
    return this
        .onGloballyPositioned { it.positionInRoot().let { p -> origin[0] = p.x; origin[1] = p.y } }
        .drawWithContent {
            if (source.recording) return@drawWithContent
            drawBackdrop(source, layer, blur * density, shape, Offset(origin[0], origin[1]))
            drawContent()
        }
}

private fun DrawScope.drawBackdrop(source: GlassSource, layer: GraphicsLayer, radiusPx: Float, shape: Shape, origin: Offset) {
    layer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
    val shift = source.origin - origin
    layer.record(drawContext.density, drawContext.layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
        translate(shift.x, shift.y) { drawLayer(source.layer) }
    }
    val box = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBackdrop)) }
    clipPath(box) { drawLayer(layer) }
    GlassProbe.draws.incrementAndGet()
    GlassProbe.lastRadiusPx = radiusPx
    GlassProbe.lastBox = Rect(origin, size)
}
