package com.hotlayout.render.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hotlayout.json.JsonValue
import com.hotlayout.render.RenderContext
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.text.ShownText
import com.hotlayout.theme.Rgba
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The geometry of a picture (`x-style.layout.image`): the proportion of its box, the box itself and
 * where the file lies in it. Pure functions.
 *
 * Wave «images» owns this file: the shared `media-chrome` picture tables run against these rules.
 */
internal object ImageRules {
    private val ratioWords = mapOf("1:1" to 1.0, "16:9" to 16.0 / 9, "4:3" to 4.0 / 3)
    const val RATIO_FALLBACK = 16.0 / 9

    /** «Width over height» of `aspectRatio`: a word, or an own pair of positive sides held to 1:8…8:1. */
    fun ratio(value: JsonValue?): Double = when (value) {
        is JsonValue.Str -> ratioWords[value.value] ?: RATIO_FALLBACK
        is JsonValue.Obj -> {
            val width = value["width"]?.number
            val height = value["height"]?.number
            if (width == null || height == null || width <= 0 || height <= 0) RATIO_FALLBACK else (width / height).coerceIn(0.125, 8.0)
        }
        else -> RATIO_FALLBACK
    }

    /** The box of a picture: [height] is the named one; otherwise width over the proportion. Limits act either way, the minimum beats the maximum. */
    class Box(val width: Double, val height: Double, val byRatio: Boolean)

    fun box(aspectRatio: JsonValue?, width: Double, height: Double?, minHeight: Double?, maxHeight: Double?): Box {
        val raw = height ?: (width / ratio(aspectRatio))
        val capped = maxHeight?.let { minOf(raw, it) } ?: raw
        val floored = minHeight?.let { maxOf(capped, it) } ?: capped
        return Box(width, floored, height == null)
    }

    /** The radius a corner really has: the named one, held to half of the shorter side. */
    fun effectiveRadius(boxWidth: Double, boxHeight: Double, radius: Double): Double = maxOf(0.0, minOf(radius, boxWidth / 2, boxHeight / 2))

    /** A circle is a square whose effective radius is half the side; `pill` is a number of the theme, so a large square with it is a rounded square. */
    fun isCircle(boxWidth: Double, boxHeight: Double, radius: Double): Boolean =
        boxWidth == boxHeight && effectiveRadius(boxWidth, boxHeight, radius) == boxWidth / 2

    /** Where a file of the proportion [natural] lies in a box: `contain` inside it, anything else over it, centred. */
    fun paintRect(boxWidth: Double, boxHeight: Double, natural: Double, fit: String?): DoubleArray {
        if (boxWidth <= 0 || boxHeight <= 0 || natural <= 0 || !natural.isFinite()) return doubleArrayOf(0.0, 0.0, boxWidth, boxHeight)
        val boxRatio = boxWidth / boxHeight
        val widthBound = if (fit == "contain") natural >= boxRatio else natural <= boxRatio
        val width = if (widthBound) boxWidth else boxHeight * natural
        val height = if (widthBound) boxWidth / natural else boxHeight
        return doubleArrayOf((boxWidth - width) / 2, (boxHeight - height) / 2, width, height)
    }
}

/** The address of an image source of the document, after the host's policy. */
internal fun imageAddress(source: JsonValue?, context: RenderContext): String? {
    if (source?.obj == null) return null
    return when (source["kind"]?.string) {
        "asset" -> source["asset"]?.string?.let { context.images.assets[it] }
        "url" -> source["url"]?.string?.let(context.images.resolve)
        "binding" -> {
            val bound = source["path"]?.string?.let { context.scope[it]?.string }?.takeIf { it.isNotEmpty() }?.let(context.images.resolve)
            bound ?: source["fallback"]?.string?.let { context.images.assets[it] }
        }
        else -> null
    }
}

/**
 * The memory cache of decoded pictures, shared by every image of every screen: a picture in a list
 * is fetched once, and an address that failed is not asked for again until [reset].
 */
