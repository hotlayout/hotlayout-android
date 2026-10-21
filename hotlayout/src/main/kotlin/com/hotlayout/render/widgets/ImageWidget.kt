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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hotlayout.DataScope
import com.hotlayout.HotlayoutReport
import com.hotlayout.ImagePolicy
import com.hotlayout.JsonValue
import com.hotlayout.model.ReportSink
import com.hotlayout.render.RenderContext
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.text.ShownText
import com.hotlayout.theme.Rgba
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The geometry of a picture (`x-style.layout.image`): the proportion of its box, the box itself and
 * where the file lies in it. Pure functions, run by the shared `media-chrome` picture tables.
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

/** What the reader says about an address it did not load: the document's address truncated, an address from data by scheme and host only. */
internal object ImageSourceRules {
    private val neverSchemes = setOf("javascript", "vbscript")
    private val schemeChars = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    const val REPORT_DOCUMENT_LENGTH = 120

    /** Whether the address is never a picture, whatever the host says: its scheme, read as a URL parser reads it, is a script. */
    fun neverAnImage(address: String): Boolean {
        val stripped = address.dropWhile { it.code in 0..0x20 }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val colon = stripped.indexOf(':')
        if (colon <= 0) return false
        val scheme = stripped.substring(0, colon)
        return schemeChars.matches(scheme) && scheme.lowercase() in neverSchemes
    }

    /** The scheme and the host of an address and nothing else: the path and the query of an address from data may carry a token. */
    fun schemeAndHost(address: String): String {
        val colon = address.indexOf(':')
        if (colon <= 0 || colon > 20) return "(not an address)"
        val scheme = address.substring(0, colon).filter { it.isLetterOrDigit() || it in "+.-" }
        val rest = address.substring(colon + 1)
        if (!rest.startsWith("//")) return "$scheme:"
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        // The port is no part of the host.
        val host = (if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')).take(80)
        return "$scheme://$host"
    }

    fun documentAddress(address: String): String = if (address.length <= REPORT_DOCUMENT_LENGTH) address else address.take(REPORT_DOCUMENT_LENGTH) + "…"
}

/** Where an image source of the document leads after the host's policy: the address to load, or why none is loaded. */
internal class ImageAddress(val address: String?, val refusal: String?)

/** The address of an image source; a refused one is no request and a report (the address shown without its secrets). */
internal fun resolveImageAddress(source: JsonValue?, images: ImagePolicy, scope: DataScope): ImageAddress {
    if (source?.obj == null) return ImageAddress(null, null)
    fun judged(address: String, fromData: Boolean): ImageAddress {
        val resolved = images.resolve(address)?.takeUnless(ImageSourceRules::neverAnImage)
        if (resolved != null) return ImageAddress(resolved, null)
        val shown = if (fromData) ImageSourceRules.schemeAndHost(address) else ImageSourceRules.documentAddress(address)
        return ImageAddress(null, "the address $shown is not loaded: the picture is drawn as its placeholder")
    }
    return when (source["kind"]?.string) {
        "asset" -> ImageAddress(source["asset"]?.string?.let { images.assets[it] }, null)
        "url" -> source["url"]?.string?.let { judged(it, fromData = false) } ?: ImageAddress(null, null)
        "binding" -> {
            val bound = source["path"]?.string?.let { scope[it]?.string }?.takeIf { it.isNotEmpty() }?.let { judged(it, fromData = true) }
            if (bound?.address != null) bound
            else ImageAddress(source["fallback"]?.string?.let { images.assets[it] }, bound?.refusal)
        }
        else -> ImageAddress(null, null)
    }
}

/** The address of an image source of the document, after the host's policy; a refused one is reported once the node is composed. */
@Composable
internal fun imageAddress(source: JsonValue?, context: RenderContext): String? {
    val judged = resolveImageAddress(source, context.images, context.scope)
    judged.refusal?.let { reason -> SideEffect { context.sink.add(HotlayoutReport.Kind.IgnoredInput, "image", reason) } }
    return judged.address
}

/**
 * The memory cache of decoded pictures, shared by every image of every screen: a picture in a list
 * is fetched once. A picture that failed is not asked for again for [FAILURE_RETRY_MS]; after that
 * the next node that shows it asks again (a network that came back, a server that recovered).
 */
internal object ImageStore {
    /** How long a failed address is not asked for again. */
    const val FAILURE_RETRY_MS: Long = 30_000

