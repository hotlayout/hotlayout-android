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

/**
 * What the glass nodes of a page blur: the page drawn once WITHOUT them, recorded in a layer. A glass
 * node records its own content in a layer of its own during that pass and is drawn afterwards over
 * the page, so the tree is traversed once (a second traversal draws the layers of the children
 * a second time and some of them stay empty).
 */
internal class GlassSource(val layer: GraphicsLayer) {
    /** True while the page is being recorded for the glass: a glass node then draws nothing into it. */
    var recording = false

    /** The place of the page in the root of the window. */
    var origin = Offset.Zero

    /** The glass nodes met during the recording, in tree order. */
    val entries = ArrayList<GlassEntry>()
}

/** A glass node of the last recording: its own content, the layer of its blur and where it stands. */
internal class GlassEntry(
    val content: GraphicsLayer,
    val backdrop: GraphicsLayer,
    val origin: Offset,
    val size: IntSize,
    val radiusPx: Float,
    val shape: Shape,
)

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
 * Records the page without its glass nodes into [GlassSource.layer], draws it, then draws every glass
 * node over it: the recorded page blurred under the box, clipped by the radius, then the node itself.
 */
internal fun Modifier.glassSource(source: GlassSource): Modifier = this
    .onGloballyPositioned { source.origin = it.positionInRoot() }
    .drawWithContent {
        source.entries.clear()
        source.recording = true
        try {
            source.layer.record(drawContext.density, drawContext.layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
        } finally {
            source.recording = false
        }
        drawLayer(source.layer)
        for (entry in source.entries) drawGlass(source, entry)
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
    val content = rememberGraphicsLayer()
    val backdrop = rememberGraphicsLayer()
    val origin = remember { FloatArray(2) }
    return this
        .onGloballyPositioned { it.positionInRoot().let { p -> origin[0] = p.x; origin[1] = p.y } }
        .drawWithContent {
            if (!source.recording) {
                drawContent()
                return@drawWithContent
            }
            content.record(drawContext.density, drawContext.layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
            source.entries.add(GlassEntry(content, backdrop, Offset(origin[0], origin[1]), IntSize(size.width.toInt(), size.height.toInt()), blur * density, shape))
        }
}

private fun DrawScope.drawGlass(source: GlassSource, entry: GlassEntry) {
    val box = androidx.compose.ui.geometry.Size(entry.size.width.toFloat(), entry.size.height.toFloat())
    val shift = entry.origin - source.origin
    entry.backdrop.renderEffect = BlurEffect(entry.radiusPx, entry.radiusPx, TileMode.Clamp)
    entry.backdrop.record(drawContext.density, drawContext.layoutDirection, entry.size) {
        translate(-shift.x, -shift.y) { drawLayer(source.layer) }
    }
    translate(shift.x, shift.y) {
        val outline = Path().apply { addOutline(entry.shape.createOutline(box, layoutDirection, this@drawGlass)) }
        clipPath(outline) { drawLayer(entry.backdrop) }
        drawLayer(entry.content)
    }
    GlassProbe.draws.incrementAndGet()
    GlassProbe.lastRadiusPx = entry.radiusPx
    GlassProbe.lastBox = Rect(entry.origin, box)
}
