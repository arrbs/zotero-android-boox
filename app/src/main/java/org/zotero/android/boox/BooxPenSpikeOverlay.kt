package org.zotero.android.boox

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * Phase 5 capture spike: drops the transparent Onyx pen overlay on top of the reader and exposes a
 * Highlight / Draw / Erase toggle. On non-Boox devices it renders nothing.
 *
 * This validates the Boox pen pipeline end-to-end at the capture level (raw ink renders on the
 * e-ink layer; finished strokes arrive as [BooxInkStroke]) without yet depending on the PDF-reader
 * coordinate bridge. Default mode is HIGHLIGHT, so the stylus falls through to the WebView and
 * reading is unaffected until the user explicitly switches to Draw.
 *
 * Wiring real ink persistence (convert via [BooxInkCoordinateBridge] -> store ink RItem) is the next
 * step and belongs in the reader ViewModel, which owns the WebView handle and the annotation store.
 */
@Composable
fun BooxPenSpikeOverlay(modifier: Modifier = Modifier) {
    if (!BooxDevice.isBooxDevice) return

    var controller by remember { mutableStateOf<BooxPenController?>(null) }
    var mode by remember { mutableStateOf(PenMode.HIGHLIGHT) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val overlay = BooxPenOverlayView(context)
                overlay.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                controller = BooxPenController(
                    overlay = overlay,
                    onDrawStroke = { stroke ->
                        Timber.i(
                            "BooxPenSpike: draw stroke samples=${stroke.sampleCount} " +
                                "pressureRatio=${stroke.medianPressureRatio()}",
                        )
                    },
                    onEraseStroke = { stroke ->
                        Timber.i("BooxPenSpike: erase stroke samples=${stroke.sampleCount}")
                    },
                )
                overlay
            },
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.White.copy(alpha = 0.85f)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PenMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = {
                        mode = entry
                        controller?.setMode(entry)
                    },
                    label = { Text(entry.name) },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller?.pause() }
    }
}
