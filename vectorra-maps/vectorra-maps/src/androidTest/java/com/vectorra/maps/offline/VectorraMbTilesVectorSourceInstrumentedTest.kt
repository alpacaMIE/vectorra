package com.vectorra.maps.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileId
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorraMbTilesVectorSourceInstrumentedTest {
    @Test
    fun opensSqliteMbTilesAndReadsVectorTileBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = createVectorMbTiles(context)

        VectorraMbTilesVectorSource.open(file, id = "local-vector").use { source ->
            val response = source.loadTile(
                TileRequest(
                    sourceId = "local-vector",
                    layerId = "roads-line",
                    url = "vectorra-local://tile/local-vector/2/1/2",
                    tileId = TileId(z = 2, x = 1, y = 2),
                    resourceType = TileResourceType.VECTOR
                )
            )

            assertEquals("Local MVT", source.metadata.name)
            assertEquals("pbf", source.metadata.format)
            assertEquals(2, source.minZoom)
            assertEquals(2, source.maxZoom)
            assertEquals(VectorraMbTilesTileRowScheme.TMS, source.metadata.tileRowScheme)
            assertEquals(200, response.statusCode)
            assertEquals(TileCacheStatus.DISK, response.cacheStatus)
            assertEquals("application/x-protobuf", response.headers["Content-Type"])
            assertTrue(mvtTile("roads").contentEquals(response.body))
        }
    }

    private fun createVectorMbTiles(context: Context): File {
        val file = File(context.cacheDir, "vectorra-instrumented-vector.mbtiles")
        if (file.exists()) {
            file.delete()
        }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.use { database ->
            database.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            database.execSQL(
                "CREATE TABLE tiles (" +
                    "zoom_level INTEGER, " +
                    "tile_column INTEGER, " +
                    "tile_row INTEGER, " +
                    "tile_data BLOB)"
            )
            insertMetadata(database, "name", "Local MVT")
            insertMetadata(database, "format", "pbf")
            insertMetadata(database, "minzoom", "2")
            insertMetadata(database, "maxzoom", "2")
            insertMetadata(database, "scheme", "tms")
            database.compileStatement(
                "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.bindLong(1, 2)
                statement.bindLong(2, 1)
                statement.bindLong(3, 1)
                statement.bindBlob(4, mvtTile("roads"))
                statement.executeInsert()
            }
        }
        return file
    }

    private fun insertMetadata(database: SQLiteDatabase, name: String, value: String) {
        database.compileStatement("INSERT INTO metadata (name, value) VALUES (?, ?)").use { statement ->
            statement.bindString(1, name)
            statement.bindString(2, value)
            statement.executeInsert()
        }
    }

    private fun mvtTile(layerName: String): ByteArray {
        return message {
            bytes(
                3,
                message {
                    string(1, layerName)
                    bytes(
                        2,
                        message {
                            uint(1, 1)
                            uint(3, 1)
                            packed(4, listOf(command(1, 1), zigZag(1), zigZag(1)))
                        }
                    )
                    uint(5, 4096)
                    uint(15, 2)
                }
            )
        }
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun message(block: Encoder.() -> Unit): ByteArray {
        return Encoder().apply(block).toByteArray()
    }

    private class Encoder {
        private val output = ByteArrayOutputStream()

        fun uint(field: Int, value: Long) {
            tag(field, 0)
            varint(value)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            varint(value.size.toLong())
            output.write(value)
        }

        fun packed(field: Int, values: List<Int>) {
            if (values.isEmpty()) return
            val packed = Encoder()
            values.forEach { packed.varint(it.toLong()) }
            bytes(field, packed.toByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            varint(((field shl 3) or wireType).toLong())
        }

        fun varint(value: Long) {
            var remaining = value
            while (remaining >= 0x80L) {
                output.write(((remaining and 0x7fL) or 0x80L).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }
}
