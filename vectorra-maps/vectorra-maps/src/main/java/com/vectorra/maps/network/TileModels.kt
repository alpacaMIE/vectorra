package com.vectorra.maps.network

data class TileRequest(
    val sourceId: String? = null,
    val layerId: String? = null,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val tileId: TileId? = null,
    val resourceType: TileResourceType = TileResourceType.UNKNOWN,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(url.isNotBlank()) { "TileRequest.url must not be blank" }
        require(method.isNotBlank()) { "TileRequest.method must not be blank" }
    }
}

data class TileId(
    val z: Int,
    val x: Int,
    val y: Int,
    val scheme: TileScheme = TileScheme.XYZ
)

enum class TileScheme {
    XYZ,
    TMS,
    WMTS
}

enum class TileResourceType {
    RASTER,
    VECTOR,
    DEM,
    TILES3D,
    GLYPH,
    SPRITE,
    UNKNOWN
}

data class TileResponse(
    val request: TileRequest,
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val cacheStatus: TileCacheStatus = TileCacheStatus.MISS,
    val errorMessage: String? = null
)

enum class TileCacheStatus {
    MISS,
    MEMORY,
    DISK
}
