package com.vectorra.maps.network

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun interface LocalTileProvider {
    fun load(request: TileRequest): TileResponse
}

class TileProxyServer(
    private val cacheDirectory: File,
    initialConfig: TileNetworkConfig = TileNetworkConfig()
) : Closeable {
    private data class Registration(
        val sourceId: String?,
        val layerId: String?,
        val templateUrl: String?,
        val headers: Map<String, String>,
        val resourceType: TileResourceType,
        val localProvider: LocalTileProvider?
    )

    private val closed = AtomicBoolean(false)
    private val registrations = ConcurrentHashMap<String, Registration>()
    private val clientThreadIds = AtomicInteger()
    private val clientExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rocky-tile-proxy-client-${clientThreadIds.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var scheduler: TileRequestScheduler = schedulerFor(initialConfig)
    @Volatile private var config: TileNetworkConfig = initialConfig
    @Volatile private var cacheStore: TileCacheStore = TileCacheStore(cacheDirectory, initialConfig.cachePolicy)

    fun updateConfig(config: TileNetworkConfig) {
        this.config = config
        cacheStore = TileCacheStore(cacheDirectory, config.cachePolicy)
        val next = schedulerFor(config)
        val previous = scheduler
        scheduler = next
        previous.close()
    }

    internal fun cacheStatus(): TileCacheStoreStatus {
        return cacheStore.status()
    }

    internal fun clearCache() {
        cacheStore.clear()
    }

    fun proxyTemplateFor(
        sourceId: String?,
        layerId: String?,
        templateUrl: String,
        headers: Map<String, String>,
        resourceType: TileResourceType = TileResourceType.RASTER
    ): String {
        if (!isRemoteTemplate(templateUrl) || isProxyTemplate(templateUrl) || !hasTilePlaceholders(templateUrl)) {
            return templateUrl
        }
        val socket = startIfNeeded()
        val id = stableRegistrationId(
            sourceId = sourceId,
            layerId = layerId,
            resourceType = resourceType,
            fallback = "remote|$templateUrl"
        )
        registrations[id] = Registration(
            sourceId = sourceId,
            layerId = layerId,
            templateUrl = templateUrl,
            headers = headers,
            resourceType = resourceType,
            localProvider = null
        )
        return "http://127.0.0.1:${socket.localPort}/tile/$id?z={z}&x={x}&y={y}"
    }

    internal fun proxyTemplateForLocalProvider(
        sourceId: String?,
        layerId: String?,
        resourceType: TileResourceType = TileResourceType.RASTER,
        provider: LocalTileProvider
    ): String {
        val socket = startIfNeeded()
        val id = stableRegistrationId(
            sourceId = sourceId,
            layerId = layerId,
            resourceType = resourceType,
            fallback = "local-provider"
        )
        registrations[id] = Registration(
            sourceId = sourceId,
            layerId = layerId,
            templateUrl = null,
            headers = emptyMap(),
            resourceType = resourceType,
            localProvider = provider
        )
        return "http://127.0.0.1:${socket.localPort}/tile/$id?z={z}&x={x}&y={y}"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.close()
            clientExecutor.shutdownNow()
            registrations.clear()
            serverSocket?.close()
            serverSocket = null
        }
    }

    private fun startIfNeeded(): ServerSocket {
        serverSocket?.let { return it }
        synchronized(this) {
            serverSocket?.let { return it }
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            serverThread = Thread({ acceptLoop(socket) }, "rocky-tile-proxy").apply {
                isDaemon = true
                start()
            }
            return socket
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed.get() && !socket.isClosed) {
            try {
                val client = socket.accept()
                try {
                    clientExecutor.execute {
                        client.use(::handleSocket)
                    }
                } catch (_: RejectedExecutionException) {
                    client.use {
                        writeResponse(
                            it,
                            503,
                            "text/plain",
                            "Tile proxy is shutting down.".toByteArray(),
                            closeConnection = true
                        )
                    }
                }
            } catch (_: Exception) {
                if (!closed.get()) {
                    continue
                }
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            handleSocketRequest(socket)
        } catch (e: Exception) {
            runCatching {
                writeResponse(
                    socket,
                    503,
                    "text/plain",
                    (e.message ?: "Tile proxy request failed.").toByteArray(),
                    closeConnection = true
                )
            }
        }
    }

    private fun handleSocketRequest(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (!closed.get() && !socket.isClosed) {
            val result = handleSingleRequest(socket, reader)
            if (!result.keepAlive) return
        }
    }

    private data class RequestResult(val keepAlive: Boolean)

    private fun handleSingleRequest(
        socket: Socket,
        reader: java.io.BufferedReader
    ): RequestResult {
        val requestLine = reader.readLine() ?: return RequestResult(keepAlive = false)
        if (requestLine.isBlank()) return RequestResult(keepAlive = true)

        val requestHeaders = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return RequestResult(keepAlive = false)
            if (line.isEmpty()) break
            val separator = line.indexOf(":")
            if (separator > 0) {
                requestHeaders[line.substring(0, separator).lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }

        val shouldKeepAlive = shouldKeepAlive(requestLine, requestHeaders)
        fun respond(statusCode: Int, contentType: String, body: ByteArray): RequestResult {
            val closeConnection = !shouldKeepAlive || statusCode !in 200..299
            writeResponse(socket, statusCode, contentType, body, closeConnection)
            return RequestResult(keepAlive = !closeConnection)
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") {
            return respond(405, "text/plain", "Method not allowed".toByteArray())
        }

        val uri = runCatching { URI(parts[1]) }.getOrNull()
        if (uri == null || !uri.path.startsWith("/tile/")) {
            return respond(404, "text/plain", "Not found".toByteArray())
        }

        val id = uri.path.removePrefix("/tile/")
        val registration = registrations[id]
        if (registration == null) {
            return respond(404, "text/plain", "Unknown tile source".toByteArray())
        }

        val query = parseQuery(uri.rawQuery.orEmpty())
        val z = query["z"]
        val x = query["x"]
        val y = query["y"]
        if (z.isNullOrBlank() || x.isNullOrBlank() || y.isNullOrBlank()) {
            return respond(400, "text/plain", "Missing tile coordinate".toByteArray())
        }

        val tileId = TileId(
            z = z.toIntOrNull() ?: -1,
            x = x.toIntOrNull() ?: -1,
            y = y.toIntOrNull() ?: -1
        )
        val provider = registration.localProvider
        if (provider != null) {
            val response = provider.load(
                TileRequest(
                    sourceId = registration.sourceId,
                    layerId = registration.layerId,
                    url = "vectorra-local://tile/$id/${tileId.z}/${tileId.x}/${tileId.y}",
                    tileId = tileId,
                    resourceType = registration.resourceType
                )
            )
            val status = if (response.statusCode in 100..599) response.statusCode else 502
            val contentType = response.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?: inferContentType(response.request.url)
            return respond(status, contentType, response.body)
        }

        val templateUrl = registration.templateUrl
        if (templateUrl == null) {
            return respond(404, "text/plain", "Unknown tile source".toByteArray())
        }
        val targetUrl = templateUrl
            .replace("\${z}", z)
            .replace("\${x}", x)
            .replace("\${y}", y)
            .replace("{z}", z)
            .replace("{x}", x)
            .replace("{y}", y)
        val request = TileRequest(
            sourceId = registration.sourceId,
            layerId = registration.layerId,
            url = targetUrl,
            headers = registration.headers,
            tileId = tileId,
            resourceType = registration.resourceType
        )
        val response = executeWithCache(request, tileId)
        val status = if (response.statusCode in 100..599) response.statusCode else 502
        val contentType = response.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?: inferContentType(targetUrl)
        return respond(status, contentType, response.body)
    }

    private fun executeWithCache(request: TileRequest, tileId: TileId): TileResponse {
        val currentCacheStore = cacheStore
        if (!currentCacheStore.isCacheable(request)) {
            return scheduler.enqueue(request, priority = tilePriority(tileId)).awaitOrError(60_000L)
        }

        currentCacheStore.get(request)?.let { return it }

        val response = scheduler.enqueue(request, priority = tilePriority(tileId)).awaitOrError(60_000L)
        currentCacheStore.put(response)
        return response
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        closeConnection: Boolean
    ) {
        val reason = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Tile Response"
        }
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: ${if (closeConnection) "close" else "keep-alive"}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        output.write(headers)
        output.write(body)
        output.flush()
    }

    private fun schedulerFor(config: TileNetworkConfig): TileRequestScheduler {
        val chain = TileRequestInterceptorChainExecutor(
            interceptors = config.interceptors,
            terminalExecutor = ResilientTileRequestExecutor(HttpUrlTileRequestExecutor())
        )
        return TileRequestScheduler(
            executor = TileRequestExecutor { request -> chain.execute(request) },
            maxConcurrentRequests = config.maxConcurrentRequests,
            maxQueuedRequests = config.maxQueuedRequests
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val index = part.indexOf("=")
                if (index < 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, index), "UTF-8")
                val value = URLDecoder.decode(part.substring(index + 1), "UTF-8")
                key to value
            }
            .toMap()
    }

    private fun tilePriority(tileId: TileId): Int {
        return tileId.z.coerceAtLeast(0)
    }

    internal fun isProxyTemplate(templateUrl: String): Boolean {
        val port = serverSocket?.localPort ?: return false
        return templateUrl.startsWith("http://127.0.0.1:$port/tile/") ||
            templateUrl.startsWith("http://localhost:$port/tile/")
    }

    private fun stableRegistrationId(
        sourceId: String?,
        layerId: String?,
        resourceType: TileResourceType,
        fallback: String
    ): String {
        val source = sourceId.orEmpty().trim()
        val layer = layerId.orEmpty().trim()
        val identity = if (source.isNotEmpty() || layer.isNotEmpty()) {
            "ids|$source|$layer|${resourceType.name}"
        } else {
            "fallback|${resourceType.name}|$fallback"
        }
        return "v1-${sha256(identity)}"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun shouldKeepAlive(requestLine: String, headers: Map<String, String>): Boolean {
        val protocol = requestLine.split(" ").getOrNull(2).orEmpty().uppercase(Locale.US)
        val connection = headers["connection"].orEmpty().lowercase(Locale.US)
        return when {
            connection.split(",").map { it.trim() }.any { it == "close" } -> false
            protocol == "HTTP/1.0" -> connection.split(",").map { it.trim() }.any { it == "keep-alive" }
            else -> true
        }
    }

    private fun inferContentType(url: String): String {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }

    private fun isRemoteTemplate(templateUrl: String): Boolean {
        val lower = templateUrl.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun hasTilePlaceholders(templateUrl: String): Boolean {
        return (templateUrl.contains("{z}") || templateUrl.contains("\${z}")) &&
            (templateUrl.contains("{x}") || templateUrl.contains("\${x}")) &&
            (templateUrl.contains("{y}") || templateUrl.contains("\${y}"))
    }

    private fun TileRequestHandle.awaitOrError(timeoutMillis: Long): TileResponse {
        return try {
            await(timeoutMillis)
        } catch (e: TimeoutException) {
            cancel()
            TileResponse(request = request, statusCode = 504, errorMessage = e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel()
            TileResponse(request = request, statusCode = 499, errorMessage = e.message)
        } catch (e: CancellationException) {
            TileResponse(request = request, statusCode = 499, errorMessage = e.message)
        } catch (e: ExecutionException) {
            TileResponse(request = request, statusCode = 599, errorMessage = e.cause?.message ?: e.message)
        }
    }
}
