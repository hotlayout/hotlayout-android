package com.hotlayout

import com.hotlayout.placements.PlacementRules
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** What the network said to a request of the placement source. */
public sealed class PlacementResponse {
    /** 200 with a body of at most the allowed size: the raw bytes, which the reader decodes itself. */
    public class Body(public val bytes: ByteArray, public val etag: String?) : PlacementResponse() {
        public constructor(text: String, etag: String?) : this(text.toByteArray(Charsets.UTF_8), etag)

        /** The bytes as text, with replacement characters for what is not UTF-8. For reading by a person; the reader decodes strictly. */
        public val text: String get() = String(bytes, Charsets.UTF_8)
    }

    /** 200 with a body above the allowed size. */
    public data object TooLarge : PlacementResponse()

    /** 304: the kept copy is still right. */
    public data object NotModified : PlacementResponse()

    /** 404. */
    public data object NotFound : PlacementResponse()

    /** Anything else: no connection, a timeout, 5xx, 403, a redirect. */
    public data object Unavailable : PlacementResponse()
}

/**
 * The transport of the placement source: one GET. Use your own to give the requests a client with its own
 * cookies, proxy and certificates; the default is `HttpsURLConnection`: `https` only, no redirects, no credentials.
 * It is a plain system connection, so it goes through the system proxy and the app's Network Security Config, and
 * it may carry cookies the app itself stored for the storage host in a process-wide `CookieHandler`.
 */
public fun interface PlacementTransport {
    /**
     * GETs [url], sending [etag] as `If-None-Match` when given; returns at most [maxBytes] of the body. Must not throw.
     * A request has [REQUEST_DEADLINE_MS] from its start to the end of its body, and a redirect is no answer.
     */
    public suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse

    public companion object {
        /** The time one request has, from its start to the end of its body. */
        public const val REQUEST_DEADLINE_MS: Long = PlacementRules.REQUEST_DEADLINE_MS

        /**
         * `https` only, no redirects, no caches, no credentials, one deadline for the whole request. A process-wide
         * `CookieHandler` of the app is not a reason to refuse: the request goes through and may carry cookies the
         * app stored for the storage host.
         */
        public val Default: PlacementTransport = PlacementTransport { url, etag, maxBytes ->
            download(url, etag, maxBytes, REQUEST_DEADLINE_MS) { it.openConnection() as? HttpsURLConnection }
        }

        /** [open] makes the connection for an address; tests give a plain one to a local server. */
        internal suspend fun download(
            url: String,
            etag: String?,
            maxBytes: Int,
            deadlineMs: Long,
            open: (URL) -> HttpURLConnection?,
        ): PlacementResponse {
            if (!url.startsWith("https://")) return PlacementResponse.Unavailable
            val address = try { URL(url) } catch (_: IOException) { return PlacementResponse.Unavailable }
            if (address.host.isNullOrEmpty() || address.userInfo != null) return PlacementResponse.Unavailable
            val endsAt = System.nanoTime() + deadlineMs * 1_000_000
            return coroutineScope {
                var connection: HttpURLConnection? = null
                val work = async(Dispatchers.IO) {
                    try {
                        val opened = open(address) ?: return@async PlacementResponse.Unavailable
                        connection = opened
                        exchange(opened, etag, maxBytes, endsAt)
                    } catch (_: IOException) {
                        PlacementResponse.Unavailable
                    } catch (_: RuntimeException) {
                        PlacementResponse.Unavailable
                    }
                }
                val answer = withTimeoutOrNull(deadlineMs) { work.await() }
                // Past the deadline, disconnecting releases a read that waits for a server that is silent or drips.
                runCatching { connection?.disconnect() }
                if (answer == null) work.cancel()
                answer ?: PlacementResponse.Unavailable
            }
        }

        private fun remainingMs(endsAt: Long): Int = ((endsAt - System.nanoTime()) / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

        private fun exchange(connection: HttpURLConnection, etag: String?, maxBytes: Int, endsAt: Long): PlacementResponse {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = false
            connection.requestMethod = "GET"
            connection.connectTimeout = remainingMs(endsAt)
            connection.readTimeout = remainingMs(endsAt)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (etag != null && etag.all { it.code in 0x20..0x7E }) connection.setRequestProperty("If-None-Match", etag)
            return when (connection.responseCode) {
                304 -> PlacementResponse.NotModified
                404 -> PlacementResponse.NotFound
                200 -> {
                    val length = connection.contentLengthLong
                    if (length > maxBytes) return PlacementResponse.TooLarge
                    val stream = connection.inputStream
                    var finished = false
                    val bytes = try {
                        readBody(stream, maxBytes, endsAt)?.also { finished = it.size <= maxBytes && (length < 0 || it.size.toLong() >= length) }
                    } finally {
                        // A stream closed before its fixed length is read drains the rest and blocks on a slow
                        // server past the deadline: an unfinished read drops the connection first.
                        if (!finished) runCatching { connection.disconnect() }
                        runCatching { stream.close() }
                    } ?: return PlacementResponse.Unavailable
                    when {
                        bytes.size > maxBytes -> PlacementResponse.TooLarge
                        length >= 0 && bytes.size < length -> PlacementResponse.Unavailable
                        else -> PlacementResponse.Body(bytes, connection.getHeaderField("ETag"))
                    }
                }
                else -> PlacementResponse.Unavailable
            }
        }

        /** Up to one byte over [maxBytes], so the caller can tell; `null` when the deadline passes while reading. */
        private fun readBody(input: java.io.InputStream, maxBytes: Int, endsAt: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() <= maxBytes) {
                if (System.nanoTime() >= endsAt) return null
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
