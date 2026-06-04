package org.zotero.android.boox

/**
 * A single finished pen stroke as captured by the Onyx overlay, in **overlay-view pixels**.
 *
 * This is deliberately a plain data holder with no Onyx types so it can cross into the rest of the
 * app (and be unit-tested) freely. The px -> PDF-point conversion (build plan §7) is done later by
 * the coordinate bridge inside the WebView, which owns the live pdf.js viewport transform.
 *
 * @param xs x coordinates in overlay pixels, one per sample.
 * @param ys y coordinates in overlay pixels, one per sample.
 * @param pressures raw pressure per sample (0..[maxPressure]); empty if the device reports none.
 * @param maxPressure device max pressure ([com.onyx.android.sdk.api.device.epd.EpdController.getMaxTouchPressure]).
 * @param startTimestamp timestamp of the first sample (ms).
 * @param endTimestamp timestamp of the last sample (ms).
 */
data class BooxInkStroke(
    val xs: FloatArray,
    val ys: FloatArray,
    val pressures: FloatArray,
    val maxPressure: Float,
    val startTimestamp: Long,
    val endTimestamp: Long,
) {
    val sampleCount: Int get() = minOf(xs.size, ys.size)

    val isEmpty: Boolean get() = sampleCount == 0

    /**
     * Median pressure normalised to 0..1, used to derive a single per-annotation ink width
     * (Zotero ink has one width per annotation, not per sample). Falls back to 0.5 when the
     * device reports no usable pressure.
     */
    fun medianPressureRatio(): Float {
        if (pressures.isEmpty() || maxPressure <= 0f) return 0.5f
        val sorted = pressures.sortedArray()
        val median = sorted[sorted.size / 2]
        return (median / maxPressure).coerceIn(0f, 1f)
    }

    /** Flat [x0, y0, x1, y1, ...] view in overlay pixels, the shape the JS bridge expects. */
    fun toFlatPoints(): FloatArray {
        val n = sampleCount
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = xs[i]
            out[i * 2 + 1] = ys[i]
        }
        return out
    }

    // Arrays in a data class need explicit equals/hashCode to behave by value.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BooxInkStroke) return false
        return xs.contentEquals(other.xs) &&
            ys.contentEquals(other.ys) &&
            pressures.contentEquals(other.pressures) &&
            maxPressure == other.maxPressure &&
            startTimestamp == other.startTimestamp &&
            endTimestamp == other.endTimestamp
    }

    override fun hashCode(): Int {
        var result = xs.contentHashCode()
        result = 31 * result + ys.contentHashCode()
        result = 31 * result + pressures.contentHashCode()
        result = 31 * result + maxPressure.hashCode()
        result = 31 * result + startTimestamp.hashCode()
        result = 31 * result + endTimestamp.hashCode()
        return result
    }
}

/** Callbacks for finished pen input, always delivered on the main thread. */
interface BooxPenInputListener {
    /** A finished draw stroke, ready to be converted to a PDF-point ink annotation. */
    fun onStrokeFinished(stroke: BooxInkStroke)

    /** A finished erase stroke; hit-test it against existing ink annotations for deletion. */
    fun onEraseStroke(stroke: BooxInkStroke)
}
