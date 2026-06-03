package com.vectorra.maps.tiles3d

internal enum class Vectorra3DTilesRendererPayloadSource {
    GLB_URI,
    GLTF_URI,
    B3DM_INNER_GLB_CACHE_URI
}

internal enum class Vectorra3DTilesRendererTransformKind {
    MATRIX,
    ECEF
}

internal sealed class Vectorra3DTilesRendererTransform {
    abstract val kind: Vectorra3DTilesRendererTransformKind
    abstract fun nativeMatrix(): DoubleArray
    abstract fun nativeEcefOrigin(): DoubleArray

    data class Matrix(
        val values: List<Double>
    ) : Vectorra3DTilesRendererTransform() {
        init {
            require(values.size == MATRIX_4_SIZE) {
                "3D Tiles renderer matrix transform must contain 16 numbers."
            }
            require(values.all(Double::isFinite)) {
                "3D Tiles renderer matrix transform values must be finite."
            }
        }

        override val kind: Vectorra3DTilesRendererTransformKind =
            Vectorra3DTilesRendererTransformKind.MATRIX

        override fun nativeMatrix(): DoubleArray = values.toDoubleArray()

        override fun nativeEcefOrigin(): DoubleArray = ZERO_ECEF
    }

    data class Ecef(
        val x: Double,
        val y: Double,
        val z: Double,
        val localTransform: List<Double> = IDENTITY_MATRIX
    ) : Vectorra3DTilesRendererTransform() {
        init {
            require(x.isFinite() && y.isFinite() && z.isFinite()) {
                "3D Tiles renderer ECEF origin values must be finite."
            }
            require(localTransform.size == MATRIX_4_SIZE) {
                "3D Tiles renderer ECEF local transform must contain 16 numbers."
            }
            require(localTransform.all(Double::isFinite)) {
                "3D Tiles renderer ECEF local transform values must be finite."
            }
        }

        override val kind: Vectorra3DTilesRendererTransformKind =
            Vectorra3DTilesRendererTransformKind.ECEF

        override fun nativeMatrix(): DoubleArray = localTransform.toDoubleArray()

        override fun nativeEcefOrigin(): DoubleArray = doubleArrayOf(x, y, z)
    }

    companion object {
        private const val MATRIX_4_SIZE = 16
        private val ZERO_ECEF = doubleArrayOf(0.0, 0.0, 0.0)
        val IDENTITY_MATRIX = listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )
    }
}

internal data class Vectorra3DTilesRendererContentInput(
    val layerId: String,
    val tileId: String,
    val contentUri: String,
    val contentKind: VectorraTileset3DContentKind,
    val renderUri: String,
    val payloadSource: Vectorra3DTilesRendererPayloadSource,
    val transform: Vectorra3DTilesRendererTransform,
    val visible: Boolean = true
) {
    init {
        require(layerId.isNotBlank()) { "3D Tiles renderer layerId must not be blank." }
        require(tileId.isNotBlank()) { "3D Tiles renderer tileId must not be blank." }
        require(contentUri.isNotBlank()) { "3D Tiles renderer contentUri must not be blank." }
        require(renderUri.isNotBlank()) { "3D Tiles renderer renderUri must not be blank." }
        require(payloadSource == expectedPayloadSource(contentKind)) {
            "3D Tiles renderer payload source must match content kind."
        }
        require(renderUri.hasSupportedRendererModelExtension()) {
            "3D Tiles renderer renderUri must end with .glb or .gltf."
        }
    }

    val nativeContentId: String
        get() = "$layerId:$tileId"

    fun nativeTransformKind(): String = transform.kind.name

    fun nativeMatrix(): DoubleArray = transform.nativeMatrix()

    fun nativeEcefOrigin(): DoubleArray = transform.nativeEcefOrigin()

    companion object {
        fun expectedPayloadSource(
            kind: VectorraTileset3DContentKind
        ): Vectorra3DTilesRendererPayloadSource {
            return when (kind) {
                VectorraTileset3DContentKind.GLB -> Vectorra3DTilesRendererPayloadSource.GLB_URI
                VectorraTileset3DContentKind.GLTF -> Vectorra3DTilesRendererPayloadSource.GLTF_URI
                VectorraTileset3DContentKind.B3DM ->
                    Vectorra3DTilesRendererPayloadSource.B3DM_INNER_GLB_CACHE_URI
                VectorraTileset3DContentKind.UNKNOWN ->
                    throw IllegalArgumentException("Unknown 3D Tiles content cannot be rendered.")
            }
        }

        private fun String.hasSupportedRendererModelExtension(): Boolean {
            val path = substringBefore('?').substringBefore('#').lowercase()
            return path.endsWith(".glb") || path.endsWith(".gltf")
        }
    }
}
