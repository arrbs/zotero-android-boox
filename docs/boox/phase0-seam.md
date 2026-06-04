# Phase 0 — The Seam (zotero-android-boox)

Research output for the "Replace PSPDFKit with pdf.js + Boox Pen" build plan.
This documents the integration target (0a) and reader embeddability (0b) **as the
code actually exists today**, with file:line evidence. Read this before writing app code.

> **Headline finding.** The build plan assumes we must bundle `zotero/reader`, build a
> WebView host, and write a JS↔native bridge from scratch. **That work is already done in
> this fork — for EPUB, HTML snapshots, and web pages.** A full `zotero/reader`-in-WebView
> stack already renders documents, emits/consumes Zotero annotation objects, and writes
> them into the synced Realm store. Only **PDF** is still routed to PSPDFKit. The real task
> is therefore *not* "build a new reader" but **"extend the existing WebView reader to also
> handle `type: "pdf"`, then add the Boox pen overlay for ink"** — a much smaller, lower-risk
> change than the plan budgets for.

---

## 0a — The integration target

### One-line answer
> To add an annotation so sync ships it, I deserialize the reader's annotation JSON into a
> `ReaderAnnotation`, then execute a `CreateReaderAnnotationsDbRequest` (or its position-aware
> subclass) against **Realm**. That request writes an `RItem` of type `annotation` with
> `changeType = user` + an `RObjectChange`, which is exactly what the sync engine drains.

### Persistence layer — Realm (confirmed)
- Realm Java/Kotlin. Annotation items are `RItem` rows with `rawType = ItemTypes.annotation`.
  - `app/src/main/java/org/zotero/android/database/objects/RItem.kt:117` — `annotationType`
  - `app/src/main/java/org/zotero/android/database/objects/RItem.kt:120` — `annotationSortIndex`
- Field keys: `app/src/main/java/org/zotero/android/database/objects/FieldKeys.kt:129-155`
  (`annotationType`, `annotationColor`, `annotationSortIndex`, `annotationPosition`, …;
  `mandatoryApiFields(type)` lists required fields per annotation type).
- An annotation is an `RItem` + embedded `RItemField` rows + (for PDF) embedded `RRect`/`RPath`
  rows for geometry.

### The write path (this is the seam)
**`CreateReaderAnnotationsDbRequest<Annotation: ReaderAnnotation>`**
`app/src/main/java/org/zotero/android/database/requests/CreateReaderAnnotationsDbRequest.kt`
- `process()` (`:34`) finds the parent attachment `RItem` and creates one annotation `RItem` each.
- `create()` (`:42-91`) sets the sync-critical fields:
  - `item.rawType = ItemTypes.annotation` (`:58`)
  - `item.syncState = ObjectSyncState.synced.name` (`:66`)
  - **`item.changeType = UpdatableChangeType.user.name`** (`:67`) ← marks it for upload
  - `item.parent = parent` (`:75`)
  - **`item.changes.add(RObjectChange.create(changes = changes))`** (`:90`) ← the change record the sync uploader reads
- `addFields()` (`:94-124`) writes type/color/comment/sortIndex/text as `RItemField`s
  (each `rField.changed = true`).
- `addAdditionalProperties()` (`:129`) is an **open hook** — subclasses add geometry here.

**EPUB/snapshot subclass (working reference):**
`app/src/main/java/org/zotero/android/database/requests/CreateHtmlEpubAnnotationsDbRequest.kt`
— overrides the hooks to persist the JSON-string `annotationPosition` for HTML/EPUB.

**PSPDFKit's PDF subclass (geometry reference for our new PDF path):**
`app/src/main/java/org/zotero/android/database/requests/CreatePDFAnnotationsDbRequest.kt`
- Stores geometry as **embedded `RRect` rows** (highlight/underline) and **`RPath` rows**
  (ink), plus position fields:
  - rects → `RRect` (`:49-58`, `changes.add(RItemChanges.rects)`)
  - paths → `RPath` (`:78-97`, `changes.add(RItemChanges.paths)`)
  - position fields `pageIndex` / `lineWidth` / `rotation` / `fontSize` under
    `baseKey = FieldKeys.Item.Annotation.position` (`:104-125`)

> **Implication for the migration:** PDF annotations are stored with **structured geometry**
> (`RRect`/`RPath` + `pageIndex`), *not* the flat `annotationPosition` JSON string used by
> EPUB. So the new PDF-reader write path should be a `CreateReaderAnnotationsDbRequest`
> subclass whose `addAdditionalProperties()` mirrors `CreatePDFAnnotationsDbRequest`'s
> rects/paths handling — fed from the reader's annotation JSON (which already emits
> `pageIndex` + `rects`/`paths` in PDF points; see §9 of the plan).

