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

## Done — PDF routing + annotation persistence (Phase 3)

**PDFs now route through the WebView reader on Boox** (`BooxDevice.isBooxDevice`):
- `AllItemsViewModel` / `ItemDetailsViewModel`: `application/pdf` → `showHtmlEpub` (PSPDFKit
  remains the fallback on non-Boox).
- Reader plumbing: `Page.pdf`, `CreateReaderViewState.pageIndex`, `defaultPageValue("pdf")`,
  `loadTypeAndPage("pdf")`, executor `createView(type="pdf")`.

**Annotation persistence (highlight / underline / ink) → synced RItem:**
- `CreatePdfReaderAnnotationsDbRequest` converts the reader's `position` JSON into embedded
  `RRect` (highlight/underline) and `RPath` (ink) geometry + `pageIndex`/`width` fields,
  mirroring `CreatePDFAnnotationsDbRequest` but with no Y-flip (reader emits PDF points already).
- `HtmlEpubReaderViewModel.createDatabaseAnnotations` picks this request when the doc is a PDF.
- `RItem.htmlEpubAnnotation` now injects embedded rects/paths back into `position`, so existing
  PDF annotations round-trip into the reader (no-op for EPUB).

**Boox ink end-to-end:**
- `BooxPenSpikeOverlay` forwards strokes to `HtmlEpubReaderViewModel.onBooxStrokeDrawn`.
- The VM runs the stroke through `HtmlEpubReaderWebCallChainExecutor.convertBooxInkStroke`
  (injected `BooxInkCoordinateBridge` JS → `{pageIndex,width,paths,sortIndex}` in PDF points),
  builds an ink `HtmlEpubAnnotation`, and persists it. The DB observer renders it back into the
  reader and the sync engine ships it.

## Remaining device-gated steps (in order)

0. **Verify the bundled `reader.zip` (android build) supports `type:"pdf"` + create/ink.**
   If not, build the submodule with PDF enabled (`scripts/bundle_reader_local.sh`). This is now
   the top risk, since Boox PDF opens route here with no PSPDFKit fallback.
0b. **Numeric position fields:** `htmlEpubAnnotation` serialises `pageIndex`/`width` as strings
   (field values are strings). Confirm zotero/reader's PDF view accepts them; if it needs numbers,
   coerce numeric position primitives in the injection. (Sync to Zotero desktop is already numeric
   via `createAnnotationPosition`.)

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
