package org.zotero.android.boox

import android.os.Build
import org.zotero.android.BuildConfig

/**
 * Runtime detection for Onyx Boox e-ink devices.
 *
 * The Onyx pen SDK ([com.onyx.android.sdk.pen.TouchHelper] and friends) only works on Boox
 * hardware, so every entry point into the Boox pen layer is gated behind [isBooxDevice]. On a
 * normal phone the pen layer is simply never instantiated and the app behaves exactly like the
 * upstream build.
 *
 * Detection is intentionally dependency-free (just [Build]) rather than relying on the Onyx
 * device SDK, so the gate keeps working even if the SDK fails to initialise on an odd ROM.
 */
object BooxDevice {

    private val isOnyxHardware: Boolean by lazy {
        listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
            .any { it?.contains("onyx", ignoreCase = true) == true || it?.contains("boox", ignoreCase = true) == true }
    }

    /** True when the pen overlay should be available: real Boox hardware + the build flag. */
    val isBooxDevice: Boolean
        get() = BuildConfig.BOOX_PEN_OVERLAY_ENABLED && isOnyxHardware
}
