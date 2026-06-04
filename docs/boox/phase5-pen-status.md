# Boox pen integration — status

What is wired in, and exactly what remains to be validated on a Boox device.

## Done (in this branch)

**Onyx SDK in the build**
- `build.gradle.kts`: added the Boox Maven repo (`http://repo.boox.com/...`, `allowInsecureProtocol`).
- `Libs.kt`: `Libs.Onyx.pen` (`onyxsdk-pen:1.4.11`), `Libs.Onyx.device` (`onyxsdk-device:1.1.11`).
- `app/build.gradle.kts`: both deps added; `BuildConfig.BOOX_PEN_OVERLAY_ENABLED` flag.

**Native pen module** — `app/src/main/java/org/zotero/android/boox/`
- `BooxDevice` — dependency-free `isBooxDevice` gate (Build manufacturer/brand/model).
- `PenMode` — HIGHLIGHT / DRAW / ERASE single-stylus toggle.
- `BooxInkStroke` + `BooxPenInputListener` — Onyx-free stroke model, main-thread delivery.
- `BooxPenOverlayView` — transparent `SurfaceView` + Onyx `TouchHelper`/`RawInputCallback`.
  **All Onyx imports live only here**, so SDK drift is contained to one file.
- `BooxPenController` — mode/lifecycle orchestration, no Onyx types.
- `BooxInkCoordinateBridge` — builds the overlay-px → PDF-point conversion JS (plan §7).
- `BooxPenSpikeOverlay` — gated Compose overlay + mode toggle, mounted in `HtmlEpubReaderBox`.
  No-op off-Boox; defaults to HIGHLIGHT pass-through so reading is unaffected.

## Cannot be verified in this environment
No Android SDK, and the network blocks `repo.boox.com` / `jitpack.io` / Google Maven (HTTP 403),
so the project does not compile here. The code is modelled on the documented Onyx contract
(plan §8) and `ScribbleTouchHelperDemoActivity`; treat the first on-device build as the compile
check. Risk is concentrated in `BooxPenOverlayView` (Onyx API surface).

## Remaining device-gated steps (in order)

1. **Compile on a machine with the Android SDK + network.** Fix any Onyx signature drift
   (likely candidates: exact `RawInputCallback` abstract method set for 1.4.11; `setLimitRect`
   overloads; `EpdController.getMaxTouchPressure` return type).
2. **Capture spike on the Boox.** Open any document, switch the toggle to DRAW, confirm raw ink
   renders on the e-ink layer and `BooxPenSpike` logs strokes (logcat). This proves §11
   "Onyx raw-ink over the WebView overlay feels native" + stylus-vs-touch routing.
3. **PDF in the reader.** Add a `type:"pdf"` branch so PDFs route through the existing WebView
   reader (`HtmlEpubReaderViewModel` §970-994, `DocumentData`/`Page`). See `phase0-seam.md`.
4. **Coordinate bridge validation gate (plan §7).** Inject `BooxInkCoordinateBridge.INK_TO_PDF_DEFINITION`
   after the reader page loads; per stroke call `buildConvertCall` and confirm one stroke lands
   exactly under the pen and shows correctly in Zotero desktop. Confirm the TODO selectors against
   the bundled reader (`window._view` page/viewport access).
5. **Persist ink.** Feed the converted `{pageIndex,width,paths}` into a `CreateReaderAnnotationsDbRequest`
   subclass whose `addAdditionalProperties` mirrors `CreatePDFAnnotationsDbRequest`'s `RPath`
   writes → syncs as a normal ink RItem.
6. **Eraser + polish** (plan §6): `onRawErasing*` → hit-test ink → delete request; color/width,
   fountain/pencil, e-ink refresh tuning via `onyxsdk-device`.
