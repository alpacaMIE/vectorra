package com.vectorra.maps

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.vectorra.maps.gestures.VectorraGestureSettings
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class VectorraMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val engine: VectorraMapEngine = VectorraMapEngine(context.applicationContext.cacheDir)
    val map: VectorraMap
        get() = engine
    var lifecycleCallback: VectorraMapLifecycleCallback? = null
    val mapLoadState: VectorraMapLoadState
        get() = engine.loadState
    val mapLoadError: VectorraMapLoadError?
        get() = engine.lastLoadError
    var onUserGestureStarted: (() -> Unit)? = null
    var gestureSettings: VectorraGestureSettings = VectorraGestureSettings()
    var surfaceLifecycleState: VectorraSurfaceLifecycleState = VectorraSurfaceLifecycleState.NOT_CREATED
        private set
    private var surfaceAvailable = false
    private val lifecycleCallbacks = CopyOnWriteArrayList<VectorraMapLifecycleCallback>()
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var lastTouchSpan = 0f
    private var lastTouchAngle = 0.0
    private var activePointerCount = 0
    private var gestureActive = false
    private var dragging = false
    private var released = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()
    private val flingScroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var twoFingerTapCandidate = false
    private var twoFingerTapStartTime = 0L
    private var twoFingerTapStartX = 0f
    private var twoFingerTapStartY = 0f
    private var twoFingerTapStartSpan = 0f
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var twoFingerStartSpan = 0f
    private var twoFingerStartAngle = 0.0
    private var twoFingerPitching = false
    private var suppressSingleTap = false
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (flingScroller.computeScrollOffset()) {
                val currentX = flingScroller.currX
                val currentY = flingScroller.currY
                val deltaX = currentX - lastFlingX
                val deltaY = currentY - lastFlingY
                lastFlingX = currentX
                lastFlingY = currentY
                if (deltaX != 0 || deltaY != 0) {
                    engine.panByPixels(deltaX.toFloat(), deltaY.toFloat(), width, height)
                }
                postOnAnimation(this)
            }
        }
    }

    init {
        engine.setResourcePath(ensureVectorraAssets(context).absolutePath)
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        surfaceLifecycleState = VectorraSurfaceLifecycleState.CREATED
        Log.i(TAG, "surfaceCreated view=${width}x${height}")
        dispatchSurfaceLifecycleChanged(surfaceLifecycleState, width, height)
        attachSurfaceIfSized(holder, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged surface=${width}x${height} format=$format")
        surfaceAvailable = true
        surfaceLifecycleState = VectorraSurfaceLifecycleState.CHANGED
        dispatchSurfaceLifecycleChanged(surfaceLifecycleState, width, height)
        attachSurfaceIfSized(holder, width, height)
    }

    private fun attachSurfaceIfSized(holder: SurfaceHolder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val error = engine.attachSurface(holder.surface, width, height)
        if (error == null) {
            dispatchMapReady()
        } else {
            dispatchMapLoadError(error)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        surfaceLifecycleState = VectorraSurfaceLifecycleState.DESTROYED
        Log.i(TAG, "surfaceDestroyed")
        engine.detachSurface()
        dispatchSurfaceLifecycleChanged(surfaceLifecycleState, 0, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = if (pointerCount > 1) event.getX(1) else 0f
        val y1 = if (pointerCount > 1) event.getY(1) else 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                parent?.requestDisallowInterceptTouchEvent(true)
                engine.onUserGesture()
                onUserGestureStarted?.invoke()
                gestureActive = true
                activePointerCount = 1
                dragging = false
                suppressSingleTap = false
                twoFingerTapCandidate = false
                downTouchX = x0
                downTouchY = y0
                lastTouchX = x0
                lastTouchY = y0
                lastTouchSpan = 0f
                lastTouchAngle = 0.0
                recycleVelocityTracker()
                trackVelocity(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureActive = true
                activePointerCount = pointerCount
                dragging = false
                suppressSingleTap = true
                recycleVelocityTracker()
                if (pointerCount >= 2) {
                    val midpointX = midpoint(x0, x1)
                    val midpointY = midpoint(y0, y1)
                    val span = touchSpan(x0, y0, x1, y1)
                    lastTouchX = midpointX
                    lastTouchY = midpointY
                    lastTouchSpan = span
                    lastTouchAngle = touchAngle(x0, y0, x1, y1)
                    twoFingerTapCandidate = pointerCount == 2
                    twoFingerTapStartTime = event.eventTime
                    twoFingerTapStartX = midpointX
                    twoFingerTapStartY = midpointY
                    twoFingerTapStartSpan = span
                    twoFingerStartX = midpointX
                    twoFingerStartY = midpointY
                    twoFingerStartSpan = span
                    twoFingerStartAngle = lastTouchAngle
                    twoFingerPitching = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) {
                    return true
                } else if (pointerCount >= 2) {
                    val midpointX = midpoint(x0, x1)
                    val midpointY = midpoint(y0, y1)
                    val span = touchSpan(x0, y0, x1, y1)
                    val angle = touchAngle(x0, y0, x1, y1)
                    updateTwoFingerTapCandidate(event.eventTime, midpointX, midpointY, span)
                    if (activePointerCount >= 2 && lastTouchSpan > MIN_TOUCH_SPAN && span > MIN_TOUCH_SPAN) {
                        val dx = midpointX - lastTouchX
                        val dy = midpointY - lastTouchY
                        val angleDelta = shortestAngleDelta(lastTouchAngle, angle)
                        updateTwoFingerPitchState(midpointX, midpointY, span, angle)
                        val pinchActive = gestureSettings.pinchToZoomEnabled &&
                            !twoFingerPitching &&
                            abs(span - lastTouchSpan) > PINCH_SLOP_PIXELS
                        val rotateActive = gestureSettings.rotateEnabled &&
                            !twoFingerPitching &&
                            abs(angleDelta) > ROTATE_SLOP_DEGREES

                        if (pinchActive) {
                            engine.zoomByScaleAt(span / lastTouchSpan, midpointX, midpointY, width, height)
                        }
                        if (rotateActive) {
                            engine.rotateBy(-angleDelta)
                        }
                        if (twoFingerPitching) {
                            engine.pitchBy((-dy * PITCH_DEGREES_PER_PIXEL).toDouble())
                        } else if (!pinchActive && !rotateActive && gestureSettings.scrollEnabled) {
                            engine.panByPixels(dx, dy, width, height)
                        }
                    }
                    activePointerCount = pointerCount
                    lastTouchX = midpointX
                    lastTouchY = midpointY
                    lastTouchSpan = span
                    lastTouchAngle = angle
                } else if (activePointerCount == 1) {
                    trackVelocity(event)
                    val dx = x0 - lastTouchX
                    val dy = y0 - lastTouchY
                    if (!dragging && touchSpan(x0, y0, downTouchX, downTouchY) >= touchSlop) {
                        dragging = true
                    }
                    if (dragging && gestureSettings.scrollEnabled) {
                        engine.panByPixels(dx, dy, width, height)
                    }
                    lastTouchX = x0
                    lastTouchY = y0
                } else {
                    activePointerCount = 1
                    dragging = false
                    downTouchX = x0
                    downTouchY = y0
                    lastTouchX = x0
                    lastTouchY = y0
                    trackVelocity(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (
                    activePointerCount == 2 &&
                    pointerCount == 2 &&
                    twoFingerTapCandidate &&
                    event.eventTime - twoFingerTapStartTime <= DOUBLE_TAP_TIMEOUT_MS
                ) {
                    if (gestureSettings.doubleTouchToZoomOutEnabled) {
                        engine.zoomByScaleAt(0.5f, twoFingerTapStartX, twoFingerTapStartY, width, height)
                    }
                    twoFingerTapCandidate = false
                }
                activePointerCount = (pointerCount - 1).coerceAtLeast(0)
                if (activePointerCount == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    downTouchX = lastTouchX
                    downTouchY = lastTouchY
                    lastTouchSpan = 0f
                    lastTouchAngle = 0.0
                    dragging = false
                }
                twoFingerPitching = false
                recycleVelocityTracker()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && activePointerCount == 1) {
                    trackVelocity(event)
                    if (dragging && gestureSettings.scrollEnabled && gestureSettings.flingEnabled) {
                        startFlingIfNeeded()
                    }
                }
                if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    activePointerCount == 1 &&
                    !dragging &&
                    !suppressSingleTap
                ) {
                    handleSingleTapUp(event.eventTime, x0, y0)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                gestureActive = false
                activePointerCount = 0
                dragging = false
                twoFingerTapCandidate = false
                twoFingerPitching = false
                suppressSingleTap = false
                lastTouchSpan = 0f
                lastTouchAngle = 0.0
                recycleVelocityTracker()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    fun release() {
        if (released) return
        released = true
        stopFling()
        recycleVelocityTracker()
        holder.removeCallback(this)
        if (surfaceAvailable) {
            engine.detachSurface()
            surfaceAvailable = false
            surfaceLifecycleState = VectorraSurfaceLifecycleState.DESTROYED
            dispatchSurfaceLifecycleChanged(surfaceLifecycleState, 0, 0)
        }
        lifecycleCallbacks.clear()
        engine.close()
    }

    fun snapshot(callback: VectorraSnapshotCallback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { snapshot(callback) }
            return
        }
        if (released) {
            callback.onSnapshotReady(null, VectorraSnapshotError("VectorraMapView 已释放"))
            return
        }
        if (!isAttachedToWindow || !surfaceAvailable || holder.surface == null || !holder.surface.isValid) {
            callback.onSnapshotReady(null, VectorraSnapshotError("VectorraMapView surface 不可用"))
            return
        }
        if (width <= 0 || height <= 0) {
            callback.onSnapshotReady(null, VectorraSnapshotError("VectorraMapView 尺寸无效：${width}x${height}"))
            return
        }

        val target = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (error: Throwable) {
            callback.onSnapshotReady(null, VectorraSnapshotError("创建截图 Bitmap 失败", error))
            return
        }

        PixelCopy.request(this, target, { result ->
            if (result == PixelCopy.SUCCESS) {
                callback.onSnapshotReady(target, null)
            } else {
                target.recycle()
                callback.onSnapshotReady(null, VectorraSnapshotError("PixelCopy 截图失败：$result"))
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun addLifecycleCallback(callback: VectorraMapLifecycleCallback): Closeable {
        lifecycleCallbacks.add(callback)
        return Closeable {
            lifecycleCallbacks.remove(callback)
        }
    }

    private fun handleSingleTapUp(eventTime: Long, x: Float, y: Float) {
        val doubleTap = eventTime - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS &&
            touchSpan(x, y, lastTapX, lastTapY) <= DOUBLE_TAP_SLOP

        if (doubleTap) {
            if (gestureSettings.doubleTapToZoomInEnabled) {
                engine.zoomByScaleAt(2f, x, y, width, height)
            }
            lastTapTime = 0L
        } else {
            lastTapTime = eventTime
            lastTapX = x
            lastTapY = y
            if (!engine.dispatchMapClick(x, y)) {
                performClick()
            }
        }
    }

    private fun trackVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun startFlingIfNeeded() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        val velocityX = tracker.xVelocity
        val velocityY = tracker.yVelocity
        if (abs(velocityX) < minimumFlingVelocity && abs(velocityY) < minimumFlingVelocity) {
            return
        }

        engine.onUserGesture()
        lastFlingX = 0
        lastFlingY = 0
        flingScroller.fling(
            0,
            0,
            velocityX.toInt(),
            velocityY.toInt(),
            Int.MIN_VALUE,
            Int.MAX_VALUE,
            Int.MIN_VALUE,
            Int.MAX_VALUE
        )
        postOnAnimation(flingRunnable)
    }

    private fun stopFling() {
        removeCallbacks(flingRunnable)
        if (!flingScroller.isFinished) {
            flingScroller.forceFinished(true)
        }
    }

    private fun updateTwoFingerTapCandidate(eventTime: Long, x: Float, y: Float, span: Float) {
        if (!twoFingerTapCandidate) {
            return
        }
        if (
            eventTime - twoFingerTapStartTime > DOUBLE_TAP_TIMEOUT_MS ||
            touchSpan(x, y, twoFingerTapStartX, twoFingerTapStartY) > touchSlop ||
            abs(span - twoFingerTapStartSpan) > touchSlop
        ) {
            twoFingerTapCandidate = false
        }
    }

    private fun updateTwoFingerPitchState(x: Float, y: Float, span: Float, angle: Double) {
        if (twoFingerPitching || !gestureSettings.pitchEnabled || twoFingerStartSpan <= MIN_TOUCH_SPAN) {
            return
        }

        val cumulativeDx = x - twoFingerStartX
        val cumulativeDy = y - twoFingerStartY
        val spanDelta = abs(span - twoFingerStartSpan)
        val angleDelta = abs(shortestAngleDelta(twoFingerStartAngle, angle))
        val verticalDrag = abs(cumulativeDy) >= touchSlop && abs(cumulativeDy) > abs(cumulativeDx) * 1.25f
        val stableSpan = spanDelta <= touchSlop * 2f
        val stableAngle = angleDelta <= PITCH_ROTATE_SLOP_DEGREES
        if (verticalDrag && stableSpan && stableAngle) {
            twoFingerPitching = true
        }
    }

    private fun dispatchSurfaceLifecycleChanged(
        state: VectorraSurfaceLifecycleState,
        width: Int,
        height: Int
    ) {
        lifecycleCallback?.onSurfaceLifecycleChanged(this, state, width, height)
        lifecycleCallbacks.forEach { it.onSurfaceLifecycleChanged(this, state, width, height) }
    }

    private fun dispatchMapReady() {
        lifecycleCallback?.onMapReady(this, map)
        lifecycleCallbacks.forEach { it.onMapReady(this, map) }
    }

    private fun dispatchMapLoadError(error: VectorraMapLoadError) {
        Log.e(TAG, "map load failed: ${error.message}", error.cause)
        if (error is VectorraMapLoadError.UnsupportedDevice) {
            Log.e(TAG, "unsupported Vectorra device", VectorraUnsupportedDeviceException(error.message, error.cause))
        }
        lifecycleCallback?.onMapLoadError(this, error)
        lifecycleCallbacks.forEach { it.onMapLoadError(this, error) }
    }

    private companion object {
        const val TAG = "VectorraMapView"
        const val VECTORRA_ASSET_ROOT = "rocky"
        const val VECTORRA_ASSET_MARKER = ".vectorra_assets_ready_v1"
        const val VECTORRA_CJK_FONT = "fonts/NotoSansSC-VF.ttf"
        const val MIN_TOUCH_SPAN = 24f
        const val PINCH_SLOP_PIXELS = 1f
        const val ROTATE_SLOP_DEGREES = 0.5
        const val PITCH_ROTATE_SLOP_DEGREES = 8.0
        const val PITCH_DEGREES_PER_PIXEL = 0.15f
        const val DOUBLE_TAP_TIMEOUT_MS = 300L
        const val DOUBLE_TAP_SLOP = 48f

        fun midpoint(first: Float, second: Float): Float = (first + second) * 0.5f

        fun touchSpan(x0: Float, y0: Float, x1: Float, y1: Float): Float = hypot(x1 - x0, y1 - y0)

        fun touchAngle(x0: Float, y0: Float, x1: Float, y1: Float): Double =
            Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))

        fun shortestAngleDelta(from: Double, to: Double): Double {
            var delta = to - from
            while (delta > 180.0) delta -= 360.0
            while (delta < -180.0) delta += 360.0
            return delta
        }

        fun ensureVectorraAssets(context: Context): File {
            val targetRoot = File(context.filesDir, "rocky_assets")
            val marker = File(targetRoot, VECTORRA_ASSET_MARKER)
            val requiredFont = File(targetRoot, VECTORRA_CJK_FONT)
            if (marker.exists() && requiredFont.exists()) {
                return targetRoot
            }

            copyAssetTree(context, VECTORRA_ASSET_ROOT, targetRoot)
            marker.parentFile?.mkdirs()
            marker.writeText("ready")
            return targetRoot
        }

        fun copyAssetTree(context: Context, assetPath: String, target: File) {
            val children = context.assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return
            }

            target.mkdirs()
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(target, child))
            }
        }
    }
}
