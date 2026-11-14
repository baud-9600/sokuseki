package de.asdfghasdfgh.sokuseki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpSenderTest {

    @Test
    fun `posts json with the OwnTracks identification headers`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var method = ""
        var requestBody = ""
        var userHeader = ""
        var deviceHeader = ""

        val worker = thread {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                method = reader.readLine().orEmpty()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val name = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    when (name.lowercase()) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "x-limit-u" -> userHeader = value
                        "x-limit-d" -> deviceHeader = value
                    }
                }
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(chars, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                requestBody = String(chars, 0, read)
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: close\r\n\r\n[]"
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
            }
        }

        val config = TrackConfig(
            url = "http://127.0.0.1:${server.localPort}/api/webhook/test",
            user = "mk",
            device = "kompakt",
            tid = "kt",
            intervalMinutes = 5,
        )
        val payload = """{"_type":"location","lat":1.0,"lon":2.0}"""

        val result = HttpSender().send(config, payload)
        worker.join(5_000)
        server.close()

        assertTrue(result.success)
        assertEquals(200, result.code)
        assertTrue(method.startsWith("POST "))
        assertEquals(payload, requestBody)
        assertEquals("mk", userHeader)
        assertEquals("kompakt", deviceHeader)
    }
}
