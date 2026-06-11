package com.vectorra.maps.network

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileProxyServerTest {
    @Test
    fun proxyUsesStableRegistrationKeyForSameSourceLayerAndTemplate() {
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-stable-key-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val first = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "https://example.test/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )
                val second = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "https://example.test/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )

                assertEquals(first, second)
                assertTrue(it.isProxyTemplate(first))
                assertEquals(proxyTileId(first), proxyTileId(second))
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyUsesDifferentRegistrationKeysForDifferentLayers() {
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-distinct-key-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val first = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer-a",
                    templateUrl = "https://example.test/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )
                val second = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer-b",
                    templateUrl = "https://example.test/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )

                assertNotEquals(proxyTileId(first), proxyTileId(second))
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

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
                    templateUrl = "http://127.0.0.1:${upstream.port}/tiles/\${z}/\${x}/\${y}.png",
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
    fun proxyKeepsHttp11ConnectionAliveAcrossRequests() {
        val upstream = TestHttpServer { path ->
            200 to "tile:$path".toByteArray()
        }
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-keep-alive-cache").toFile()
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
                val base = URI(template.replace("{z}", "3").replace("{x}", "4").replace("{y}", "5"))
                Socket(base.host, base.port).use { socket ->
                    socket.soTimeout = 2_000
                    val output = socket.getOutputStream()
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)

                    output.write("GET ${base.rawPath}?z=3&x=4&y=5 HTTP/1.1\r\nHost: ${base.host}\r\n\r\n".toByteArray())
                    output.flush()
                    val first = readRawResponse(reader)
                    assertEquals(200, first.statusCode)
                    assertEquals("keep-alive", first.headers["connection"])
                    assertEquals("tile:/tiles/3/4/5.png", first.body)

                    output.write("GET ${base.rawPath}?z=3&x=4&y=6 HTTP/1.1\r\nHost: ${base.host}\r\n\r\n".toByteArray())
                    output.flush()
                    val second = readRawResponse(reader)
                    assertEquals(200, second.statusCode)
                    assertEquals("keep-alive", second.headers["connection"])
                    assertEquals("tile:/tiles/3/4/6.png", second.body)
                }
            }
        } finally {
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun proxyClosesConnectionWhenClientRequestsClose() {
        val upstream = TestHttpServer { path ->
            200 to "tile:$path".toByteArray()
        }
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-close-cache").toFile()
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
                val url = URI(template.replace("{z}", "3").replace("{x}", "4").replace("{y}", "5"))
                Socket(url.host, url.port).use { socket ->
                    socket.soTimeout = 2_000
                    val output = socket.getOutputStream()
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    output.write(
                        (
                            "GET ${url.rawPath}?z=3&x=4&y=5 HTTP/1.1\r\n" +
                                "Host: ${url.host}\r\n" +
                                "Connection: close\r\n" +
                                "\r\n"
                            ).toByteArray()
                    )
                    output.flush()

                    val response = readRawResponse(reader)
                    assertEquals(200, response.statusCode)
                    assertEquals("close", response.headers["connection"])
                    assertEquals("tile:/tiles/3/4/5.png", response.body)
                    assertNull(reader.readLine())
                }
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

    @Test
    fun keepsRemoteManifestUrlsOutOfProxy() {
        val cacheRoot = Files.createTempDirectory("rocky-tile-proxy-manifest-cache").toFile()
        try {
            val server = TileProxyServer(cacheDirectory = cacheRoot)
            server.use {
                val template = "http://readymap.org/readymap/tiles/1.0.0/7/"
                assertEquals(
                    template,
                    it.proxyTemplateFor(
                        sourceId = "readymap",
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

    private fun proxyTileId(template: String): String {
        return template.substringAfter("/tile/").substringBefore("?")
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRawResponse(reader: java.io.BufferedReader): RawHttpResponse {
        val statusLine = reader.readLine()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val separator = line.indexOf(":")
            if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = reader.read(body, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        return RawHttpResponse(statusCode, headers, body.concatToString(0, read))
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
