package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtGeoJsonTest {
    @Test
    fun convertsTileLocalPointToLonLatAtZoomZero() {
        val tile = VectorraMvtTile(
            layers = listOf(
                VectorraMvtLayer(
                    name = "places",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = 1,
                            layerName = "places",
                            geometry = VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(2048, 2048))),
                            properties = mapOf("name" to VectorraMvtValue.StringValue("Center"))
                        )
                    )
                )
            )
        )

        val feature = tile.toGeoJsonFeatures(VectorraMvtTileId(0, 0, 0)).single()
        val point = feature.geometry as VectorraAnnotationGeometry.Point
        assertEquals(0.0, point.coordinate.longitude, 1e-9)
        assertEquals(0.0, point.coordinate.latitude, 1e-9)
        assertEquals("Center", feature.properties["name"])
        assertEquals("places", feature.properties["mvt_layer"])
    }

    @Test
    fun filtersLayersDuringConversion() {
        val tile = VectorraMvtTile(
            layers = listOf(
                layer("roads"),
                layer("buildings")
            )
        )

        val features = tile.toGeoJsonFeatures(
            tileId = VectorraMvtTileId(1, 0, 0),
            layerNames = setOf("buildings")
        )

        assertEquals(1, features.size)
        assertEquals("buildings", features.single().properties["mvt_layer"])
    }

    @Test
    fun convertsLineStringGeometry() {
        val decoded = VectorraMvtFeature(
            id = null,
            layerName = "roads",
            geometry = VectorraMvtGeometry.LineString(
                listOf(listOf(VectorraMvtPoint(0, 0), VectorraMvtPoint(4096, 4096)))
            ),
            properties = emptyMap()
        ).toDecodedFeature(VectorraMvtTileId(0, 0, 0), 4096)

        assertTrue(decoded?.geometry is VectorraAnnotationGeometry.LineString)
    }

    private fun layer(name: String): VectorraMvtLayer {
        return VectorraMvtLayer(
            name = name,
            version = 2,
            extent = 4096,
            features = listOf(
                VectorraMvtFeature(
                    id = null,
                    layerName = name,
                    geometry = VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(0, 0))),
                    properties = emptyMap()
                )
            )
        )
    }
}
