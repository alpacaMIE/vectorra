package com.vectorra.maps.offline

import android.database.sqlite.SQLiteDatabase
import com.vectorra.maps.VectorraBetaApi
import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileId
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResponse
import java.io.Closeable
import java.io.File
import kotlin.math.pow

@VectorraBetaApi("0.2.0-beta.1")
data class VectorraMbTilesBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
)

@VectorraBetaApi("0.2.0-beta.1")
data class VectorraMbTilesCenter(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double?
)

@VectorraBetaApi("0.2.0-beta.1")
enum class VectorraMbTilesTileRowScheme {
    TMS,
    XYZ
}

@VectorraBetaApi("0.2.0-beta.1")
data class VectorraMbTilesMetadata(
    val name: String?,
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: VectorraMbTilesBounds?,
    val center: VectorraMbTilesCenter?,
    val tileRowScheme: VectorraMbTilesTileRowScheme,
    val raw: Map<String, String>
) {
    val contentType: String
        get() = when (format) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "pbf", "mvt" -> "application/x-protobuf"
            else -> "image/png"
        }
}

@VectorraBetaApi("0.2.0-beta.1")
class VectorraMbTilesRasterSource internal constructor(
    val id: String,
    val file: File,
    val metadata: VectorraMbTilesMetadata,
    private val reader: VectorraMbTilesReader
) : Closeable {
    val minZoom: Int
        get() = metadata.minZoom
    val maxZoom: Int
        get() = metadata.maxZoom
    val tileSize: Int = DEFAULT_TILE_SIZE

    internal fun loadTile(request: TileRequest): TileResponse {
        val tileId = request.tileId
        if (tileId == null || !VectorraMbTilesTileRows.isValid(tileId)) {
            return TileResponse(request = request, statusCode = 404)
        }
        val bytes = reader.readTile(tileId, metadata.tileRowScheme)
        return if (bytes == null) {
            TileResponse(request = request, statusCode = 404)
        } else {
            TileResponse(
                request = request,
                statusCode = 200,
                headers = mapOf("Content-Type" to metadata.contentType),
                body = bytes,
                cacheStatus = TileCacheStatus.DISK
            )
        }
    }

    override fun close() {
        reader.close()
    }

    companion object {
        const val DEFAULT_SOURCE_ID = "mbtiles-source"
        const val DEFAULT_TILE_SIZE = 256

        fun open(
            file: File,
            id: String = DEFAULT_SOURCE_ID
        ): VectorraMbTilesRasterSource {
            require(id.isNotBlank()) { "MBTiles source id must not be blank." }
            require(file.exists() && file.isFile && file.canRead()) {
                "MBTiles file is not readable: ${file.absolutePath}"
            }

            val reader = VectorraMbTilesReader.open(file)
            return try {
                val rawMetadata = reader.readMetadata()
                val (minZoom, maxZoom) = reader.readZoomRange()
                val metadata = VectorraMbTilesMetadataParser.parse(
                    values = rawMetadata,
                    fallbackMinZoom = minZoom,
                    fallbackMaxZoom = maxZoom
                )
                VectorraMbTilesRasterSource(
                    id = id,
                    file = file.absoluteFile,
                    metadata = metadata,
                    reader = reader
                )
            } catch (error: Throwable) {
                reader.close()
                throw error
            }
        }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
class VectorraMbTilesVectorSource internal constructor(
    val id: String,
    val file: File,
    val metadata: VectorraMbTilesMetadata,
    private val reader: VectorraMbTilesReader
) : Closeable {
    val minZoom: Int
        get() = metadata.minZoom
    val maxZoom: Int
        get() = metadata.maxZoom
    val tileSize: Int = DEFAULT_TILE_SIZE

    internal fun loadTile(request: TileRequest): TileResponse {
        val tileId = request.tileId
        if (tileId == null || !VectorraMbTilesTileRows.isValid(tileId)) {
            return TileResponse(request = request, statusCode = 404)
        }
        val bytes = reader.readTile(tileId, metadata.tileRowScheme)
        return if (bytes == null) {
            TileResponse(request = request, statusCode = 404)
        } else {
            TileResponse(
                request = request,
                statusCode = 200,
                headers = mapOf("Content-Type" to metadata.contentType),
                body = bytes,
                cacheStatus = TileCacheStatus.DISK
            )
        }
    }

    override fun close() {
        reader.close()
    }

    companion object {
        const val DEFAULT_SOURCE_ID = "mbtiles-vector-source"
        const val DEFAULT_TILE_SIZE = 512

        fun open(
            file: File,
            id: String = DEFAULT_SOURCE_ID
        ): VectorraMbTilesVectorSource {
            require(id.isNotBlank()) { "MBTiles vector source id must not be blank." }
            require(file.exists() && file.isFile && file.canRead()) {
                "MBTiles file is not readable: ${file.absolutePath}"
            }

            val reader = VectorraMbTilesReader.open(file)
            return try {
                val rawMetadata = reader.readMetadata()
                val (minZoom, maxZoom) = reader.readZoomRange()
                val metadata = VectorraMbTilesVectorMetadataParser.parse(
                    values = rawMetadata,
                    fallbackMinZoom = minZoom,
                    fallbackMaxZoom = maxZoom
                )
                VectorraMbTilesVectorSource(
                    id = id,
                    file = file.absoluteFile,
                    metadata = metadata,
                    reader = reader
                )
            } catch (error: Throwable) {
                reader.close()
                throw error
            }
        }
    }
}

internal object VectorraMbTilesMetadataParser {
    private val supportedFormats = setOf("png", "jpg", "jpeg", "webp")

    fun parse(
        values: Map<String, String>,
        fallbackMinZoom: Int,
        fallbackMaxZoom: Int
    ): VectorraMbTilesMetadata {
        val normalized = values.mapKeys { it.key.lowercase() }
        val format = normalized["format"]
            ?.lowercase()
            ?.removePrefix("image/")
            ?.let { if (it == "jpg") "jpeg" else it }
            ?: "png"
        require(format in supportedFormats) {
            "Unsupported MBTiles raster format: $format"
        }

        val minZoom = normalized["minzoom"]?.toIntOrNull() ?: fallbackMinZoom
        val maxZoom = normalized["maxzoom"]?.toIntOrNull() ?: fallbackMaxZoom
        require(minZoom >= 0) { "MBTiles minzoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "MBTiles maxzoom must be greater than or equal to minzoom." }

        val scheme = if (normalized["scheme"].equals("xyz", ignoreCase = true)) {
            VectorraMbTilesTileRowScheme.XYZ
        } else {
            VectorraMbTilesTileRowScheme.TMS
        }

        return VectorraMbTilesMetadata(
            name = normalized["name"],
            format = format,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = normalized["bounds"]?.let(::parseBounds),
            center = normalized["center"]?.let(::parseCenter),
            tileRowScheme = scheme,
            raw = values
        )
    }

    fun parseBounds(value: String): VectorraMbTilesBounds? {
        val parts = value.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 4) return null
        return VectorraMbTilesBounds(parts[0], parts[1], parts[2], parts[3])
    }

    fun parseCenter(value: String): VectorraMbTilesCenter? {
        val parts = value.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size < 2 || parts[0] == null || parts[1] == null) return null
        return VectorraMbTilesCenter(
            longitude = parts[0] ?: return null,
            latitude = parts[1] ?: return null,
            zoom = parts.getOrNull(2)
        )
    }
}

internal object VectorraMbTilesVectorMetadataParser {
    private val supportedFormats = setOf("pbf", "mvt")

    fun parse(
        values: Map<String, String>,
        fallbackMinZoom: Int,
        fallbackMaxZoom: Int
    ): VectorraMbTilesMetadata {
        val normalized = values.mapKeys { it.key.lowercase() }
        val format = normalized["format"]
            ?.lowercase()
            ?.removePrefix("application/")
            ?.removePrefix("x-")
            ?.let { if (it == "protobuf") "pbf" else it }
            ?: "pbf"
        require(format in supportedFormats) {
            "Unsupported MBTiles vector format: $format"
        }

        val minZoom = normalized["minzoom"]?.toIntOrNull() ?: fallbackMinZoom
        val maxZoom = normalized["maxzoom"]?.toIntOrNull() ?: fallbackMaxZoom
        require(minZoom >= 0) { "MBTiles minzoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "MBTiles maxzoom must be greater than or equal to minzoom." }

        val scheme = if (normalized["scheme"].equals("xyz", ignoreCase = true)) {
            VectorraMbTilesTileRowScheme.XYZ
        } else {
            VectorraMbTilesTileRowScheme.TMS
        }

        return VectorraMbTilesMetadata(
            name = normalized["name"],
            format = format,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = normalized["bounds"]?.let(VectorraMbTilesMetadataParser::parseBounds),
            center = normalized["center"]?.let(VectorraMbTilesMetadataParser::parseCenter),
            tileRowScheme = scheme,
            raw = values
        )
    }
}

internal object VectorraMbTilesTileRows {
    fun isValid(tileId: TileId): Boolean {
        if (tileId.z !in 0..30 || tileId.x < 0 || tileId.y < 0) return false
        val dimension = 2.0.pow(tileId.z).toInt()
        return tileId.x < dimension && tileId.y < dimension
    }

    fun storageRow(z: Int, y: Int, scheme: VectorraMbTilesTileRowScheme): Int {
        require(z in 0..30) { "MBTiles zoom must be in 0..30." }
        require(y >= 0) { "MBTiles tile row must be greater than or equal to 0." }
        return when (scheme) {
            VectorraMbTilesTileRowScheme.XYZ -> y
            VectorraMbTilesTileRowScheme.TMS -> ((1 shl z) - 1) - y
        }
    }
}

internal class VectorraMbTilesReader private constructor(
    private val database: SQLiteDatabase
) : Closeable {
    fun readMetadata(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) {
                values[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return values
    }

    fun readZoomRange(): Pair<Int, Int> {
        database.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { cursor ->
            require(cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                "MBTiles package contains no tiles."
            }
            return cursor.getInt(0) to cursor.getInt(1)
        }
    }

    fun readTile(tileId: TileId, scheme: VectorraMbTilesTileRowScheme): ByteArray? {
        val storageRow = VectorraMbTilesTileRows.storageRow(tileId.z, tileId.y, scheme)
        database.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
            arrayOf(tileId.z.toString(), tileId.x.toString(), storageRow.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
    }

    override fun close() {
        database.close()
    }

    companion object {
        fun open(file: File): VectorraMbTilesReader {
            return VectorraMbTilesReader(
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            )
        }
    }
}
