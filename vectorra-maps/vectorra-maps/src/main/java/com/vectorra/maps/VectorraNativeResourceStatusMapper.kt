package com.vectorra.maps

internal object VectorraNativeResourceStatusMapper {
    fun map(
        previous: VectorraResourceStatus?,
        rawKind: String,
        rawState: String,
        rawErrorType: String?,
        rawErrorMessage: String?
    ): VectorraNativeResourceStatusUpdate? {
        val existing = previous ?: return null
        val nativeKind = rawKind.toResourceKindOrNull() ?: return null
        val nativeState = rawState.toResourceLoadStateOrNull() ?: return null
        if (nativeState == VectorraResourceLoadState.REMOVED || existing.state == VectorraResourceLoadState.REMOVED) {
            return null
        }
        if (!nativeKind.matchesNativeCallbackFor(existing.kind)) {
            return null
        }
        val error = if (nativeState == VectorraResourceLoadState.FAILED) {
            val message = rawErrorMessage?.takeIf { it.isNotBlank() } ?: return null
            VectorraResourceLoadError(
                type = rawErrorType.toResourceErrorTypeOrNull() ?: VectorraResourceErrorType.NATIVE_RENDERER,
                message = message
            )
        } else {
            null
        }
        return VectorraNativeResourceStatusUpdate(
            kind = existing.kind,
            sourceId = existing.sourceId,
            layerId = existing.layerId,
            state = nativeState,
            error = error
        )
    }

    private fun String.toResourceKindOrNull(): VectorraResourceKind? {
        return VectorraResourceKind.entries.firstOrNull { it.name == uppercase() }
    }

    private fun String.toResourceLoadStateOrNull(): VectorraResourceLoadState? {
        return VectorraResourceLoadState.entries.firstOrNull { it.name == uppercase() }
    }

    private fun String?.toResourceErrorTypeOrNull(): VectorraResourceErrorType? {
        return this?.let { raw ->
            VectorraResourceErrorType.entries.firstOrNull { it.name == raw.uppercase() }
        }
    }

    private fun VectorraResourceKind.matchesNativeCallbackFor(engineKind: VectorraResourceKind): Boolean {
        return this == engineKind || (this == VectorraResourceKind.RASTER && engineKind == VectorraResourceKind.MBTILES)
    }
}

internal data class VectorraNativeResourceStatusUpdate(
    val kind: VectorraResourceKind,
    val sourceId: String,
    val layerId: String,
    val state: VectorraResourceLoadState,
    val error: VectorraResourceLoadError?
)
