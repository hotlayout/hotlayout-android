package com.hotlayout.render.paint

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import com.hotlayout.JsonValue
import com.hotlayout.model.BlurRules
import com.hotlayout.model.DocumentNode
import java.util.concurrent.atomic.AtomicInteger

/** The glass of a node (`style.backdropBlur`): the deviation of the blur under its box, a whole number 1…40. */
internal object GlassRules {
    /** Whether any node of the tree names a glass in its style or in a state of it. */
    fun uses(node: DocumentNode, depth: Int = 0): Boolean {
        if (depth > 64) return false
        return names(node.fields["style"]) || names(node.fields["styleWhen"]) || node.children.any { uses(it, depth + 1) }
    }

    private fun names(value: JsonValue?, depth: Int = 0): Boolean {
        if (value == null || depth > 6) return false
        value.obj?.let { obj -> return obj.any { (key, inner) -> (key == "backdropBlur" && BlurRules.radius(inner) != null) || names(inner, depth + 1) } }
        return value.array?.any { names(it, depth + 1) } ?: false
    }

    /** Blur of what lies under a box needs a render effect: API 31. Below it the box keeps its own background and nothing blurs. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** How far a Gaussian of deviation [sigmaPx] reaches: three deviations. */
    fun reachPx(sigmaPx: Float): Int = kotlin.math.ceil(3 * sigmaPx).toInt()
}

/**
 * What the glass nodes of a page blur: the page drawn once WITHOUT them, recorded in a layer, and under a glass node the
 * glass nodes drawn before it. A glass node records its own content in a layer of its own and is drawn afterwards over the page, so the tree
 * is traversed once (a second traversal draws the layers of the children a second time and some of them
 * stay empty).
 *
 * The glass nodes stay registered for as long as they are on the page, not per pass: a node inside a layer of its own
 * (a motion, an alpha) is not drawn again when the page is, so a list rebuilt from each pass would lose it. A glass node
 * never draws into the host canvas, whether the page is being recorded or the node is redrawn alone: the page it would
 * show itself in is the page the glass blurs, and the node would be drawn twice, one of them blurred and at the wrong place.
 */
internal class GlassSource(val layer: GraphicsLayer) {
    /** True while the page is being recorded for the glass. */
    var recording = false

    /** The place of the page in the window, read when the glass is drawn. */
    var coordinates: LayoutCoordinates? = null

    /** The glass nodes of the page in the order they were first drawn: the tree order, a node before the glass inside it. */
    val entries = ArrayList<GlassEntry>()

    /** The nodes that stand after a glass node in their parent (see [aboveGlass]), in the same order. */
    val above = ArrayList<AboveEntry>()

    /** Read by the draw of the page: a change redraws it (a node moved, was redrawn alone, left the page). */
    private val frame = mutableIntStateOf(0)

    fun readFrame(): Int = frame.intValue

    fun invalidate() { frame.intValue += 1 }

    /**
     * Puts a glass node on the page, after every glass it stands in: by the number of glass nodes around it, and in the order
     * of the first draw within one. A bar that becomes glass after its buttons did is still drawn before them.
     */
    fun register(entry: GlassEntry) {
        if (entry in entries) return
        val at = entries.indexOfFirst { it.level > entry.level }
        if (at < 0) entries.add(entry) else entries.add(at, entry)
    }

    /** Where the node stands in the page, read now (the layers of its ancestors, a motion, are part of it). */
    fun place(entry: Placed): Offset {
        val own = entry.coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: return Offset.Zero
        val page = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
        return own - page
    }
}

/** A node the page draws over its own content: where it stands. */
internal interface Placed {
    var coordinates: LayoutCoordinates?
}

/** A node painted over the glass: its own content in a layer of its own. */
internal class AboveEntry(val content: GraphicsLayer) : Placed {
    override var coordinates: LayoutCoordinates? = null
}

/** A glass node: its own content, the layer of its blur, its box and its shape. */
internal class GlassEntry(val content: GraphicsLayer, val backdrop: GraphicsLayer) : Placed {
    override var coordinates: LayoutCoordinates? = null
    var size: IntSize = IntSize.Zero
    var radiusPx: Float = 0f
    var shape: Shape = RectangleShape

    /** How many glass nodes the node stands inside: a glass is drawn after the glass it stands in. */
    var level = 0
}

/** Whether a glass node stands inside a moving node (see `NodeMotionBox`). */
internal class GlassInside { var inside = false }

internal val LocalGlassInside = compositionLocalOf<GlassInside?> { null }

/** How many glass nodes the composition below stands inside (provided by a glass node for its content). */
internal val LocalGlassLevel = compositionLocalOf { 0 }

internal val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

/** The last glass drawn, for the tests: how many times, with which deviation (in pixels), over which box. */
internal object GlassProbe {
    val draws = AtomicInteger()

    /** How many nodes after a glass node were drawn over it. */
    val aboveDraws = AtomicInteger()
    /** The boxes of the glass nodes of the last pass, in the order they were drawn. */
    @Volatile var drawnBoxes: List<Rect> = emptyList()
    @Volatile var lastRadiusPx = 0f
    @Volatile var lastBox: Rect = Rect.Zero

