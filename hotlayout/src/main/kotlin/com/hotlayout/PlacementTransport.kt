package com.hotlayout

import com.hotlayout.placements.PlacementRules
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
 * cookies, proxy and certificates; the default speaks `https` directly, without cookies and without redirects.
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

        private const val MAX_HEADER_BYTES = 64 * 1024

        /**
         * `https` only, no redirects, no cookies, one deadline for the whole request. The request is written on
         * the socket itself: `HttpURLConnection` sends and stores cookies through the app's process-wide cookie
         * handler, and has no way to opt out of it for a single connection.
         */
        public val Default: PlacementTransport = PlacementTransport { url, etag, maxBytes ->
            download(url, etag, maxBytes, REQUEST_DEADLINE_MS, ::connectTls)
        }

        private fun connectTls(host: String, port: Int, timeoutMs: Int): Socket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                val parameters: SSLParameters = ssl.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = parameters
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                return ssl
            } catch (error: Throwable) {
                runCatching { plain.close() }
                throw error
            }
        }

        /** [connect] opens the socket to `host:port`; tests give a plain one. */
        internal suspend fun download(
            url: String,
            etag: String?,
            maxBytes: Int,
            deadlineMs: Long,
            connect: (host: String, port: Int, timeoutMs: Int) -> Socket,
        ): PlacementResponse {
            if (!url.startsWith("https://")) return PlacementResponse.Unavailable
            val address = try { URL(url) } catch (_: IOException) { return PlacementResponse.Unavailable }
            if (address.host.isNullOrEmpty() || address.userInfo != null) return PlacementResponse.Unavailable
            val port = if (address.port == -1) 443 else address.port
            val path = address.file.ifEmpty { "/" }
            val hostHeader = if (address.port == -1) address.host else "${address.host}:${address.port}"
            return coroutineScope {
                var socket: Socket? = null
                val work = async(Dispatchers.IO) {
                    try {
                        val opened = connect(address.host, port, deadlineMs.toInt().coerceAtLeast(1))
                        socket = opened
                        opened.soTimeout = deadlineMs.toInt().coerceAtLeast(1)
                        exchange(opened, hostHeader, path, etag, maxBytes)
                    } catch (_: IOException) {
                        PlacementResponse.Unavailable
                    } catch (_: RuntimeException) {
                        PlacementResponse.Unavailable
                    }
                }
                val answer = withTimeoutOrNull(deadlineMs) { work.await() }
                if (answer == null) {
                    // The deadline passed: closing the socket releases a read that waits for a server that is silent or drips.
                    runCatching { socket?.close() }
                    work.cancel()
                    PlacementResponse.Unavailable
                } else {
                    runCatching { socket?.close() }
                    answer
                }
            }
        }

        /** One GET over an open [socket]: `Connection: close`, no cookies, no `Accept-Encoding`, the status and the body read here. */
        internal fun exchange(socket: Socket, hostHeader: String, path: String, etag: String?, maxBytes: Int): PlacementResponse {
            val request = StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("Accept: application/json\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n")
            if (etag != null && etag.all { it.code in 0x20..0x7E }) request.append("If-None-Match: ").append(etag).append("\r\n")
            request.append("\r\n")
            socket.getOutputStream().apply { write(request.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            val input = socket.getInputStream().buffered()
            val status = readLine(input, MAX_HEADER_BYTES)?.let { statusOf(it) } ?: return PlacementResponse.Unavailable
            var contentLength = -1L
            var chunked = false
            var responseEtag: String? = null
            var budget = MAX_HEADER_BYTES
            while (true) {
                val line = readLine(input, budget) ?: return PlacementResponse.Unavailable
                if (line.isEmpty()) break
                budget -= line.length + 2
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val value = line.substring(colon + 1).trim()
                when (line.substring(0, colon).trim().lowercase()) {
                    "content-length" -> contentLength = value.toLongOrNull()?.takeIf { it >= 0 } ?: return PlacementResponse.Unavailable
                    "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
                    "etag" -> responseEtag = value
                }
            }
            return when (status) {
                304 -> PlacementResponse.NotModified
                404 -> PlacementResponse.NotFound
                200 -> {
                    if (!chunked && contentLength > maxBytes) return PlacementResponse.TooLarge
                    val bytes = (if (chunked) readChunked(input, maxBytes) else readBody(input, contentLength, maxBytes))
                        ?: return PlacementResponse.Unavailable
                    if (bytes.size > maxBytes) PlacementResponse.TooLarge else PlacementResponse.Body(bytes, responseEtag)
                }
                else -> PlacementResponse.Unavailable
            }
        }

        private fun statusOf(line: String): Int? {
            val parts = line.split(' ')
            if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) return null
            return parts[1].toIntOrNull()
        }

        /** One header line without its line end; `null` when the stream ends or the line is longer than [limit]. */
        private fun readLine(input: InputStream, limit: Int): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
                if (out.length > limit) return null
            }
        }

        /** The body by `Content-Length` or until the stream ends; one byte over [maxBytes] is returned as such so the caller can tell. */
        private fun readBody(input: InputStream, length: Long, maxBytes: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > maxBytes) return out.toByteArray()
                if (length >= 0 && out.size() >= length) break
            }
            if (length >= 0 && out.size() < length) return null
            return out.toByteArray()
        }

        private fun readChunked(input: InputStream, maxBytes: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val sizeLine = readLine(input, 64) ?: return null
                val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: return null
                if (size == 0L) return out.toByteArray()
                if (out.size() + size > maxBytes) return ByteArray(maxBytes + 1)
                var left = size
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) return null
                    out.write(buffer, 0, read)
                    left -= read
                }
                if (readLine(input, 4) != "") return null
            }
        }
    }
}