### Update / delete
- Geometry edits: `EditAnnotationRectsDbRequest`, `EditAnnotationPathsDbRequest`,
  `EditAnnotationRotationDbRequest`, `EditAnnotationFontSizeDbRequest`
  (`app/src/main/java/org/zotero/android/database/requests/`).
- The EPUB ViewModel already routes reader edit/delete events through these — see
  `updateView(modifications, insertions, deletions)` below.

### Where sync reads them
Standard Zotero item-level sync. An annotation `RItem` with `changeType = user` and a
populated `RObjectChange` is indistinguishable from any other locally-changed item, so the
existing upload pipeline ships it. We add **nothing** to the sync engine — we only feed its
store, exactly as `CreateReaderAnnotationsDbRequest` already does for EPUB.

---

## 0b — Reader embeddability (already solved for EPUB; reuse for PDF)

### `zotero/reader` is vendored and bundled
- Git submodule: `.gitmodules` → `reader` → `https://github.com/zotero/reader.git`
  (root `reader/` is just not checked out in this container).
- Build/bundle script: `scripts/bundle_reader.sh`
  - Downloads the prebuilt **`android`** target for the pinned submodule hash from
    `https://zotero-download.s3.amazonaws.com/ci/reader/<hash>.zip`.
  - Zips its `android/` output to **`app/src/main/assets/reader/reader.zip`**.
  - `scripts/bundle_reader_local.sh` builds from a local checkout instead.
- At runtime the zip is unpacked into internal storage
  (`FileStore.runningHtmlEpubReaderDirectory()`,
  `app/.../files/FileStore.kt:602`) and served to the WebView.

### The WebView host + bridge (reusable as-is)
`app/src/main/java/org/zotero/android/screens/htmlepub/reader/web/HtmlEpubReaderWebViewHandler.kt`
- `javaScriptEnabled` + file/universal access (`:43-48`).
- Assets served via **`WebViewAssetLoader`** under
  `https://appassets.androidplatform.net/local/…` →
  `InternalStoragePathHandler(context, fileStore.runningHtmlEpubReaderDirectory())` (`:62-72`).
  *(This is the worker-safe asset loader the plan §10 asks for — already in place.)*
- Bridge is **`WebMessagePort` / `MessageChannel`**, not `addJavascriptInterface`:
  on `onPageFinished` it creates a channel, keeps port[0], and posts port[1] to JS via
  `postWebMessage(WebMessage("initPort", [port]))` (`:81-98`). Native→JS uses
  `evaluateJavascript(...)` (`:106-112`).

### Reader init (createView) — the PDF entry point we need
`app/.../reader/web/HtmlEpubReaderWebCallChainExecutor.kt`
- `loadDocument(data: DocumentData)` (`:308-340`) builds
  `CreateReaderViewOptions(type, url, annotations, location?, viewState?)`
  (`app/.../reader/CreateReaderViewOptions.kt`) and calls JS
  **`createView('<json>')`** (`:333`).
  - `type` is currently `"epub"` / `"snapshot"`; **`zotero/reader` also supports `"pdf"`.**
  - `url = https://appassets.androidplatform.net/local/<file>` — point this at the
    downloaded PDF and it renders.
- `DocumentData` (`app/.../reader/data/DocumentData.kt`) carries `type`, `file`,
  `annotationsJson`, `page`, `selectedAnnotationKey`. `Page` currently has `html`/`epub`
  variants only → add a `pdf(pageIndex)` variant.

### Reader → native messages (already wired)
`app/.../reader/data/HtmlEpubReaderWebData.kt` enumerates the events the reader emits:
`loadDocument`, **`saveAnnotations(params)`**, `selectAnnotationFromDocument`,
`deselectSelectedAnnotation`, `setSelectedTextParams`, `setViewState`, `showUrl`,
`parseOutline`, `processDocumentSearchResults`, `toggleInterfaceVisibility`.
- Dispatched in the executor's `receiveMessage` (`onSaveAnnotations`, `onSelectAnnotations`,
  …) at `HtmlEpubReaderWebCallChainExecutor.kt:75-130`.

### Native → reader commands (already wired)
`select`, `navigate`, `find`, `setTool`/`clearTool`, `search`/`clearSearch`,
`updateAnnotations({deletions,insertions,modifications})`, `setColorScheme`, `createView`
(`HtmlEpubReaderWebCallChainExecutor.kt:227-350`).

### ViewModel glue (already wired)
`app/.../reader/HtmlEpubReaderViewModel.kt` (~1995 lines):
- `saveAnnotations(params)` (`:680`) parses the reader's annotation JSON →
  `ReaderAnnotation` list → persists via the DbRequest above (`store annotations`, `:602`).
