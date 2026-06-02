package com.vectorra.maps.network

class BaiduTileInterceptor(
    private val markerQueryKey: String = "_kt_type_",
    private val markerValue: String = "bd",
    private val z18XOffset: Int = 4,
    private val z18YOffset: Int = 2
) : TileRequestInterceptor {
    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        val request = chain.request
        if (request.queryValue(markerQueryKey) != markerValue) {
            return chain.proceed()
        }

        val z = request.tileId?.z ?: request.queryValue("z")?.toIntOrNull()
        val x = request.tileId?.x ?: request.queryValue("x")?.toIntOrNull()
        val y = request.tileId?.y ?: request.queryValue("y")?.toIntOrNull()
        if (x == null || y == null || z == null || z != 18) {
            return chain.proceed()
        }

        val shiftedX = request.replaceQueryValue("x", (x + z18XOffset).toString())
        val shifted = request.copy(url = shiftedX)
            .replaceQueryValue("y", (y + z18YOffset).toString())
        return chain.proceed(request.copy(url = shifted))
    }
}