    /** The memory the decoded pictures may take: an eighth of what the app may use. */
    private val cacheBytes: Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(4L * 1024 * 1024, Int.MAX_VALUE.toLong()).toInt()

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The moment, on [clock], until which an address that failed is not asked for again. */
    private val failedUntil = LruCache<String, Long>(512)

    /** Milliseconds of a monotonic clock; tests move it. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    internal fun reset() {
        cache.evictAll()
        failedUntil.evictAll()
    }

    /** A picture is decoded for a longest side: the same address under another side is another picture. */
    private fun key(address: String, maxPixelSide: Int) = "$maxPixelSide|$address"

    internal fun cached(address: String, maxPixelSide: Int = ImagePolicy.DEFAULT_MAX_PIXEL_SIDE): Bitmap? = cache.get(key(address, maxPixelSide))

    private fun recentlyFailed(key: String): Boolean {
        val until = failedUntil.get(key) ?: return false
        if (clock() < until) return true
        failedUntil.remove(key)
        return false
    }

    internal suspend fun load(
        address: String,
        maxBytes: Long = ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES,
        policy: ImagePolicy = ImagePolicy(maxDownloadBytes = maxBytes),
        report: (String) -> Unit = {},
    ): Bitmap? {
        val key = key(address, policy.maxPixelSide)
        cache.get(key)?.let { return it }
        if (recentlyFailed(key)) return null
        val isData = address.startsWith("data:image/")
        if (!isData && policy.fetch == null && CookieHandler.getDefault() != null) {
            // HttpURLConnection sends and stores through the app's own cookie handler; the address is the document's.
            report("a picture is not fetched while the app has a global CookieHandler; give ImagePolicy.fetch a client of your own")
            return null
        }
        // A cancelled load (the node left the screen) is no failure: the exception goes on and nothing is remembered.
        val bitmap = withContext(Dispatchers.IO) { decode(address, maxBytes, policy) }
        if (bitmap != null) cache.put(key, bitmap) else failedUntil.put(key, clock() + FAILURE_RETRY_MS)
        return bitmap
    }