- Text-anchored highlight/underline from selection already implemented:
  `saveAnnotationFromSelection(AnnotationType.highlight)` (`:1918`) /
  `…underline` (`:1926`). **This is the "exports to Obsidian as text" property the plan
  wants — already working for EPUB, and identical for PDF text selection via pdf.js.**
- Document-type branch (`:970-994`) handles `"epub"` / `"snapshot"` only — **no `"pdf"`
  branch yet.** This is the concrete gap.

---

## How PDF is routed today (what Phase 4 will repoint)
- Open-PDF flow: `AllItemsViewModel` → `NavigateToPdfScreen` (`:329-338`, effect `:1414`) →
  PSPDFKit reader `app/src/main/java/org/zotero/android/pdf/reader/PdfReaderViewModel.kt`
  (3751 lines; uses `com.pspdfkit.annotations.AnnotationProvider` listeners at
  `:230,769,1018,1052,2586`).
- EPUB/HTML flow: `onShowHtmlOrEpub { … }` → `htmlEpubReaderNavScreensForPhone/Tablet`
  (`architecture/navigation/phone|tablet/DashboardRoot*Navigation.kt`).

## PSPDFKit removal map (Phase 4)
- Dependency: `buildSrc/src/main/kotlin/Libs.kt:5` → `nutrient = "com.pspdfkit:pspdfkit:2024.4.0"`,
  used at `app/build.gradle.kts:159` (`implementation(Libs.nutrient)`).
- Key plumbing: `app/build.gradle.kts:43,111,116,191` (`PSPDFKIT_KEY`, `readPspdfkitKey()`,
  reads `pspdfkit-key.txt`).
- Reader screen + translation to delete once PDF runs on the WebView reader:
  `app/src/main/java/org/zotero/android/pdf/reader/` (esp. `PdfReaderViewModel.kt`,
  `PdfReaderPspdfKitView.kt`), `app/src/main/java/org/zotero/android/sync/AnnotationConverter.kt`,
  `app/src/main/java/org/zotero/android/pdf/data/AnnotationBoundingBoxConverter.kt`.
- 38 `com.pspdfkit` references across `.kt`/`.gradle` (Phase 4 exit criterion: zero).

---

## Revised, lower-risk path (supersedes plan Phases 2–4 mechanics)

Because the WebView reader stack already exists, prefer **generalizing the existing
`htmlepub` reader to handle PDF** over building a parallel screen:

1. **Route PDFs to the WebView reader behind a debug flag.** Add a `"pdf"` branch to the
   document-type logic (`HtmlEpubReaderViewModel.kt:970-994`), a `Page.pdf(pageIndex)`
   variant (`DocumentData.kt`), and call `createView` with `type = "pdf"` and the local PDF
   URL. Keep `NavigateToPdfScreen` → PSPDFKit intact for fallback. *(Plan Phase 2 — but
   reusing the existing host, not a new Activity.)*
2. **Persist PDF annotations with geometry.** Add a `CreateReaderAnnotationsDbRequest`
   subclass (or extend `saveAnnotations`) whose `addAdditionalProperties()` mirrors
   `CreatePDFAnnotationsDbRequest`'s `RRect`/`RPath` + `pageIndex` writes, fed from the
   reader's PDF annotation JSON. Highlight/underline/note round-trip through sync.
   *(Plan Phase 3 — the make-or-break gate. Largely a geometry-mapping exercise, since the
   message plumbing and the EPUB write path already work.)*
3. **Flip default + delete PSPDFKit.** Repoint `AllItemsViewModel` open-PDF to the WebView
   reader; remove the dependency, key plumbing, the `pdf/reader/` PSPDFKit screen, and
   `AnnotationConverter`/`AnnotationBoundingBoxConverter`. *(Plan Phase 4.)*
4. **Boox pen overlay for ink.** This is the only genuinely new subsystem (plan Phases 5–6):
   transparent Onyx `SurfaceView` + `TouchHelper`, stroke→PDF-point bridge (plan §7) into a
   `RPath`-backed ink `RItem` via the same store. Gate behind an `onyxsdk-device` "is Boox?"
   check so normal phones still run.

### Still-open unknowns (need device / submodule)
- **0c (e-ink perf):** unchanged — must be judged on the Boox. But note pdf.js rendering
  already ships inside this app's reader, lowering the unknown.
- **Does the bundled `zotero/reader` `android` build expose `type:"pdf"` create-ink?**
  Confirm against the checked-out submodule / built `reader.zip`. If the prebuilt CI `android`
  target excludes PDF, we either build the submodule with PDF enabled (`bundle_reader_local.sh`)
  or feed ink through the DbRequest directly (plan §7 step 5 fallback).
- **Coordinate accuracy after scroll/zoom** (plan §7 validation gate) — device test.
