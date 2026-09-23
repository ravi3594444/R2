package ai.wakey.android.llm

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Minimal scripted HTTP/1.1 server for client tests. Unit tests compile against android.jar, where
 * the JDK's `com.sun.net.httpserver` isn't visible, so this speaks just enough HTTP over a socket.
 */
internal class FakeHttpServer : Closeable {
    class Reply(val status: Int, val body: String, val delayMs: Long = 0)
    class Received(val path: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    val replies = ConcurrentLinkedQueue<Reply>()
    val received = ConcurrentLinkedQueue<Received>()
    val port: Int get() = socket.localPort

    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) } }
            }
        }
    }

    private fun handle(client: Socket) = client.use {
        val input = BufferedInputStream(it.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { line -> line.isNotEmpty() } }
            .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        received += Received(requestLine.split(' ')[1], headers, body.decodeToString())
        val reply = replies.poll() ?: Reply(500, "no scripted reply")
        if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
        val bytes = reply.body.toByteArray()
        val out = it.getOutputStream()
        out.write(
            "HTTP/1.1 ${reply.status} Scripted\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
