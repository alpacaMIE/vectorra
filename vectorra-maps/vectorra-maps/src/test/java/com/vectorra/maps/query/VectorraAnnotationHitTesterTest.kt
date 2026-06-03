package com.vectorra.maps.query

import com.vectorra.maps.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraAnnotationHitTesterTest {

    private fun newTester(): VectorraAnnotationHitTester {
        return VectorraAnnotationHitTester().apply {
            setViewport(1000, 1000)
            setCamera(
                CameraState(
                    longitude = 104.0,
                    latitude = 30.0,
                    zoom = 10.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
        }
    }

    @Test
    fun pointHitRespectsRadius() {
        val tester = newTester()
        val center = VectorraCoordinate(104.0, 30.0)
        tester.add(
            VectorraAnnotationFeature(
                id = "point-1",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center),
                properties = mapOf("id" to "p1"),
                radiusPixels = 12.0
            )
        )

        val screen = tester.pixelForCoordinate(center)
        assertEquals("point-1", tester.query(screen, VectorraQueryOptions()).single().id)
        assertTrue(tester.query(VectorraScreenPoint(screen.x + 20.0, screen.y), VectorraQueryOptions()).isEmpty())
    }

    @Test
    fun lineStringHitUsesSegmentDistance() {
        val tester = newTester()
        tester.add(
            VectorraAnnotationFeature(
                id = "line-1",
                layerId = "draw-polyline",
                geometry = VectorraAnnotationGeometry.LineString(
                    listOf(
                        VectorraCoordinate(103.99, 30.0),
                        VectorraCoordinate(104.01, 30.0)
                    )
                ),
                radiusPixels = 10.0
            )
        )

        assertEquals(
            "line-1",
            tester.query(tester.pixelForCoordinate(VectorraCoordinate(104.0, 30.0)), VectorraQueryOptions()).single().id
        )
    }

    @Test
    fun polygonHitCoversInsideBoundaryAndOutside() {
        val tester = newTester()
        tester.add(
            VectorraAnnotationFeature(
                id = "polygon-1",
                layerId = "draw-polygon",
                geometry = VectorraAnnotationGeometry.Polygon(
                    listOf(
                        listOf(
                            VectorraCoordinate(103.99, 29.99),
                            VectorraCoordinate(104.01, 29.99),
                            VectorraCoordinate(104.01, 30.01),
                            VectorraCoordinate(103.99, 30.01),
                            VectorraCoordinate(103.99, 29.99)
                        )
                    )
                ),
                radiusPixels = 8.0
            )
        )

        val inside = tester.pixelForCoordinate(VectorraCoordinate(104.0, 30.0))
        val outside = tester.pixelForCoordinate(VectorraCoordinate(104.05, 30.05))
        assertEquals("polygon-1", tester.query(inside, VectorraQueryOptions()).single().id)
        assertTrue(tester.query(outside, VectorraQueryOptions()).isEmpty())
    }

    @Test
    fun layerFilterAndPropertiesAreReturned() {
        val tester = newTester()
        tester.add(
            VectorraAnnotationFeature(
                id = "point-1",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(VectorraCoordinate(104.0, 30.0)),
                properties = mapOf("payload" to "{\"id\":\"p1\"}"),
                radiusPixels = 12.0
            )
        )

        val screen = tester.pixelForCoordinate(VectorraCoordinate(104.0, 30.0))
        assertTrue(tester.query(screen, VectorraQueryOptions(layerIds = setOf("draw-polyline"))).isEmpty())
        val result = tester.query(screen, VectorraQueryOptions(layerIds = setOf("draw-point"))).single()
        assertEquals("{\"id\":\"p1\"}", result.properties["payload"])
    }

    @Test
    fun externalCandidatesUseSameHitLogicAndReturnSourceId() {
        val tester = newTester()
        val center = VectorraCoordinate(104.0, 30.0)
        val screen = tester.pixelForCoordinate(center)
        val results = tester.queryFeatures(
            candidates = listOf(
                VectorraAnnotationFeature(
                    id = "mvt-road-1",
                    sourceId = "openmaptiles",
                    layerId = "roads-line",
                    geometry = VectorraAnnotationGeometry.LineString(
                        listOf(
                            VectorraCoordinate(103.99, 30.0),
                            VectorraCoordinate(104.01, 30.0)
                        )
                    ),
                    properties = mapOf("source-layer" to "transportation"),
                    radiusPixels = 10.0
                )
            ),
            screenPoint = screen,
            options = VectorraQueryOptions(layerIds = setOf("roads-line"))
        )

        val result = results.single()
        assertEquals("mvt-road-1", result.id)
        assertEquals("openmaptiles", result.sourceId)
        assertEquals("transportation", result.properties["source-layer"])
        assertEquals(
            "mvt-road-1",
            tester.queryFeatures(
                candidates = listOf(
                    VectorraAnnotationFeature(
                        id = "mvt-road-1",
                        layerId = "roads-line",
                        geometry = VectorraAnnotationGeometry.Point(center),
                        properties = mapOf("source-layer" to "transportation")
                    )
                ),
                screenPoint = screen,
                options = VectorraQueryOptions(sourceLayerIds = setOf("transportation"))
            ).single().id
        )
        assertTrue(
            tester.queryFeatures(
                candidates = listOf(
                    VectorraAnnotationFeature(
                        id = "mvt-road-1",
                        layerId = "roads-line",
                        geometry = VectorraAnnotationGeometry.Point(center)
                    )
                ),
                screenPoint = screen,
                options = VectorraQueryOptions(layerIds = setOf("water-fill"))
            ).isEmpty()
        )
        assertTrue(
            tester.queryFeatures(
                candidates = listOf(
                    VectorraAnnotationFeature(
                        id = "mvt-road-1",
                        layerId = "roads-line",
                        geometry = VectorraAnnotationGeometry.Point(center),
                        properties = mapOf("source-layer" to "transportation")
                    )
                ),
                screenPoint = screen,
                options = VectorraQueryOptions(sourceLayerIds = setOf("water"))
            ).isEmpty()
        )
    }

    @Test
    fun zIndexSortsBeforeDistance() {
        val tester = newTester()
        val center = VectorraCoordinate(104.0, 30.0)
        tester.add(
            VectorraAnnotationFeature(
                id = "low",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center),
                radiusPixels = 20.0,
                zIndex = 1
            )
        )
        tester.add(
            VectorraAnnotationFeature(
                id = "high",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(VectorraCoordinate(104.00001, 30.0)),
                radiusPixels = 20.0,
                zIndex = 2
            )
        )

        val results = tester.query(tester.pixelForCoordinate(center), VectorraQueryOptions())
        assertEquals(listOf("high", "low"), results.map { it.id })
    }

    @Test
    fun visibilityOpacityAndZoomFilterAnnotationHits() {
        val tester = newTester()
        val center = VectorraCoordinate(104.0, 30.0)
        val screen = tester.pixelForCoordinate(center)
        tester.add(
            VectorraAnnotationFeature(
                id = "hidden",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center),
                visible = false
            )
        )
        tester.add(
            VectorraAnnotationFeature(
                id = "transparent",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center),
                opacity = 0.0
            )
        )
        tester.add(
            VectorraAnnotationFeature(
                id = "out-of-zoom",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center),
                minZoom = 12.0
            )
        )
        tester.add(
            VectorraAnnotationFeature(
                id = "visible",
                layerId = "draw-point",
                geometry = VectorraAnnotationGeometry.Point(center)
            )
        )

        assertEquals(listOf("visible"), tester.query(screen, VectorraQueryOptions()).map { it.id })
    }
}