public object ImageStore {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    public fun reset() {
        cache.evictAll()
        synchronized(failed) { failed.clear() }
    }

    internal fun cached(address: String): Bitmap? = cache.get(address)

    internal suspend fun load(address: String): Bitmap? {
        cached(address)?.let { return it }
        if (synchronized(failed) { address in failed }) return null
        val bitmap = withContext(Dispatchers.IO) { runCatching { decode(address) }.getOrNull() }
        if (bitmap != null) cache.put(address, bitmap) else synchronized(failed) { failed.add(address) }
        return bitmap
    }

    private fun decode(address: String): Bitmap? {
        if (address.startsWith("data:image/")) {
            val comma = address.indexOf(',')
            if (comma < 0 || !address.substring(0, comma).endsWith(";base64")) return null
            val bytes = Base64.decode(address.substring(comma + 1), Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        return try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A picture in a box: the placeholder while it loads and when it fails, then the file placed by
 * [ImageRules.paintRect] and cut by the box. The slot is the same before and after, so the layout
 * does not jump.
 */
@Composable
internal fun RemoteImage(address: String?, fit: String, placeholder: Rgba, modifier: Modifier = Modifier) {
    var image by remember(address) { mutableStateOf<ImageBitmap?>(address?.let(ImageStore::cached)?.asImageBitmap()) }
    LaunchedEffect(address) {
        if (address != null && image == null) image = ImageStore.load(address)?.asImageBitmap()
    }
    Canvas(modifier.clipToBounds()) {
        val picture = image
        if (picture == null || picture.height == 0) {
            drawRect(placeholder.color)
            return@Canvas
        }
        val rect = ImageRules.paintRect(size.width.toDouble(), size.height.toDouble(), picture.width.toDouble() / picture.height, fit)
        drawImage(
            picture,
            dstOffset = IntOffset(rect[0].toInt(), rect[1].toInt()),
            dstSize = IntSize(rect[2].toInt(), rect[3].toInt()),
        )
    }
}

/**
 * `Image`: the width is given by the place, the height by the proportion unless the node names one.
 * The file is never asked for its own size. `alt` names the picture for accessibility; an empty one
 * hides it.
 */
@Composable
internal fun ImageWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val look = context.resolver.box(call.style, context.scope)
    val fit = if (node.props["fit"]?.string == "contain") "contain" else "cover"
    val placeholder = context.theme.colorValue("muted")?.withOpacity(0.15) ?: Rgba(0.5, 0.5, 0.5, 0.15)
    val source = node.props["source"] ?: JsonValue.of(mapOf("kind" to "asset", "asset" to "placeholder"))
    val alt = node.props["alt"]?.let { if (it.string != null) ShownText.of(it.string!!) else context.text(node, "alt") } ?: ""
    val semantics = Modifier.clearAndSetSemantics {
        if (alt.isNotEmpty()) {
            contentDescription = alt
            role = Role.Image
        }
    }
    val picture: @Composable () -> Unit = { RemoteImage(imageAddress(source, context), fit, placeholder, Modifier.fillMaxSize()) }
    if (look.height != null) {
        // The node box gives the named height; the proportion is not read.
        Box(semantics.fillMaxSize()) { picture() }
    } else {
        val aspectRatio = node.props["aspectRatio"]
        val inset = look.padding.vertical + 2 * (look.borderWidth ?: 0.0)
        Layout(content = picture, modifier = semantics) { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            var height = ImageRules.box(
                aspectRatio, width.toDouble(), null,
                look.minHeight?.let { (it - inset) * density }, look.maxHeight?.let { (it - inset) * density },
            ).height.toInt()
            height = height.coerceIn(constraints.minHeight, if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE)
            val placeables = measurables.map { it.measure(Constraints.fixed(width, maxOf(0, height))) }
            layout(width, maxOf(0, height)) { placeables.forEach { it.place(0, 0) } }
        }
    }
}
