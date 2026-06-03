package com.vectorra.maps.tiles3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class Vectorra3DTilesPoint3D(
    val x: Double,
    val y: Double,
    val z: Double
)

internal data class Vectorra3DTilesBoundingSphere(
    val center: Vectorra3DTilesPoint3D,
    val radius: Double
) {
    init {
        require(center.x.isFinite() && center.y.isFinite() && center.z.isFinite()) {
            "3D Tiles bounding sphere center must be finite."
        }
        require(radius.isFinite() && radius >= 0.0) {
            "3D Tiles bounding sphere radius must be finite and greater than or equal to 0."
        }
    }
}

internal object Vectorra3DTilesSpatial {
    val IDENTITY_MATRIX = listOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    )

    fun composeTransform(
        parentTransform: List<Double>,
        tileTransform: List<Double>?,
        rtcCenter: Vectorra3DTilesPoint3D? = null
    ): List<Double> {
        requireMatrix(parentTransform, "parentTransform")
        val tile = tileTransform ?: IDENTITY_MATRIX
        requireMatrix(tile, "tileTransform")
        val composed = multiply(parentTransform, tile)
        return if (rtcCenter == null) {
            composed
        } else {
            multiply(composed, translation(rtcCenter.x, rtcCenter.y, rtcCenter.z))
        }
    }

    fun multiply(left: List<Double>, right: List<Double>): List<Double> {
        requireMatrix(left, "left")
        requireMatrix(right, "right")
        return List(MATRIX_SIZE) { index ->
            val row = index % 4
            val column = index / 4
            (0 until 4).sumOf { k -> left[k * 4 + row] * right[column * 4 + k] }
        }
    }

    fun translation(x: Double, y: Double, z: Double): List<Double> {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "3D Tiles translation values must be finite."
        }
        return listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            x, y, z, 1.0
        )
    }

    fun boundingSphere(
        volume: VectorraTileset3DBoundingVolume,
        transform: List<Double> = IDENTITY_MATRIX
    ): Vectorra3DTilesBoundingSphere {
        val local = when (volume) {
            is VectorraTileset3DBoundingVolume.Sphere -> Vectorra3DTilesBoundingSphere(
                center = Vectorra3DTilesPoint3D(volume.centerX, volume.centerY, volume.centerZ),
                radius = volume.radius
            )
            is VectorraTileset3DBoundingVolume.Box -> boxBoundingSphere(volume.values)
            is VectorraTileset3DBoundingVolume.Region -> regionBoundingSphere(volume)
        }
        return transform(local, transform)
    }

    fun transformPoint(point: Vectorra3DTilesPoint3D, matrix: List<Double>): Vectorra3DTilesPoint3D {
        requireMatrix(matrix, "matrix")
        return Vectorra3DTilesPoint3D(
            x = matrix[0] * point.x + matrix[4] * point.y + matrix[8] * point.z + matrix[12],
            y = matrix[1] * point.x + matrix[5] * point.y + matrix[9] * point.z + matrix[13],
            z = matrix[2] * point.x + matrix[6] * point.y + matrix[10] * point.z + matrix[14]
        )
    }

    private fun transform(
        sphere: Vectorra3DTilesBoundingSphere,
        matrix: List<Double>
    ): Vectorra3DTilesBoundingSphere {
        requireMatrix(matrix, "matrix")
        val scale = maxOf(columnLength(matrix, 0), columnLength(matrix, 1), columnLength(matrix, 2))
        return Vectorra3DTilesBoundingSphere(
            center = transformPoint(sphere.center, matrix),
            radius = sphere.radius * scale
        )
    }

    private fun boxBoundingSphere(values: List<Double>): Vectorra3DTilesBoundingSphere {
        val xHalfAxis = length(values[3], values[4], values[5])
        val yHalfAxis = length(values[6], values[7], values[8])
        val zHalfAxis = length(values[9], values[10], values[11])
        return Vectorra3DTilesBoundingSphere(
            center = Vectorra3DTilesPoint3D(values[0], values[1], values[2]),
            radius = sqrt(xHalfAxis * xHalfAxis + yHalfAxis * yHalfAxis + zHalfAxis * zHalfAxis)
        )
    }

    private fun regionBoundingSphere(region: VectorraTileset3DBoundingVolume.Region): Vectorra3DTilesBoundingSphere {
        val corners = listOf(
            wgs84RadiansToEcef(region.west, region.south, region.minimumHeight),
            wgs84RadiansToEcef(region.west, region.south, region.maximumHeight),
            wgs84RadiansToEcef(region.west, region.north, region.minimumHeight),
            wgs84RadiansToEcef(region.west, region.north, region.maximumHeight),
            wgs84RadiansToEcef(region.east, region.south, region.minimumHeight),
            wgs84RadiansToEcef(region.east, region.south, region.maximumHeight),
            wgs84RadiansToEcef(region.east, region.north, region.minimumHeight),
            wgs84RadiansToEcef(region.east, region.north, region.maximumHeight)
        )
        val minX = corners.minOf { it.x }
        val minY = corners.minOf { it.y }
        val minZ = corners.minOf { it.z }
        val maxX = corners.maxOf { it.x }
        val maxY = corners.maxOf { it.y }
        val maxZ = corners.maxOf { it.z }
        val center = Vectorra3DTilesPoint3D(
            x = (minX + maxX) / 2.0,
            y = (minY + maxY) / 2.0,
            z = (minZ + maxZ) / 2.0
        )
        val radius = corners.maxOf { corner ->
            length(corner.x - center.x, corner.y - center.y, corner.z - center.z)
        }
        return Vectorra3DTilesBoundingSphere(center, radius)
    }

    private fun wgs84RadiansToEcef(
        longitude: Double,
        latitude: Double,
        height: Double
    ): Vectorra3DTilesPoint3D {
        val clampedLatitude = latitude.coerceIn(-HALF_PI, HALF_PI)
        val radius = WGS84_RADIUS_METERS + height
        val cosLatitude = cos(clampedLatitude)
        return Vectorra3DTilesPoint3D(
            x = radius * cosLatitude * cos(longitude),
            y = radius * cosLatitude * sin(longitude),
            z = radius * sin(clampedLatitude)
        )
    }

    private fun columnLength(matrix: List<Double>, column: Int): Double {
        val start = column * 4
        return length(matrix[start], matrix[start + 1], matrix[start + 2])
    }

    private fun length(x: Double, y: Double, z: Double): Double {
        return sqrt(x * x + y * y + z * z)
    }

    private fun requireMatrix(values: List<Double>, name: String) {
        require(values.size == MATRIX_SIZE) { "3D Tiles $name matrix must contain 16 numbers." }
        require(values.all(Double::isFinite)) { "3D Tiles $name matrix values must be finite." }
    }

    private const val MATRIX_SIZE = 16
    private const val WGS84_RADIUS_METERS = 6_378_137.0
    private const val HALF_PI = Math.PI / 2.0
}
