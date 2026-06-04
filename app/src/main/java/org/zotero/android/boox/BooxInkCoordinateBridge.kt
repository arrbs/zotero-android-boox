package org.zotero.android.boox

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Builds the JS that converts a captured [BooxInkStroke] (overlay-view pixels) into a Zotero ink
 * annotation in PDF points, using the live pdf.js viewport owned by the reader WebView.
 *
 * This is the make-or-break coordinate step from build plan §7. We deliberately do **not** hand-roll
 * the screen<->PDF transform on the native side: pdf.js tracks scale + scroll per page via its
 * viewport, so we hand the raw overlay pixels into the WebView and let it do the conversion with
 * [viewport.convertToPdfPoint], which also handles the Y flip.
 *
 * The JS function [JS_INK_TO_PDF_FN] is expected to be injected into the reader page once at load
 * (see [INK_TO_PDF_DEFINITION]); [buildConvertCall] then produces a single `evaluateJavascript`
 * call per stroke. The returned JSON has the shape:
 *
 *   { "pageIndex": N, "width": W, "paths": [[x0,y0,x1,y1,...]] }   // PDF points, bottom-left origin
 *
 * NOTE (device validation gate): the exact DOM hooks below — how to reach the page element and its
 * pdf.js viewport inside zotero/reader's `window._view` — must be confirmed against the bundled
 * reader build on the Boox. The conversion math (rect/scroll subtract -> convertToPdfPoint) is the
 * stable part; the selectors marked TODO are the variable part.
 */
class BooxInkCoordinateBridge(private val gson: Gson = Gson()) {

    /** Builds `evaluateJavascript` source that converts [stroke] and returns the ink-annotation JSON. */
    fun buildConvertCall(stroke: BooxInkStroke, widthFactor: Float = DEFAULT_WIDTH_FACTOR): String {
        val payload = JsonObject().apply {
            val pts = JsonArray()
            stroke.toFlatPoints().forEach { pts.add(it) }
            add("points", pts)
            addProperty("pressureRatio", stroke.medianPressureRatio())
            addProperty("widthFactor", widthFactor)
        }
        val encoded = gson.toJson(payload.toString()) // JSON-string-encode so it is safe inside '...'
        return "javascript:$JS_INK_TO_PDF_FN($encoded);"
    }

    companion object {
        const val JS_INK_TO_PDF_FN = "window.__zoteroBooxInkToPdf"
        private const val DEFAULT_WIDTH_FACTOR = 3.0f

        /**
         * One-time definition injected after the reader page loads. Mirrors build plan §7:
         * overlay px -> subtract page element rect + scroll -> page-canvas px ->
         * viewport.convertToPdfPoint -> PDF point; pageIndex = page the stroke started on.
         */
        val INK_TO_PDF_DEFINITION: String = """
            javascript:(function() {
              window.__zoteroBooxInkToPdf = function(rawJson) {
                try {
                  var data = JSON.parse(rawJson);
                  var pts = data.points; // [x0,y0,x1,y1,...] in overlay (== screen) px
                  if (!pts || pts.length < 2) return null;

                  // TODO(device): confirm how zotero/reader exposes the pdf.js page layer.
                  // Typical pdf.js: page elements carry class "page" with data-page-number,
                  // and a PDFPageView with a .viewport. zotero/reader wraps this in window._view.
                  function pageAt(clientX, clientY) {
                    var els = document.elementsFromPoint(clientX, clientY);
                    for (var i = 0; i < els.length; i++) {
                      var el = els[i].closest ? els[i].closest('.page') : null;
                      if (el) return el;
                    }
                    return document.querySelector('.page');
                  }

                  var firstEl = pageAt(pts[0], pts[1]);
                  if (!firstEl) return null;
                  var pageNumber = parseInt(firstEl.getAttribute('data-page-number') || '1', 10);
                  var pageIndex = pageNumber - 1;

                  var rect = firstEl.getBoundingClientRect();
                  var viewport = (window._view && window._view.getViewportForPage)
                    ? window._view.getViewportForPage(pageIndex)
                    : (firstEl.__pdfViewport || null);

                  var path = [];
                  for (var j = 0; j + 1 < pts.length; j += 2) {
                    var canvasX = pts[j] - rect.left;
                    var canvasY = pts[j + 1] - rect.top;
                    if (viewport && viewport.convertToPdfPoint) {
                      var p = viewport.convertToPdfPoint(canvasX, canvasY);
                      path.push(p[0], p[1]);
                    } else {
                      // Fallback: no viewport found — return canvas px so native can log the gap.
                      path.push(canvasX, canvasY);
                    }
                  }

                  var width = (data.pressureRatio || 0.5) * (data.widthFactor || 3.0);
                  return JSON.stringify({ pageIndex: pageIndex, width: width, paths: [path] });
                } catch (e) {
                  return JSON.stringify({ error: String(e) });
                }
              };
            })();
        """.trimIndent()
    }
}
