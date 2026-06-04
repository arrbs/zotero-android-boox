package org.zotero.android.boox

import timber.log.Timber

/**
 * Thin orchestration layer over [BooxPenOverlayView]. Owns the current [PenMode] and forwards
 * finished strokes to the host (typically the reader ViewModel, which runs them through the
 * coordinate bridge and persists ink annotations).
 *
 * Holds no Onyx types; safe to construct from anywhere. The overlay it wraps must only be created
 * when [BooxDevice.isBooxDevice] is true.
 */
class BooxPenController(
    private val overlay: BooxPenOverlayView,
    private val onDrawStroke: (BooxInkStroke) -> Unit,
    private val onEraseStroke: (BooxInkStroke) -> Unit,
) {
    var mode: PenMode = PenMode.HIGHLIGHT
        private set

    init {
        overlay.inputListener = object : BooxPenInputListener {
            override fun onStrokeFinished(stroke: BooxInkStroke) {
                if (stroke.isEmpty) return
                Timber.d("BooxPenController: draw stroke, ${stroke.sampleCount} pts")
                onDrawStroke(stroke)
            }

            override fun onEraseStroke(stroke: BooxInkStroke) {
                if (stroke.isEmpty) return
                Timber.d("BooxPenController: erase stroke, ${stroke.sampleCount} pts")
                onEraseStroke(stroke)
            }
        }
        overlay.setMode(mode)
    }

    fun setMode(newMode: PenMode) {
        if (newMode == mode) return
        mode = newMode
        overlay.setMode(newMode)
    }

    fun setStrokeStyle(style: Int) = overlay.setStrokeStyle(style)

    fun setStrokeWidthPx(width: Float) = overlay.setStrokeWidthPx(width)

    fun resume() = overlay.resume()

    fun pause() = overlay.pause()
}
