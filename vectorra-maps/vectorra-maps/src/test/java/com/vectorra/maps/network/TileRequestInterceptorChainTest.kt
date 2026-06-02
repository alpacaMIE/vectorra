package com.vectorra.maps.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TileRequestInterceptorChainTest {
    @Test
    fun executesInterceptorsInOrderAndTerminalReceivesMutatedRequest() {
        val seen = mutableListOf<String>()
        val executor = TileRequestInterceptorChainExecutor(
            interceptors = listOf(
                TileRequestInterceptor { chain ->
                    seen += "first-before"
                    val response = chain.proceed(
                        chain.request.copy(headers = chain.request.headers + ("X-First" to "1"))
                    )
                    seen += "first-after"
                    response
                },
                UrlRewriteTileInterceptor { request ->
                    seen += "rewrite"
                    request.copy(url = "https://tiles.example/rewrite.png")
                }
            ),
            terminalExecutor = TileRequestExecutor { request ->
                seen += "terminal"
                TileResponse(
                    request = request,
                    statusCode = 200,
                    body = request.url.toByteArray()
                )
            }
        )

        val response = runSuspend {
            executor.execute(TileRequest(url = "https://tiles.example/original.png"))
        }

        assertEquals(listOf("first-before", "rewrite", "terminal", "first-after"), seen)
        assertEquals("https://tiles.example/rewrite.png", response.request.url)
        assertEquals("1", response.request.headers["X-First"])
        assertEquals("https://tiles.example/rewrite.png", response.body.decodeToString())
    }

    @Test
    fun retriesTransientResponses() {
        var attempts = 0
        val executor = TileRequestInterceptorChainExecutor(
            interceptors = listOf(RetryTileInterceptor(maxAttempts = 3)),
            terminalExecutor = TileRequestExecutor { request ->
                attempts += 1
                val statusCode = if (attempts < 3) 503 else 200
                TileResponse(request, statusCode)
            }
        )

        val response = runSuspend {
            executor.execute(TileRequest(url = "https://tiles.example/0/0/0.png"))
        }

        assertEquals(3, attempts)
        assertEquals(200, response.statusCode)
        assertEquals("3", response.request.metadata["retryAttempt"])
    }

    @Test
    fun injectsDefaultAndSourceHeadersWithoutOverwritingRequestHeaders() {
        val interceptor = HeaderInjectingTileInterceptor(
            defaultHeaders = mapOf("User-Agent" to "Vectorra", "X-Default" to "1"),
            sourceHeaders = mapOf("base" to mapOf("Authorization" to "Bearer token"))
        )
        val response = runSuspend {
            interceptor.intercept(
                TerminalChain(
                    TileRequest(
                        sourceId = "base",
                        url = "https://tiles.example/{z}/{x}/{y}.png",
                        headers = mapOf("User-Agent" to "Custom")
                    )
                )
            )
        }

        assertEquals("Custom", response.request.headers["User-Agent"])
        assertEquals("1", response.request.headers["X-Default"])
        assertEquals("Bearer token", response.request.headers["Authorization"])
    }

    @Test
    fun redactsSensitiveQueryValuesInLogs() {
        val events = mutableListOf<TileLogEvent>()
        val interceptor = RedactingTileLogger(TileRequestLogger { events += it })
        val response = runSuspend {
            interceptor.intercept(
                TerminalChain(
                    TileRequest(
                        sourceId = "tdt",
                        url = "https://tiles.example/wmts?tk=secret&ak=baidu&TileMatrix=10&token=abc"
                    )
                )
            )
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, events.size)
        assertTrue(events.all { "<redacted>" in it.redactedUrl })
        assertFalse(events.any { "secret" in it.redactedUrl || "baidu" in it.redactedUrl || "abc" in it.redactedUrl })
    }

    @Test
    fun redactsSignedProxyQueryValuesInLogs() {
        val events = mutableListOf<TileLogEvent>()
        val interceptor = RedactingTileLogger(TileRequestLogger { events += it })
        runSuspend {
            interceptor.intercept(
                TerminalChain(
                    TileRequest(
                        sourceId = "proxy",
                        url = "https://tiles.example/proxy?url=https%3A%2F%2Fprovider.example%2Ftile.png&api_key=api-secret&sign=signed-value"
                    )
                )
            )
        }

        assertTrue(events.all { "<redacted>" in it.redactedUrl })
        assertFalse(events.any { "provider.example" in it.redactedUrl || "api-secret" in it.redactedUrl || "signed-value" in it.redactedUrl })
    }

    @Test
    fun rewritesEligibleSuperResolutionRequests() {
        val interceptor = SuperResolutionTileInterceptor(
            enabled = true,
            proxyBaseUrl = "https://earth.example/superres?url=",
            minimumZoom = 8,
            tdtTokenReplacement = "server-token"
        )
        val response = runSuspend {
            interceptor.intercept(
                TerminalChain(
                    TileRequest(
                        url = "https://t0.tianditu.gov.cn/img_w/wmts?TileMatrix=10&TileRow=3&TileCol=5&tk=browser-token&_kt_srs_layer_=tdt&_kt_srs_=true",
                        tileId = TileId(z = 10, x = 5, y = 3)
                    )
                )
            )
        }

        assertTrue(response.request.url.startsWith("https://earth.example/superres?url="))
        assertTrue("server-token" in java.net.URLDecoder.decode(response.request.url, "UTF-8"))
    }

    @Test
    fun keepsIneligibleSuperResolutionRequestsUnchanged() {
        val request = TileRequest(
            url = "https://tiles.example/7/1/2.png?_kt_srs_=true",
            tileId = TileId(z = 7, x = 1, y = 2)
        )
        val response = runSuspend {
            SuperResolutionTileInterceptor(
                enabled = true,
                proxyBaseUrl = "https://earth.example/superres?url=",
                minimumZoom = 8
            ).intercept(TerminalChain(request))
        }

        assertEquals(request.url, response.request.url)
    }

    @Test
    fun shiftsGansuWmtsTileRowsOnlyForMatchingRequests() {
        val interceptor = WmtsTileRowShiftInterceptor()
        val response = runSuspend {
            interceptor.intercept(
                TerminalChain(
                    TileRequest(
                        url = "https://gansu.tianditu.gov.cn/a/wmtsoperations/b?Request=GetTile&TileMatrix=10&TileCol=823&TileRow=412"
                    )
                )
            )
        }

        assertTrue("TileRow=156" in response.request.url)
        assertTrue("TileCol=823" in response.request.url)
    }

    @Test
    fun shiftsBaiduZ18RequestsOnlyWhenMarked() {
        val response = runSuspend {
            BaiduTileInterceptor().intercept(
                TerminalChain(
                    TileRequest(
                        url = "https://wprd04.is.autonavi.com/appmaptile?x=10&y=20&z=18&style=6&_kt_type_=bd"
                    )
                )
            )
        }

        assertTrue("x=14" in response.request.url)
        assertTrue("y=22" in response.request.url)
        assertTrue("_kt_type_=bd" in response.request.url)
    }

    @Test
    fun keepsUnmarkedAndNonZ18BaiduRequestsUnchanged() {
        val interceptor = BaiduTileInterceptor()
        val unmarked = TileRequest(
            url = "https://wprd04.is.autonavi.com/appmaptile?x=10&y=20&z=18&style=6"
        )
        val nonZ18 = TileRequest(
            url = "https://wprd04.is.autonavi.com/appmaptile?x=10&y=20&z=17&style=6&_kt_type_=bd"
        )

        val unmarkedResponse = runSuspend { interceptor.intercept(TerminalChain(unmarked)) }
        val nonZ18Response = runSuspend { interceptor.intercept(TerminalChain(nonZ18)) }

        assertEquals(unmarked.url, unmarkedResponse.request.url)
        assertEquals(nonZ18.url, nonZ18Response.request.url)
    }

    @Test
    fun returnsConfiguredHeadersForSource() {
        val config = TileNetworkConfig(
            defaultHeaders = mapOf("User-Agent" to "Vectorra", "X-Default" to "1"),
            sourceHeaders = mapOf("dem" to mapOf("X-Source" to "terrain"))
        )

        assertEquals(
            mapOf("User-Agent" to "Vectorra", "X-Default" to "1", "X-Source" to "terrain"),
            config.headersFor("dem")
        )
    }

    private class TerminalChain(
        override val request: TileRequest
    ) : TileRequestInterceptorChain {
        override suspend fun proceed(request: TileRequest): TileResponse {
            return TileResponse(request = request, statusCode = 200)
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    result
                        .onSuccess { value = it }
                        .onFailure { failure = it }
                }
            }
        )
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
