package com.vectorra.maps.network

fun interface TileRequestInterceptor {
    suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse
}

interface TileRequestInterceptorChain {
    val request: TileRequest

    suspend fun proceed(request: TileRequest = this.request): TileResponse
}

fun interface TileRequestExecutor {
    suspend fun execute(request: TileRequest): TileResponse
}

class TileRequestInterceptorChainExecutor(
    private val interceptors: List<TileRequestInterceptor>,
    private val terminalExecutor: TileRequestExecutor
) {
    suspend fun execute(request: TileRequest): TileResponse {
        return RealChain(0, request).proceed(request)
    }

    private inner class RealChain(
        private val index: Int,
        override val request: TileRequest
    ) : TileRequestInterceptorChain {
        override suspend fun proceed(request: TileRequest): TileResponse {
            return if (index >= interceptors.size) {
                terminalExecutor.execute(request)
            } else {
                interceptors[index].intercept(RealChain(index + 1, request))
            }
        }
    }
}
