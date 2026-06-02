package com.vectorra.maps

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Onscreen Vectorra map snapshot entry point.
 *
 * This intentionally reads the currently attached VectorraMapView surface. It is not
 * a no-View/offscreen renderer replacement.
 */
object VectorraSnapshotter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isCapturing = AtomicBoolean(false)

    fun takeSnapshot(
        mapView: VectorraMapView,
        delayMs: Long = 300,
        onSuccess: (Bitmap) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isCapturing.compareAndSet(false, true)) {
            onFailure("已有截图任务执行中，请稍后再试")
            return
        }

        val requestSnapshot = Runnable {
            mapView.snapshot { bitmap, error ->
                isCapturing.set(false)
                if (bitmap != null) {
                    onSuccess(bitmap)
                } else {
                    onFailure(error?.message ?: "Vectorra 地图快照失败")
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (delayMs > 0) {
                mainHandler.postDelayed(requestSnapshot, delayMs)
            } else {
                requestSnapshot.run()
            }
        } else {
            mainHandler.post {
                if (delayMs > 0) {
                    mainHandler.postDelayed(requestSnapshot, delayMs)
                } else {
                    requestSnapshot.run()
                }
            }
        }
    }
}
