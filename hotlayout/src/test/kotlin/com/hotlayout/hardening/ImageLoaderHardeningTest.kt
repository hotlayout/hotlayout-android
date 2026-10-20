package com.hotlayout.hardening

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.ImagePolicy
import com.hotlayout.JsonValue
import com.hotlayout.render.widgets.ImageStore
import com.hotlayout.support.Screens
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Limits of the picture loader: which addresses the default policy lets through, redirects, the
 * download cap (announced and streamed), the `data:` cap, the decoded size, and time.
 *
 * The loader itself is driven against a server on the loopback of the test machine (the default
 * policy would refuse that address, so these tests call the loader directly).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ImageLoaderHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // ---------------------------------------------------------------- a picture of any size, a few bytes long

    /** A grey PNG of [width] × [height]; all zero rows compress to almost nothing. */
    private fun png(width: Int, height: Int): ByteArray {
        fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val body = type.toByteArray(Charsets.US_ASCII) + data
            out.write(body)
            out.writeInt(CRC32().apply { update(body) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 0, 0, 0, 0)) } }.toByteArray()
        val deflater = Deflater(9)
        val compressed = ByteArrayOutputStream()
        val row = ByteArray(1 + width)
        val buffer = ByteArray(64 * 1024)
        repeat(height) {
            deflater.setInput(row)
            while (!deflater.needsInput()) compressed.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            chunk(this, "IHDR", header)
            chunk(this, "IDAT", compressed.toByteArray())
            chunk(this, "IEND", ByteArray(0))
        }
        return out.toByteArray()
    }

    private fun dataAddress(bytes: ByteArray) = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    // ---------------------------------------------------------------- a server on the loopback

    private class Server : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<String>()
        private val pool = Executors.newCachedThreadPool()
        private val routes = HashMap<String, (OutputStream) -> Unit>()

        fun route(path: String, answer: (OutputStream) -> Unit) { routes[path] = answer }

        fun address(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        init {
            pool.execute {
                while (!socket.isClosed) {
                    val client: Socket = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute {
                        client.use {
                            val reader = it.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            val path = line.split(' ').getOrNull(1) ?: "/"
                            while (true) {
                                val header = reader.readLine() ?: break
                                if (header.isEmpty()) break
                                headers.add(header)
                            }
                            requests.add(path)
                            runCatching { routes[path]?.invoke(it.getOutputStream()) }
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            pool.shutdownNow()
        }
    }

    private lateinit var server: Server

    @Before fun start() {
        ImageStore.reset()
        server = Server()
    }

    @After fun stop() {
        server.close()
        ImageStore.reset()
    }

    private fun OutputStream.head(status: String, vararg headers: String) {
        write(("HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        flush()
    }

    private fun load(address: String, maxBytes: Long = ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES): Bitmap? = runBlocking { ImageStore.load(address, maxBytes) }

    // ---------------------------------------------------------------- the default policy

    @Test fun theDefaultPolicyLetsOnlyNamedHttpsHostsThrough() {
        val refused = listOf(
            "http://example.com/a.png", "HTTPS://example.com/a.png", "https://127.0.0.1/a.png", "https://2130706433/a.png",
            "https://0x7f.0.0.1/a.png", "https://0177.0.0.1/a.png", "https://[::1]/a.png", "https://[::ffff:127.0.0.1]/a.png",
            "https://localhost/a.png", "https://intranet/a.png", "https://10.0.0.1/a.png", "https://169.254.169.254/latest",
            "https://user@127.0.0.1/a.png", "https://example.com@127.0.0.1/a.png", "https://example.123/a.png",
            "data:image/png;base64,AAAA", "file:///sdcard/a.png", "content://media/x", "javascript:alert(1)",
            "https://" + "a".repeat(2050) + ".com/", "https://example.com\\@127.0.0.1/", " https://example.com/a.png",
        )
        for (address in refused) assertNull(address, ImagePolicy.httpsOnly(address))
        for (address in listOf("https://cdn.example.com/a.png", "https://example.com:8443/a.png?x=1", "https://xn--80ak6aa92e.com/a.png")) {
            assertEquals(address, address, ImagePolicy.httpsOnly(address))
        }
    }

    /** An address that comes from data goes through the host's policy like a literal one. */
    @Test fun anAddressFromDataGoesThroughThePolicy() {
        val seen = CopyOnWriteArrayList<String>()
        val document = Screens.document(
            "a" to """{"id":"img","type":"Image","props":{"source":{"kind":"binding","path":"offer.image","fallback":"placeholder"},"aspectRatio":"1:1"}}""",
        )
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(
                    document, data = mapOf("offer.image" to JsonValue.Str("https://127.0.0.1/a.png")),
                    images = ImagePolicy(resolve = { seen.add(it); ImagePolicy.httpsOnly(it) }),
                )
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("https://127.0.0.1/a.png"), seen.distinct())
    }

    // ---------------------------------------------------------------- the loader

    @Test fun aSmallPictureLoads() {
        val picture = png(4, 4)
        server.route("/ok.png") { it.head("200 OK", "Content-Type: image/png", "Content-Length: ${picture.size}"); it.write(picture) }
        val bitmap = load(server.address("/ok.png"))
        assertNotNull(bitmap)
        assertEquals(4, bitmap!!.width)
    }

    @Test fun aRedirectIsNotFollowed() {
        val picture = png(4, 4)
        server.route("/from.png") { it.head("302 Found", "Location: ${server.address("/to.png")}", "Content-Length: 0") }
        server.route("/to.png") { it.head("200 OK", "Content-Length: ${picture.size}"); it.write(picture) }
        assertNull(load(server.address("/from.png")))
        assertEquals(listOf("/from.png"), server.requests.toList())
    }

    /**
     * The address of a picture comes from the document or the data. A request to it must not carry
     * the cookies of the host app (a `CookieHandler` the app installed for its own API) and must not
     * put the server's `Set-Cookie` into the app's jar. `HttpURLConnection` cannot be told to skip the
     * handler, so the default loader refuses to fetch while one is installed, and says why.
     */
    @Test fun aPictureRequestNeitherSendsNorStoresTheHostsCookies() {
        val previous = java.net.CookieHandler.getDefault()
        val jar = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        try {
            java.net.CookieHandler.setDefault(jar)
            val origin = java.net.URI("http://127.0.0.1:${server.socket.localPort}/")
            jar.cookieStore.add(origin, java.net.HttpCookie("session", "HOST-SESSION-SECRET").apply { path = "/"; version = 0 })
            val picture = png(4, 4)
            server.route("/c.png") {
                it.head("200 OK", "Content-Length: ${picture.size}", "Set-Cookie: tracker=PLANTED; Path=/")
                it.write(picture)
            }
            val reasons = ArrayList<String>()
            assertNull("the default loader fetches while the app has a CookieHandler", runBlocking { ImageStore.load(server.address("/c.png"), ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES, ImagePolicy()) { reasons.add(it) } })
            assertEquals("requests that reached the server", emptyList<String>(), server.requests.toList())
            assertEquals("cookies sent, cookies in the host's jar", emptyList<String>() to listOf("session"), server.headers.filter { h -> h.startsWith("Cookie:", ignoreCase = true) } to jar.cookieStore.cookies.map { c -> c.name })
            assertEquals("the reason is reported once per sink", 1, reasons.size)
            assertTrue(reasons.single(), "CookieHandler" in reasons.single() && "fetch" in reasons.single())
        } finally {
            java.net.CookieHandler.setDefault(previous)
        }
    }

    /** With the host's own transport the reader makes no request of its own, handler or not. */
    @Test fun aHostTransportIsUsedAndTheDefaultLoaderIsNotTouched() {
        val previous = java.net.CookieHandler.getDefault()
        try {
            java.net.CookieHandler.setDefault(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
            val picture = png(4, 4)
            val asked = ArrayList<Pair<String, Long>>()
            val policy = ImagePolicy(fetch = { address, cap -> asked.add(address to cap); picture })
            val bitmap = runBlocking { ImageStore.load("https://cdn.example.com/a.png", 1234, policy) }
            assertNotNull(bitmap)
            assertEquals(listOf("https://cdn.example.com/a.png" to 1234L), asked)
            assertEquals(emptyList<String>(), server.requests.toList())
            ImageStore.reset()
            val big = ImagePolicy(fetch = { _, _ -> ByteArray(2000) })
            assertNull("a transport that returns more than the cap", runBlocking { ImageStore.load("https://cdn.example.com/b.png", 1000, big) })
            ImageStore.reset()
            val slow = ImagePolicy(totalDeadlineMs = 300, fetch = { _, _ -> kotlinx.coroutines.delay(5000); picture })
            val started = System.nanoTime()
            assertNull("a transport that never ends", runBlocking { ImageStore.load("https://cdn.example.com/c.png", 1000, slow) })
            assertTrue((System.nanoTime() - started) / 1e9 < 4)
        } finally {
            java.net.CookieHandler.setDefault(previous)
        }
    }

    @Test fun theDefaultLoaderLoadsWithoutAHandlerAndSendsNoCookie() {
        val picture = png(4, 4)
        server.route("/n.png") { it.head("200 OK", "Content-Length: ${picture.size}"); it.write(picture) }
        assertNotNull(load(server.address("/n.png")))
        assertEquals(emptyList<String>(), server.headers.filter { h -> h.startsWith("Cookie:", ignoreCase = true) })
    }

    @Test fun aHugeSizeIsSampledDownToTheSideAndTheSideIsHostOverridable() {
        val wide = load(dataAddress(png(40_000, 4)))
        assertTrue("40000 wide is decoded at no more than 4096: ${wide?.width}", wide != null && wide.width <= 4096)
        ImageStore.reset()
        val small = runBlocking { ImageStore.load(dataAddress(png(1000, 800)), ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES, ImagePolicy(maxPixelSide = 256)) }
        assertTrue("sampled to 256: ${small?.width}", small != null && small.width <= 256 && small.height <= 256)
        ImageStore.reset()
        val thin = runBlocking { ImageStore.load(dataAddress(png(1000, 10)), ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES, ImagePolicy(maxPixelSide = 100)) }
        assertTrue("1000 wide is sampled to 100: ${thin?.width}", thin != null && thin.width <= 100)
    }

    @Test fun theDeadlineIsHostOverridable() {
        server.route("/drip2.png") {
            it.head("200 OK", "Content-Type: image/png")
            repeat(1000) { _ -> it.write(0); it.flush(); Thread.sleep(100) }
        }
        val started = System.nanoTime()
        val bitmap = runBlocking { ImageStore.load(server.address("/drip2.png"), ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES, ImagePolicy(totalDeadlineMs = 1500)) }
        assertNull(bitmap)
        assertTrue("took ${(System.nanoTime() - started) / 1e9} s", (System.nanoTime() - started) / 1e9 < 10)
    }

    /** A redirect is never followed, so the host's policy cannot be bypassed by one; also when it points to an allowed address. */
    @Test fun aRedirectToAnyAddressIsNotFollowed() {
        for (target in listOf("https://cdn.example.com/a.png", "http://127.0.0.1:1/a.png", "file:///etc/hosts", "/same-server.png")) {
            ImageStore.reset()
            server.requests.clear()
            server.route("/r.png") { it.head("301 Moved Permanently", "Location: $target", "Content-Length: 0") }
            server.route("/same-server.png") { it.head("200 OK"); it.write(png(4, 4)) }
            assertNull(target, load(server.address("/r.png")))
            assertEquals(target, listOf("/r.png"), server.requests.toList())
        }
    }

    @Test fun anAnnouncedSizeOverTheCapIsRefusedBeforeTheBody() {
        server.route("/big.png") {
            it.head("200 OK", "Content-Length: ${ImagePolicy.DEFAULT_MAX_DOWNLOAD_BYTES + 1}")
            // A body that never ends: reading it would hang the test.
            repeat(600) { _ -> it.write(0); it.flush(); Thread.sleep(100) }
        }
        val started = System.nanoTime()
        assertNull(load(server.address("/big.png")))
        assertTrue("the body was read", (System.nanoTime() - started) / 1e9 < 10)
    }

    @Test fun aStreamedBodyOverTheCapIsRefused() {
        val cap = 64L * 1024
        server.route("/stream.png") {
            it.head("200 OK", "Content-Type: image/png")
            val block = ByteArray(16 * 1024)
            repeat(64) { _ -> it.write(block) } // 1 MB without a length
        }
        assertNull(load(server.address("/stream.png"), cap))
        val picture = png(4, 4)
        server.route("/small.png") { it.head("200 OK"); it.write(picture) }
        assertNotNull("a body under the cap without a length still loads", load(server.address("/small.png"), cap))
    }

    @Test fun theCapAlsoHoldsForADataAddress() {
        val picture = png(64, 64)
        assertNotNull(load(dataAddress(picture), picture.size.toLong()))
        ImageStore.reset()
        assertNull(load(dataAddress(picture), picture.size.toLong() - 1))
    }

    /**
     * A picture of a few kilobytes can declare any size: 6000 × 6000 is 144 MB of pixels, more than
     * twice the whole memory cache. The decoded picture must be sampled down so that memory stays
     * bounded whatever the file says (a box on a phone is never wider than a few thousand pixels).
     */
    @Test fun aHugePictureIsDecodedAtABoundedSize() {
        val picture = png(6000, 6000)
        assertTrue("the file is small: ${picture.size}", picture.size < 1024 * 1024)
        val bitmap = load(dataAddress(picture))
        val pixels = bitmap?.let { it.width.toLong() * it.height } ?: 0
        assertTrue(
            "decoded at ${bitmap?.width} × ${bitmap?.height} (${bitmap?.byteCount} bytes); expected at most 4096 per side",
            bitmap != null && bitmap.width <= MAX_SIDE && bitmap.height <= MAX_SIDE && pixels <= MAX_SIDE.toLong() * MAX_SIDE,
        )
    }

    /**
     * `readTimeout` bounds the silence between two reads, not the download: a server that sends a
     * byte now and then holds a loader thread for as long as it likes. A picture needs a deadline
     * for the whole transfer.
     */
    @Test fun aServerThatDripsBytesCannotHoldTheLoaderForever() {
        server.route("/drip.png") {
            it.head("200 OK", "Content-Type: image/png")
            repeat(100_000) { _ -> it.write(0); it.flush(); Thread.sleep(250) }
        }
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<Bitmap?> { load(server.address("/drip.png")) }
        val ended = runCatching { result.get(TOTAL_DEADLINE_S + 15, TimeUnit.SECONDS); true }.getOrDefault(false)
        server.close() // ends the drip either way
        worker.shutdownNow()
        assertTrue("the download was still running after ${TOTAL_DEADLINE_S + 15} s", ended)
    }

    private companion object {
        const val MAX_SIDE = 4096
        const val TOTAL_DEADLINE_S = 30L
    }
}