    /** All of [input], or `null` once it is longer than [maxBytes] or past [deadlineAtNanos] (a server may send more than it announced, or slowly). */
    internal fun readCapped(input: InputStream, maxBytes: Long, deadlineAtNanos: Long = Long.MAX_VALUE): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            if (System.nanoTime() - deadlineAtNanos > 0) return null
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * The pixels of [bytes]: the size is read first (`inJustDecodeBounds`) and the picture is decoded sampled down
     * to the side (the longest side never above it); a picture is made smaller, not refused.
     */
    internal fun decodeBounded(bytes: ByteArray, maxPixelSide: Int): Bitmap? {
        val side = maxPixelSide.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val longest = maxOf(width, height).toLong()
        var sample = 1
        while (longest / sample > side) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private suspend fun decode(address: String, maxBytes: Long, policy: ImagePolicy): Bitmap? {
        val bytes = if (address.startsWith("data:image/")) dataBytes(address, maxBytes) else transfer(address, maxBytes, policy)
        return bytes?.let { decodeBounded(it, policy.maxPixelSide) }
    }

    /** The bytes of a `data:` address of base64, or `null`; the decoded size is known from the text before anything is decoded. */
    private fun dataBytes(address: String, maxBytes: Long): ByteArray? {
        val comma = address.indexOf(',')
        if (comma < 0 || !address.substring(0, comma).endsWith(";base64")) return null
        if ((address.length - comma - 1).toLong() * 3 / 4 > maxBytes + 3) return null
        val bytes = try {
            Base64.decode(address.substring(comma + 1), Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size <= maxBytes }
    }

    /** The bytes of a remote picture through the host's transport or the reader's own, within [ImagePolicy.totalDeadlineMs] for the whole call. */
    private suspend fun transfer(address: String, maxBytes: Long, policy: ImagePolicy): ByteArray? {
        val host = policy.fetch ?: return download(address, maxBytes, policy.totalDeadlineMs)
        val bytes = try {
            withTimeoutOrNull(policy.totalDeadlineMs) { host(address, maxBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transport that throws instead of answering `null` failed this picture, not the screen.
            null
        }
        return bytes?.takeIf { it.size <= maxBytes }
    }

    /**
     * The reader's own transfer: no redirects (a 3xx answer is a refusal, so no hop is followed unjudged), no cookies
     * (the caller refused when the app has a handler). The deadline holds the whole transfer, from the request to the
     * last byte, not the silence between two bytes: a server that sends a byte now and then is cut too.
     */
    private suspend fun download(address: String, maxBytes: Long, deadlineMs: Long): ByteArray? {
        val connection = try {
            URL(address).openConnection() as? HttpURLConnection
        } catch (_: IOException) {
            null
        } ?: return null
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        return try {
            withTimeoutOrNull(deadlineMs) {
                coroutineScope {
                    // A blocking read does not end on the interrupt of a cancelled coroutine: closing the connection ends it.
                    val cut = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            connection.disconnect()
                        }
                    }
                    try {
                        runInterruptible { read(connection, maxBytes) }
                    } finally {
                        cut.cancel()
                    }
                }
            }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection, maxBytes: Long): ByteArray? {
        if (connection.responseCode !in 200..299 || connection.contentLengthLong > maxBytes) return null
        return connection.inputStream.use { input -> readCapped(input, maxBytes) }
    }
}

/**
 * A picture in a box: the placeholder while it loads and when it fails, then the file placed by
 * [ImageRules.paintRect] and cut by the box. The slot is the same before and after, so the layout
 * does not jump.
 */
@Composable
internal fun RemoteImage(address: String?, fit: String, placeholder: Rgba, modifier: Modifier = Modifier, policy: ImagePolicy = ImagePolicy(), sink: ReportSink? = null) {
    var image by remember(address, policy.maxPixelSide) { mutableStateOf(address?.let { ImageStore.cached(it, policy.maxPixelSide) }?.asImageBitmap()) }
    LaunchedEffect(address, policy.maxPixelSide) {
        if (address != null && image == null) image = ImageStore.load(address, policy.maxDownloadBytes, policy) { reason ->
                sink?.add(HotlayoutReport.Kind.IgnoredInput, "image", reason)
            }?.asImageBitmap()
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
 * The box of a picture with a named height: it fills what the parent gives. A parent that sizes itself by its children
 * asks for the width the picture would have on its own, which for a named height is that height times the proportion
 * (a browser derives the same width from `aspect-ratio`); asked for no width at all, the picture is that wide too.
 */
private class NamedHeightPicture(private val ratio: Double, private val heightDp: Double) : MeasurePolicy {
    private fun IntrinsicMeasureScope.ownWidth(): Int = (heightDp * ratio * density).roundToInt()

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else ownWidth().coerceAtLeast(constraints.minWidth)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (heightDp * density).roundToInt().coerceAtLeast(constraints.minHeight)
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        return layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = ownWidth()
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
    val picture: @Composable () -> Unit = { RemoteImage(imageAddress(source, context), fit, placeholder, Modifier.fillMaxSize(), context.images, context.sink) }
    if (look.height != null) {
        // The node box gives the named height; the proportion is not read for the box, only for the width a shrink-to-fit
        // parent (a stack, a column of natural width) asks the picture for: the named height times the proportion.
        Layout(content = picture, modifier = semantics.fillMaxSize(), measurePolicy = NamedHeightPicture(ImageRules.ratio(node.props["aspectRatio"]), look.height!!))
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
