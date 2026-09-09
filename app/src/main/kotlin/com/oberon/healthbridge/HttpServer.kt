package com.oberon.healthbridge

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Un server HTTP piccolo abbastanza da starci dentro senza librerie: serve una
 * manciata di client sulla stessa rete di casa, non un sito. Niente
 * keep-alive, niente compressione, niente sessioni.
 */
class HttpServer(
    private val port: Int,
    private val route: (Request) -> Response,
) {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val client: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        val cookies: Map<String, String> by lazy {
            header("cookie")?.split(";")?.mapNotNull { piece ->
                val at = piece.indexOf('=')
                if (at <= 0) null
                else piece.substring(0, at).trim() to piece.substring(at + 1).trim()
            }?.toMap() ?: emptyMap()
        }

        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    class Response(
        val status: String = "200 OK",
        val type: String = "text/plain; charset=utf-8",
        val body: ByteArray? = null,
        val headers: List<Pair<String, String>> = emptyList(),
    ) {
        companion object {
            fun text(body: String, status: String = "200 OK") =
                Response(status = status, body = body.toByteArray(Charsets.UTF_8))

            /**
             * Il CORS aperto e' una scelta: a proteggere il dato e' la chiave,
             * e nessuna richiesta arriva a costruire una di queste risposte
             * senza averla presentata.
             */
            fun json(body: String, status: String = "200 OK") = Response(
                status = status,
                type = "application/json; charset=utf-8",
                body = body.toByteArray(Charsets.UTF_8),
                headers = listOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-store",
                ),
            )

            fun bytes(body: ByteArray, type: String, cache: Boolean = false) = Response(
                type = type,
                body = body,
                headers = if (cache) listOf("Cache-Control" to "public, max-age=31536000")
                else listOf("Cache-Control" to "no-store"),
            )

            fun notFound() = json("""{"state":"error","error":"nothing here"}""", "404 Not Found")

            fun denied(message: String) = json(
                """{"state":"error","error":${quote(message)}}""",
                "403 Forbidden",
            )
        }
    }

    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null

    /** Thread creati quando servono: chi chiede sono una dashboard e qualche browser. */
    private val pool = ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { work -> Thread(work, "healthbridge-http").apply { isDaemon = true } }

    private var accepter: Thread? = null

    val listening: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server

        accepter = Thread({
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (stop: IOException) {
                    break
                }

                // Meglio una porta in faccia che una coda infinita: se i thread
                // sono tutti occupati la connessione si chiude subito, e chi
                // chiedeva riprova da solo.
                if (pool.activeCount >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }

                pool.execute { serve(client) }
            }
        }, "healthbridge-http").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
        accepter = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.tcpNoDelay = true

            val input = client.getInputStream()
            val request = read(input, client) ?: return
            val response = try {
                route(request)
            } catch (failure: Throwable) {
                Response.json(
                    """{"state":"error","error":${quote(failure.message ?: "internal error")}}""",
                    "500 Internal Server Error",
                )
            }

            write(BufferedOutputStream(client.getOutputStream()), response)
        } catch (ignored: IOException) {
            // Un client che chiude a meta' e' la normalita': succede a ogni
            // ricarica di pagina.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun read(input: InputStream, client: Socket): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val at = header.indexOf(':')
            if (at <= 0) continue
            headers[header.substring(0, at).trim().lowercase()] =
                header.substring(at + 1).trim()
        }

        val target = parts[1]
        val split = target.indexOf('?')
        val path = if (split < 0) target else target.substring(0, split)
        val query = if (split < 0) emptyMap() else parseQuery(target.substring(split + 1))

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return null

        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) break
            got += n
        }

        return Request(
            method = parts[0].uppercase(),
            path = decode(path),
            query = query,
            headers = headers,
            body = body,
            client = client.inetAddress?.hostAddress ?: "?",
        )
    }

    /** Una riga di intestazione, letta byte a byte perche' il corpo che segue puo' non essere testo. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (c == '\n'.code) return buffer.toString().removeSuffix("\r")
            if (buffer.length > MAX_LINE) return null
            buffer.append(c.toChar())
        }
    }

    private fun write(out: OutputStream, response: Response) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append("\r\n")
        head.append("Content-Type: ").append(response.type).append("\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }

        head.append("Content-Length: ").append(response.body?.size ?: 0).append("\r\n")
        head.append("Connection: close\r\n\r\n")

        out.write(head.toString().toByteArray(Charsets.UTF_8))
        response.body?.let { out.write(it) }
        out.flush()
    }

    companion object {
        private const val MAX_CONNECTIONS = 16
        private const val MAX_BODY = 1 shl 16
        private const val MAX_LINE = 8192
        private const val READ_TIMEOUT_MS = 20_000

        fun parseQuery(raw: String): Map<String, String> = raw.split("&")
            .mapNotNull { piece ->
                if (piece.isEmpty()) return@mapNotNull null
                val at = piece.indexOf('=')
                if (at < 0) decode(piece) to ""
                else decode(piece.substring(0, at)) to decode(piece.substring(at + 1))
            }
            .toMap()

        fun decode(raw: String): String =
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

        /** Una stringa JSON, virgolette comprese. */
        fun quote(raw: String): String {
            val out = StringBuilder("\"")
            for (c in raw) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
