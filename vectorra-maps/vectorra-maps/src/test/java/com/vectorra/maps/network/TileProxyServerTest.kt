package com.vectorra.maps.network

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class TileProxyServerTest {
    @Test
    fun proxyFetchesRemoteTileThroughCacheAndScheduler() {
        val upstream = TestHttpServer { path ->
            200 to "tile:$path".toByteArray()
        }
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-cache").toFile()
        upstream.start()
        try {
            val server = TileProxyServer(
                cacheDirectory = cacheRoot,
                initialConfig = TileNetworkConfig(
                    cachePolicy = TileCachePolicy(memoryMaxBytes = 1024, diskMaxBytes = 1024),
                    maxConcurrentRequests = 1
                )
            )
            server.use {
                val template = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "http://127.0.0.1:${upstream.port}/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )
                val url = template
                    .replace("{z}", "3")
                    .replace("{x}", "4")
                    .replace("{y}", "5")

                assertEquals("tile:/tiles/3/4/5.png", readUrl(url))
                assertEquals(1, upstream.calls.get())
                assertEquals("tile:/tiles/3/4/5.png", readUrl(url))
                assertEquals(1, upstream.calls.get())
            }
        } finally {
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyForwardsErrorStatusWithoutCaching() {
        val upstream = TestHttpServer { 404 to "missing".toByteArray() }
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-error-cache").toFile()
        upstream.start()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val template = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "http://127.0.0.1:${upstream.port}/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )
                val url = template
                    .replace("{z}", "3")
                    .replace("{x}", "4")
                    .replace("{y}", "5")

                assertEquals(404, statusCode(url))
                assertEquals(404, statusCode(url))
                assertEquals(2, upstream.calls.get())
            }
        } finally {
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyRunsInterceptorsAndForwardsHeadersBeforeNativeFetch() {
        val upstream = TestHttpServer { path ->
            200 to "intercepted:$path".toByteArray()
        }
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-interceptor-cache").toFile()
        upstream.start()
        try {
            val interceptedRequests = Collections.synchronizedList(mutableListOf<TileRequest>())
            val server = TileProxyServer(
                cacheDirectory = cacheRoot,
                initialConfig = TileNetworkConfig(
                    interceptors = listOf(
                        TileRequestInterceptor { chain ->
                            interceptedRequests += chain.request
                            chain.proceed(
                                chain.request.copy(
                                    headers = chain.request.headers + ("X-Intercepted" to "yes")
                                )
                            )
                        }
                    )
                )
            )
            server.use {
                val template = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "http://127.0.0.1:${upstream.port}/tiles/{z}/{x}/{y}.png",
                    headers = mapOf("X-Source" to "source-header")
                )
                val url = template
                    .replace("{z}", "6")
                    .replace("{x}", "7")
                    .replace("{y}", "8")

                assertEquals("intercepted:/tiles/6/7/8.png", readUrl(url))
                assertEquals("http://127.0.0.1:${upstream.port}/tiles/6/7/8.png", interceptedRequests.single().url)
                assertEquals("source-header", upstream.headers.single()["X-Source"])
                assertEquals("yes", upstream.headers.single()["X-Intercepted"])
            }
        } finally {
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyFetchesLocalProviderTile() {
        val cacheRoot = Files.createTempDirectory("vectorra-tile-proxy-local-provider-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val template = it.proxyTemplateForLocalProvider(
                    sourceId = "mbtiles",
                    layerId = "mbtiles-layer",
                    provider = LocalTileProvider { request ->
                        TileResponse(
                            request = request,
                            statusCode = 200,
                            headers = mapOf("Content-Type" to "image/png"),
                            body = "local:${request.tileId?.z}/${request.tileId?.x}/${request.tileId?.y}".toByteArray()
                        )
                    }
                )
                val url = template
                    .replace("{z}", "9")
                    .replace("{x}", "10")
                    .replace("{y}", "11")

                assertEquals("local:9/10/11", readUrl(url))
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyForwardsLocalProviderMissingTileStatus() {
        val cacheRoot = Files.createTempDirectory("vectorra-tile-proxy-local-provider-missing-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val template = it.proxyTemplateForLocalProvider(
                    sourceId = "mbtiles",
                    layerId = "mbtiles-layer",
                    provider = LocalTileProvider { request ->
                        TileResponse(request = request, statusCode = 404)
                    }
                )
                val url = template
                    .replace("{z}", "9")
                    .replace("{x}", "10")
                    .replace("{y}", "11")

                assertEquals(404, statusCode(url))
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun keepsLocalFileTemplatesOutOfProxy() {
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-local-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val template = "file:///tmp/my_offline_map/{z}/{y}/{x}.jpg"
                assertEquals(
                    template,
                    it.proxyTemplateFor(
                        sourceId = "offline",
                        layerId = "layer",
                        templateUrl = template,
                        headers = emptyMap()
                    )
                )
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    private fun readUrl(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun statusCode(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private class TestHttpServer(
        private val response: (String) -> Pair<Int, ByteArray>
    ) : AutoCloseable {
        val calls = AtomicInteger()
        val headers = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        private val closed = AtomicBoolean(false)
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int
            get() = socket.localPort
        private lateinit var thread: Thread

        fun start() {
            thread = Thread({ acceptLoop() }, "tile-proxy-test-upstream").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            closed.set(true)
            socket.close()
        }

        private fun acceptLoop() {
            while (!closed.get() && !socket.isClosed) {
                try {
                    socket.accept().use(::handle)
                } catch (_: Exception) {
                    if (!closed.get()) continue
                }
            }
        }

        private fun handle(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine().orEmpty()
            val requestHeaders = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(":")
                if (separator > 0) {
                    requestHeaders[line.substring(0, separator)] = line.substring(separator + 1).trim()
                }
            }
            calls.incrementAndGet()
            headers += requestHeaders
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?").orEmpty()
            val (status, body) = response(path)
            val statusText = if (status == 200) "OK" else "Not Found"
            val headers = buildString {
                append("HTTP/1.1 $status $statusText\r\n")
                append("Content-Type: image/png\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            client.getOutputStream().use { output ->
                output.write(headers)
                output.write(body)
            }
        }
    }
}
