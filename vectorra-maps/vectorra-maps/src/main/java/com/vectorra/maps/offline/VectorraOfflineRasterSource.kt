package com.vectorra.maps.offline

import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.source.VectorraRasterTileSource
import java.io.File

object VectorraOfflineRasterSource {
    const val DEFAULT_DIRECTORY_NAME = "my_offline_map"
    const val DEFAULT_SOURCE_ID = "offline-source"
    const val DEFAULT_LAYER_ID = "layer"
    const val DEFAULT_TILE_EXTENSION = "jpg"
    const val DEFAULT_MIN_ZOOM = 0
    const val DEFAULT_MAX_ZOOM = 14
    const val DEFAULT_TILE_SIZE = 256

    fun directory(
        rootDirectory: File,
        id: String = DEFAULT_SOURCE_ID,
        tileExtension: String = DEFAULT_TILE_EXTENSION,
        minZoom: Int = DEFAULT_MIN_ZOOM,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
        tileSize: Int = DEFAULT_TILE_SIZE
    ): VectorraRasterTileSource {
        require(rootDirectory.exists() && rootDirectory.isDirectory) {
            "Offline raster root directory does not exist: ${rootDirectory.absolutePath}"
        }
        require(tileExtension.isNotBlank()) { "Offline raster tile extension must not be blank." }
        require('.' !in tileExtension) { "Offline raster tile extension must not include a dot." }

        return VectorraRasterTileSource(
            id = id,
            templateUrl = templateUrl(rootDirectory, tileExtension),
            tileSize = tileSize,
            minZoom = minZoom,
            maxZoom = maxZoom,
            scheme = TileScheme.XYZ
        )
    }

    fun templateUrl(
        rootDirectory: File,
        tileExtension: String = DEFAULT_TILE_EXTENSION
    ): String {
        val rootUrl = rootDirectory.absoluteFile.toURI().toString().trimEnd('/')
        return "$rootUrl/{z}/{y}/{x}.$tileExtension"
    }
}
