package com.hotlayout

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the network said to a request of the placement source. */
public sealed class PlacementResponse {
    /** 200 with a body of at most the allowed size. */
    public class Body(public val text: String, public val etag: String?) : PlacementResponse()

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
 * cookies, proxy and certificates; the default is `HttpURLConnection` over `https`, without cookies.
 */
public fun interface PlacementTransport {
    /** GETs [url], sending [etag] as `If-None-Match` when given; returns at most [maxBytes] of the body. Must not throw. */
    public suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse

    public companion object {
        internal const val CONNECT_TIMEOUT_MS = 10_000
        internal const val READ_TIMEOUT_MS = 15_000
        internal const val TOTAL_DEADLINE_MS = 20_000L

        /** `https` only, no redirects, no cookies, a deadline for the whole transfer. */
        public val Default: PlacementTransport = PlacementTransport { url, etag, maxBytes -> download(url, etag, maxBytes) }

        private suspend fun download(url: String, etag: String?, maxBytes: Int): PlacementResponse {
            if (!url.startsWith("https://")) return PlacementResponse.Unavailable
            // HttpURLConnection sends and stores through the app's own cookie handler: no cookies for these requests.
            if (CookieHandler.getDefault() != null) return PlacementResponse.Unavailable
            return withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(TOTAL_DEADLINE_MS) { runInterruptible { fetch(url, etag, maxBytes) } } ?: PlacementResponse.Unavailable
                } catch (_: IOException) {
                    PlacementResponse.Unavailable
                } catch (_: SecurityException) {
                    PlacementResponse.Unavailable
                }
            }
        }

        private fun fetch(address: String, etag: String?, maxBytes: Int): PlacementResponse {
            val connection = URL(address).openConnection() as? HttpURLConnection ?: return PlacementResponse.Unavailable
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            return try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> PlacementResponse.NotModified
                    HttpURLConnection.HTTP_NOT_FOUND -> PlacementResponse.NotFound
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong > maxBytes) return PlacementResponse.TooLarge
                        val bytes = connection.inputStream.use { readCapped(it, maxBytes) } ?: return PlacementResponse.TooLarge
                        PlacementResponse.Body(String(bytes, Charsets.UTF_8), connection.getHeaderField("ETag"))
                    }
                    else -> PlacementResponse.Unavailable
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
