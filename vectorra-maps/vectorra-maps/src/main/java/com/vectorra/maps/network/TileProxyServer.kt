package com.vectorra.maps.network

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TileProxyServer(
    private val cacheDirectory: File,
    initialConfig: TileNetworkConfig = TileNetworkConfig()
) : Closeable {
    private data class Registration(
        val sourceId: String?,
        val layerId: String?,
        val templateUrl: String,
        val headers: Map<String, String>,
        val resourceType: TileResourceType
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
    @Volatile private var memoryCache: TileMemoryCache = TileMemoryCache(initialConfig.cachePolicy.memoryMaxBytes)
    @Volatile private var diskCache: TileDiskCache = TileDiskCache(cacheDirectory, initialConfig.cachePolicy.diskMaxBytes)

    fun updateConfig(config: TileNetworkConfig) {
        this.config = config
        memoryCache = TileMemoryCache(config.cachePolicy.memoryMaxBytes)
        diskCache = TileDiskCache(cacheDirectory, config.cachePolicy.diskMaxBytes)
        val next = schedulerFor(config)
        val previous = scheduler
        scheduler = next
        previous.close()
    }

    fun proxyTemplateFor(
        sourceId: String?,
        layerId: String?,
        templateUrl: String,
        headers: Map<String, String>,
        resourceType: TileResourceType = TileResourceType.RASTER
    ): String {
        if (!isRemoteTemplate(templateUrl) || isProxyTemplate(templateUrl)) {
            return templateUrl
        }
        val socket = startIfNeeded()
        val id = UUID.randomUUID().toString()
        registrations[id] = Registration(
            sourceId = sourceId,
            layerId = layerId,
            templateUrl = templateUrl,
            headers = headers,
            resourceType = resourceType
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
                        writeResponse(it, 503, "text/plain", "Tile proxy is shutting down.".toByteArray())
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
                    (e.message ?: "Tile proxy request failed.").toByteArray()
                )
            }
        }
    }

    private fun handleSocketRequest(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") {
            writeResponse(socket, 405, "text/plain", "Method not allowed".toByteArray())
            return
        }

        val uri = runCatching { URI(parts[1]) }.getOrNull()
        if (uri == null || !uri.path.startsWith("/tile/")) {
            writeResponse(socket, 404, "text/plain", "Not found".toByteArray())
            return
        }

        val id = uri.path.removePrefix("/tile/")
        val registration = registrations[id]
        if (registration == null) {
            writeResponse(socket, 404, "text/plain", "Unknown tile source".toByteArray())
            return
        }

        val query = parseQuery(uri.rawQuery.orEmpty())
        val z = query["z"]
        val x = query["x"]
        val y = query["y"]
        if (z.isNullOrBlank() || x.isNullOrBlank() || y.isNullOrBlank()) {
            writeResponse(socket, 400, "text/plain", "Missing tile coordinate".toByteArray())
            return
        }

        val targetUrl = registration.templateUrl
            .replace("{z}", z)
            .replace("{x}", x)
            .replace("{y}", y)
            .replace("\${z}", z)
            .replace("\${x}", x)
            .replace("\${y}", y)

        val tileId = TileId(
            z = z.toIntOrNull() ?: -1,
            x = x.toIntOrNull() ?: -1,
            y = y.toIntOrNull() ?: -1
        )
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
        writeResponse(socket, status, contentType, response.body)
    }

    private fun executeWithCache(request: TileRequest, tileId: TileId): TileResponse {
        val currentConfig = config
        if (!currentConfig.cachePolicy.isCacheable(request)) {
            return scheduler.enqueue(request, priority = tilePriority(tileId)).awaitOrError(60_000L)
        }

        val key = TileCacheKeys.from(request)
        memoryCache.get(key, request)?.let { return it }
        diskCache.get(key)?.let { body ->
            val cached = TileResponse(
                request = request,
                statusCode = 200,
                body = body,
                cacheStatus = TileCacheStatus.DISK
            )
            memoryCache.put(key, cached)
            return cached
        }

        val response = scheduler.enqueue(request, priority = tilePriority(tileId)).awaitOrError(60_000L)
        if (currentConfig.cachePolicy.isSuccessful(response)) {
            memoryCache.put(key, response)
            diskCache.put(key, response.body)
        }
        return response
    }

    private fun writeResponse(socket: Socket, statusCode: Int, contentType: String, body: ByteArray) {
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
            append("Connection: close\r\n")
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

    private fun isProxyTemplate(templateUrl: String): Boolean {
        val port = serverSocket?.localPort ?: return false
        return templateUrl.startsWith("http://127.0.0.1:$port/tile/") ||
            templateUrl.startsWith("http://localhost:$port/tile/")
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