    /** How far beyond its box the last glass reads the page (pixels on every side). */
    @Volatile var lastBackdropReachPx = 0
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
    .onGloballyPositioned { source.coordinates = it }
    .drawWithContent {
        source.readFrame()
        source.recording = true
        try {
            recordContent(source.layer)
        } finally {
            source.recording = false
        }
        drawLayer(source.layer)
        val drawn = ArrayList<Rect>()
        val under = ArrayList<GlassEntry>()
        for (entry in source.entries.toList()) {
            // A snapshot: a layer may replay its record later (the software path), when the list holds this entry too.
            drawn.add(drawGlass(source, entry, under.toList()))
            under.add(entry)
        }
        GlassProbe.drawnBoxes = drawn
        // What stands after a glass node in its parent lies over that glass, not blurred under it.
        for (entry in source.above.toList()) {
            val at = source.place(entry)
            translate(at.x, at.y) { drawLayer(entry.content) }
        }
        GlassProbe.aboveDraws.addAndGet(source.above.size)
    }

/** Keeps [entry] on the page while the node is on it, and redraws the page when the node moves or leaves. */
@Composable
private fun <T : Placed> Modifier.onPage(source: GlassSource, list: MutableList<T>, entry: T): Modifier {
    DisposableEffect(source, entry) {
        onDispose {
            list.remove(entry)
            source.invalidate()
        }
    }
    return this.onGloballyPositioned { coordinates ->
        val moved = entry.coordinates?.takeIf { it.isAttached }?.positionInRoot() != coordinates.positionInRoot()
        entry.coordinates = coordinates
        if (moved) source.invalidate()
    }
}

/**
 * A node that comes after a glass node in the same parent (a dot on a glass button): it is drawn over the glass, not into the
 * page the glass blurs, so it stays sharp and on top. Without a glass source it is drawn as it is.
 */
@Composable
internal fun Modifier.aboveGlass(): Modifier {
    val source = LocalGlassSource.current ?: return this
    val content = rememberGraphicsLayer()
    val entry = remember(content) { AboveEntry(content) }
    return this
        .onPage(source, source.above, entry)
        .drawWithContent {
            if (entry !in source.above) source.above.add(entry)
            recordContent(content)
            if (!source.recording) source.invalidate()
        }
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
    LocalGlassInside.current?.inside = true
    val entry = remember(content, backdrop) { GlassEntry(content, backdrop) }
    entry.level = LocalGlassLevel.current
    return this
        .onPage(source, source.entries, entry)
        .drawWithContent {
            entry.size = IntSize(size.width.toInt(), size.height.toInt())
            entry.radiusPx = blur * density
            entry.shape = shape
            // Registered before the content is recorded: a glass node inside this one registers after it and is drawn over it.
            source.register(entry)
            recordContent(content)
            // Redrawn alone (its own layer changed, not the page): the page shows the new content.
            if (!source.recording) source.invalidate()
        }
}

/**
 * Draws [entry] over the page: what lies under its box blurred, clipped by its shape, then the node itself. What lies under it
 * is the page AND the glass nodes drawn before it ([under]: the glass it stands in, a glass beside it earlier in the tree) as
 * they are shown, each with its own blur and content. A button of glass in a bar of glass blurs the bar as it is shown (the
 * page already blurred by the bar, the tint of the bar over it), not the page under both, as a backdrop filter does.
 */
private fun DrawScope.drawGlass(source: GlassSource, entry: GlassEntry, under: List<GlassEntry>): Rect {
    val box = androidx.compose.ui.geometry.Size(entry.size.width.toFloat(), entry.size.height.toFloat())
    val shift = source.place(entry)
    // The blur reads the page beyond the box too (the page under the edge is blurred into the glass, as in CSS): the backdrop is
    // recorded wider than the box by what the blur reaches, and only the box is shown.
    val reach = GlassRules.reachPx(entry.radiusPx)
    val padded = IntSize(entry.size.width + 2 * reach, entry.size.height + 2 * reach)
    val read = Rect(shift.x - reach, shift.y - reach, shift.x + box.width + reach, shift.y + box.height + reach)
    entry.backdrop.renderEffect = BlurEffect(entry.radiusPx, entry.radiusPx, TileMode.Clamp)
    entry.backdrop.record(drawContext.density, drawContext.layoutDirection, padded) {
        translate(-shift.x + reach, -shift.y + reach) {
            drawLayer(source.layer)
            for (below in under) {
                val at = source.place(below)
                val belowBox = Rect(at, androidx.compose.ui.geometry.Size(below.size.width.toFloat(), below.size.height.toFloat()))
                if (belowBox.overlaps(read)) paintGlass(below, at)
            }
        }
    }
    paintGlass(entry, shift)
    GlassProbe.draws.incrementAndGet()
    GlassProbe.lastRadiusPx = entry.radiusPx
    GlassProbe.lastBox = Rect(shift, box)
    GlassProbe.lastBackdropReachPx = reach
    return GlassProbe.lastBox
}

/** A glass node as it is shown at [at]: its recorded backdrop clipped by its shape, then its content. */
private fun DrawScope.paintGlass(entry: GlassEntry, at: Offset) {
    val box = androidx.compose.ui.geometry.Size(entry.size.width.toFloat(), entry.size.height.toFloat())
    val reach = GlassRules.reachPx(entry.radiusPx)
    translate(at.x, at.y) {
        val outline = Path().apply { addOutline(entry.shape.createOutline(box, layoutDirection, this@paintGlass)) }
        clipPath(outline) { translate(-reach.toFloat(), -reach.toFloat()) { drawLayer(entry.backdrop) } }
        drawLayer(entry.content)
    }
}

/**
 * Records the content of the node into [layer]. The content scope draws into the canvas of its own context, not into
 * the one of the layer being recorded, so the canvas of the scope is pointed at the layer for the time of the call:
 * otherwise the content goes to the screen and the layer stays empty.
 */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.recordContent(layer: GraphicsLayer) {
    val owner = this
    layer.record(drawContext.density, drawContext.layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
        val context = owner.drawContext
        val saved = context.canvas
        context.canvas = drawContext.canvas
        try {
            owner.drawContent()
        } finally {
            context.canvas = saved
        }
    }
}
