package org.zotero.android.boox

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import timber.log.Timber

/**
 * Transparent full-screen [SurfaceView] that sits on top of the reader WebView and lets the Onyx
 * [TouchHelper] render smooth raw ink on Boox e-ink hardware.
 *
 * This is the *only* file that imports Onyx types — everything else in the app talks to it through
 * [BooxInkStroke] / [BooxPenInputListener] / [PenMode], so any Onyx API drift is contained here.
 *
 * Finished strokes are delivered to [inputListener] on the main thread. Coordinates are in
 * overlay-view pixels; the px -> PDF-point conversion happens later in the WebView coordinate
 * bridge (build plan §7).
 */
class BooxPenOverlayView(context: Context) : SurfaceView(context) {

    var inputListener: BooxPenInputListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var touchHelper: TouchHelper? = null
    private var rawDrawingOpen = false

    private var mode: PenMode = PenMode.HIGHLIGHT
    private var strokeWidthPx: Float = DEFAULT_STROKE_WIDTH_PX
    private var strokeStyle: Int = TouchHelper.STROKE_STYLE_FOUNTAIN

    private val maxPressure: Float by lazy {
        runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f)
    }

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint?) {}

        override fun onEndRawDrawing(outOfLimit: Boolean, point: TouchPoint?) {}

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            deliverStroke(list, erasing = false)
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint?) {}

        override fun onEndRawErasing(outOfLimit: Boolean, point: TouchPoint?) {}

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            deliverStroke(list, erasing = true)
        }
    }

    init {
        // Transparent so the WebView shows through; on top so the EPD raw layer renders above it.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openRawDrawingIfNeeded()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                openRawDrawingIfNeeded()
                applyLimitRect()
                applyMode()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeRawDrawing()
            }
        })
    }

    /** Switch the single-stylus mode (highlight passes through; draw/erase intercept). */
    fun setMode(newMode: PenMode) {
        mode = newMode
        applyMode()
    }

    fun setStrokeWidthPx(width: Float) {
        strokeWidthPx = width
        runCatching { touchHelper?.setStrokeWidth(width) }
    }

    /** One of [TouchHelper.STROKE_STYLE_FOUNTAIN], [TouchHelper.STROKE_STYLE_PENCIL], etc. */
    fun setStrokeStyle(style: Int) {
        strokeStyle = style
        runCatching { touchHelper?.setStrokeStyle(style) }
    }

    /** Call from the host's onResume / when the overlay becomes visible. */
    fun resume() {
        openRawDrawingIfNeeded()
        applyMode()
    }

    /** Call from the host's onPause / when the overlay is hidden, and on teardown. */
    fun pause() {
        closeRawDrawing()
    }

    private fun openRawDrawingIfNeeded() {
        if (rawDrawingOpen) return
        runCatching {
            val helper = touchHelper ?: TouchHelper.create(this, rawInputCallback).also { touchHelper = it }
            helper.setStrokeWidth(strokeWidthPx)
            applyLimitRect()
            helper.openRawDrawing()
            helper.setStrokeStyle(strokeStyle)
            rawDrawingOpen = true
        }.onFailure { Timber.e(it, "BooxPenOverlayView: failed to open raw drawing") }
    }

    private fun applyLimitRect() {
        val helper = touchHelper ?: return
        val limit = Rect()
        // Local rect (0,0,w,h); TouchHelper maps the overlay region into the EPD raw layer.
        if (!getLocalVisibleRect(limit) || limit.width() == 0 || limit.height() == 0) {
            limit.set(0, 0, width, height)
        }
        runCatching { helper.setLimitRect(limit, ArrayList<Rect>()) }
    }

    private fun applyMode() {
        val helper = touchHelper ?: return
        runCatching {
            helper.setRawDrawingEnabled(mode.isRawDrawing)
            // In draw/erase, render strokes immediately; in highlight, let touches fall through.
            helper.setRawDrawingRenderEnabled(mode == PenMode.DRAW)
        }.onFailure { Timber.e(it, "BooxPenOverlayView: failed to apply mode $mode") }
    }

    private fun closeRawDrawing() {
        if (!rawDrawingOpen) return
        runCatching { touchHelper?.setRawDrawingEnabled(false) }
        runCatching { touchHelper?.closeRawDrawing() }
        rawDrawingOpen = false
    }

    private fun deliverStroke(list: TouchPointList?, erasing: Boolean) {
        val points = list?.points ?: return
        if (points.isEmpty()) return

        val n = points.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val pressures = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
            pressures[i] = p.pressure
        }
        val stroke = BooxInkStroke(
            xs = xs,
            ys = ys,
            pressures = pressures,
            maxPressure = maxPressure,
            startTimestamp = points.first().timestamp,
            endTimestamp = points.last().timestamp,
        )
        mainHandler.post {
            val listener = inputListener ?: return@post
            if (erasing) listener.onEraseStroke(stroke) else listener.onStrokeFinished(stroke)
        }
    }

    companion object {
        private const val DEFAULT_STROKE_WIDTH_PX = 3.0f
    }
}
