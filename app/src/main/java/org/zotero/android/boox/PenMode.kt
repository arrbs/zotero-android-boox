package org.zotero.android.boox

/**
 * The single-stylus mode toggle described in the build plan (§4).
 *
 * - [HIGHLIGHT]: raw drawing is disabled, the stylus falls through to the WebView so pdf.js text
 *   selection drives highlight/underline.
 * - [DRAW]: the Onyx [com.onyx.android.sdk.pen.TouchHelper] intercepts the stylus and renders
 *   smooth ink; finished strokes become Zotero ink annotations.
 * - [ERASE]: stylus strokes hit-test existing ink annotations for deletion.
 */
enum class PenMode {
    HIGHLIGHT,
    DRAW,
    ERASE,
    ;

    /** Whether the Onyx raw-drawing pipeline should be intercepting the stylus in this mode. */
    val isRawDrawing: Boolean get() = this == DRAW || this == ERASE
}
