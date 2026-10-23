package com.hotlayout

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The default transport against a plain local server: statuses, redirects, size and the one deadline. */
class PlacementTransportTest {
    private fun ask(deadlineMs: Long = 5_000, maxBytes: Int = 1024, etag: String? = null, serve: (Socket) -> Unit): Pair<PlacementResponse, String> {
        val server = ServerSocket(0)
        var seen = ""
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val lines = ArrayList<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                    seen = lines.joinToString("\n")
                    serve(socket)
                }
            } catch (_: Exception) {
            }
        }.apply { start() }
        val answer = runBlocking {
            PlacementTransport.download("https://storage.example/x.json", etag, maxBytes, deadlineMs) { _, _, _ -> Socket("127.0.0.1", server.localPort) }
        }
        server.close()
        thread.join(2_000)
        return answer to seen
    }

    private fun OutputStream.say(text: String) { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }

    @Test fun aRedirectIsNotFollowedAndIsNoAnswer() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val (answer, _) = ask { it.getOutputStream().say("HTTP/1.1 $status Moved\r\nLocation: https://evil.example/\r\nContent-Length: 0\r\n\r\n") }
            assertEquals("$status", PlacementResponse.Unavailable, answer)
        }
    }

    @Test fun statusesBodiesAndTheEtagGoThroughAndTheRequestCarriesOnlyWhatItShould() {
        val (ok, seen) = ask(etag = "\"v1\"") { it.getOutputStream().say("HTTP/1.1 200 OK\r\nETag: \"v2\"\r\nContent-Length: 3\r\n\r\nabc") }
        assertTrue(ok is PlacementResponse.Body)
        assertEquals("abc", (ok as PlacementResponse.Body).text)
        assertEquals("\"v2\"", ok.etag)
        assertTrue(seen, seen.contains("If-None-Match: \"v1\""))
        assertTrue(seen, seen.startsWith("GET /x.json HTTP/1.1"))
        assertEquals(PlacementResponse.NotModified, ask { it.getOutputStream().say("HTTP/1.1 304 Not Modified\r\n\r\n") }.first)
        assertEquals(PlacementResponse.NotFound, ask { it.getOutputStream().say("HTTP/1.1 404 Not Found\r\n\r\n") }.first)
        assertEquals(PlacementResponse.Unavailable, ask { it.getOutputStream().say("HTTP/1.1 503 x\r\n\r\n") }.first)
    }

    @Test fun chunkedAndUntilCloseBodiesAreRead() {
        val (chunked, _) = ask { it.getOutputStream().say("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n") }
        assertEquals("abcde", (chunked as PlacementResponse.Body).text)
        val (closed, _) = ask { it.getOutputStream().say("HTTP/1.1 200 OK\r\n\r\nhello") }
        assertEquals("hello", (closed as PlacementResponse.Body).text)
    }

    @Test fun aBodyAboveTheLimitIsTooLargeWhatEverWayItIsSent() {
        assertEquals(PlacementResponse.TooLarge, ask(maxBytes = 4) { it.getOutputStream().say("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabcde") }.first)
        assertEquals(PlacementResponse.TooLarge, ask(maxBytes = 4) { it.getOutputStream().say("HTTP/1.1 200 OK\r\n\r\nabcde") }.first)
        assertEquals(PlacementResponse.TooLarge, ask(maxBytes = 4) { it.getOutputStream().say("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabcde\r\n0\r\n\r\n") }.first)
        assertTrue(ask(maxBytes = 5) { it.getOutputStream().say("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabcde") }.first is PlacementResponse.Body)
    }

    @Test fun aBodyThatEndsEarlyIsNoAnswer() {
        assertEquals(PlacementResponse.Unavailable, ask { it.getOutputStream().say("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc") }.first)
    }

    @Test fun theDeadlineCoversTheBodyNotOnlyTheHeaders() {
        val started = System.nanoTime()
        val (answer, _) = ask(deadlineMs = 400) {
            it.getOutputStream().say("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n")
            repeat(50) { _ -> it.getOutputStream().say("x"); Thread.sleep(100) }
        }
        assertEquals(PlacementResponse.Unavailable, answer)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test fun aServerThatNeverAnswersIsNoAnswerAfterTheDeadline() {
        val (answer, _) = ask(deadlineMs = 300) { Thread.sleep(2_000) }
        assertEquals(PlacementResponse.Unavailable, answer)
    }
}
