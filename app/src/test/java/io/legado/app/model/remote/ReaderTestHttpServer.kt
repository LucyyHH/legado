package io.legado.app.model.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Tiny loopback HTTP fixture using only the java.net API available to Android unit tests. */
internal class ReaderTestHttpServer private constructor(address: InetSocketAddress) {
    private val socket = ServerSocket().apply { bind(address) }
    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress
    private lateinit var handler: (ReaderTestHttpExchange) -> Unit
    private var worker: Thread? = null
    private val failures = CopyOnWriteArrayList<Throwable>()

    companion object {
        fun create(address: InetSocketAddress, @Suppress("UNUSED_PARAMETER") backlog: Int) = ReaderTestHttpServer(address)
    }

    fun createContext(@Suppress("UNUSED_PARAMETER") path: String, handler: (ReaderTestHttpExchange) -> Unit) {
        this.handler = handler
    }

    fun start() {
        worker = thread(isDaemon = true, name = "Reader HTTP fixture") {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: Exception) { break }
                connection.soTimeout = 10_000
                try {
                    val exchange = ReaderTestHttpExchange(connection)
                    try {
                        handler(exchange)
                    } catch (error: Throwable) {
                        failures += error
                    } finally {
                        exchange.close()
                    }
                } catch (error: Throwable) {
                    failures += error
                    connection.close()
                }
            }
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        socket.close()
        worker?.join(1000)
        failures.firstOrNull()?.let { throw AssertionError("HTTP fixture handler failed", it) }
    }
}

internal class ReaderTestHeaders {
    private val values = linkedMapOf<String, MutableList<String>>()
    fun add(name: String, value: String) { values.getOrPut(name.lowercase()) { mutableListOf() }.add(value) }
    fun getFirst(name: String): String? = values[name.lowercase()]?.firstOrNull()
    fun entries(): List<Pair<String, String>> = values.flatMap { (key, items) -> items.map { key to it } }
}

internal class ReaderTestHttpExchange(private val socket: Socket) {
    val requestURI: URI
    val requestHeaders = ReaderTestHeaders()
    val responseHeaders = ReaderTestHeaders()
    val requestBody: ByteArrayInputStream
    val responseBody = ByteArrayOutputStream()
    private var status = 500
    private var responseLength: Long? = null
    private var closed = false

    init {
        val input = socket.getInputStream()
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1 || value == 10) break
                if (value != 13) bytes.write(value)
            }
            return bytes.toString("UTF-8")
        }
        val first = line().split(' ')
        require(first.size >= 2) { "invalid HTTP request" }
        requestURI = URI(first[1])
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator > 0) requestHeaders.add(header.substring(0, separator), header.substring(separator + 1).trim())
        }
        val size = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
        require(size in 0..1_048_576)
        val body = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(body, read, size - read)
            check(count > 0) { "incomplete HTTP request body" }
            read += count
        }
        requestBody = ByteArrayInputStream(body)
    }

    fun sendResponseHeaders(status: Int, size: Long) {
        this.status = status
        this.responseLength = size
    }

    fun close() {
        if (closed) return
        closed = true
        socket.use {
            val body = responseBody.toByteArray()
            val headers = buildString {
                append("HTTP/1.1 $status Fixture\r\nContent-Length: ${responseLength ?: body.size.toLong()}\r\nConnection: close\r\n")
                responseHeaders.entries().forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply { write(headers); write(body); flush() }
        }
    }
}
