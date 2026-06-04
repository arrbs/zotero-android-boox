package org.zotero.android.database.requests

import io.realm.Realm
import org.zotero.android.database.objects.FieldKeys
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.objects.RItemChanges
import org.zotero.android.database.objects.RItemField
import org.zotero.android.database.objects.RPath
import org.zotero.android.database.objects.RPathCoordinate
import org.zotero.android.database.objects.RRect
import org.zotero.android.database.objects.RTag
import org.zotero.android.database.objects.RTypedTag
import org.zotero.android.ktx.rounded
import org.zotero.android.screens.htmlepub.reader.data.HtmlEpubAnnotation
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.SchemaController
import timber.log.Timber

/**
 * Persists annotations created in the WebView reader **for PDF documents**, storing geometry the
 * way Zotero PDF annotations expect it: embedded [RRect] (highlight/underline) and [RPath] (ink)
 * objects plus `pageIndex`/`width` position fields — mirroring [CreatePDFAnnotationsDbRequest], but
 * sourced from the reader's `position` JSON instead of PSPDFKit objects.
 *
 * The reader (and the Boox ink bridge) already emit coordinates in PDF points, bottom-left origin —
 * Zotero's native DB convention — so unlike the PSPDFKit path there is **no** bounding-box / Y-flip
 * conversion here.
 *
 * Reused for both reader text highlight/underline (rects) and Boox freehand ink (paths); the
 * annotation type and which geometry is present come from [HtmlEpubAnnotation.position].
 */
class CreatePdfReaderAnnotationsDbRequest(
    attachmentKey: String,
    libraryId: LibraryIdentifier,
    private val annotations: List<HtmlEpubAnnotation>,
    userId: Long,
    schemaController: SchemaController,
) : CreateReaderAnnotationsDbRequest<HtmlEpubAnnotation>(
    attachmentKey = attachmentKey,
    libraryId = libraryId,
    annotations = annotations,
    userId = userId,
    schemaController = schemaController,
) {

    override fun addFields(annotation: HtmlEpubAnnotation, item: RItem, database: Realm) {
        super.addFields(annotation, item, database)

        val position = annotation.position
        val pageIndex = position[FieldKeys.Item.Annotation.Position.pageIndex]?.asString
            ?: position[FieldKeys.Item.Annotation.Position.pageIndex]?.asInt?.toString()
            ?: "0"

        for (field in FieldKeys.Item.Annotation.extraPDFFields(annotation.type)) {
            val rField = database.createEmbeddedObject(RItemField::class.java, item, "fields")
            rField.key = field.key
            rField.baseKey = field.baseKey
            rField.changed = true
            when {
                field.key == FieldKeys.Item.Annotation.Position.pageIndex && field.baseKey == FieldKeys.Item.Annotation.position -> {
                    rField.value = pageIndex
                }

                field.key == FieldKeys.Item.Annotation.Position.lineWidth && field.baseKey == FieldKeys.Item.Annotation.position -> {
                    // Width travels in the position JSON (HtmlEpubAnnotation.lineWidth is always 0).
                    val width = position[FieldKeys.Item.Annotation.Position.lineWidth]?.asFloat
                    rField.value = width?.rounded(3)?.toString() ?: ""
                }

                field.key == FieldKeys.Item.Annotation.pageLabel -> {
                    rField.value = annotation.pageLabel
                }

                field.key == FieldKeys.Item.Annotation.Position.rotation && field.baseKey == FieldKeys.Item.Annotation.position -> {
                    rField.value = "0"
                }

                field.key == FieldKeys.Item.Annotation.Position.fontSize && field.baseKey == FieldKeys.Item.Annotation.position -> {
                    rField.value = "0"
                }

                else -> {
                    Timber.w("CreatePdfReaderAnnotationsDbRequest: unknown field ${field.key}, empty value")
                    rField.value = ""
                }
            }
        }
    }

    override fun addAdditionalProperties(
        annotation: HtmlEpubAnnotation,
        fromRestore: Boolean,
        item: RItem,
        changes: MutableList<RItemChanges>,
        database: Realm,
    ) {
        val position = annotation.position

        position[FieldKeys.Item.Annotation.Position.rects]?.asJsonArray?.let { rects ->
            if (fromRestore) {
                item.rects.deleteAllFromRealm()
            }
            for (rect in rects) {
                val arr = rect.asJsonArray
                if (arr.size() < 4) continue
                val rRect = database.createEmbeddedObject(RRect::class.java, item, "rects")
                rRect.minX = arr[0].asDouble
                rRect.minY = arr[1].asDouble
                rRect.maxX = arr[2].asDouble
                rRect.maxY = arr[3].asDouble
            }
            changes.add(RItemChanges.rects)
        }

        position[FieldKeys.Item.Annotation.Position.paths]?.asJsonArray?.let { paths ->
            if (fromRestore) {
                item.paths.deleteAllFromRealm()
            }
            paths.forEachIndexed { pathIdx, path ->
                val flat = path.asJsonArray
                val rPath = database.createEmbeddedObject(RPath::class.java, item, "paths")
                rPath.sortIndex = pathIdx
                flat.forEachIndexed { coordIdx, value ->
                    val rCoordinate =
                        database.createEmbeddedObject(RPathCoordinate::class.java, rPath, "coordinates")
                    rCoordinate.value = value.asDouble
                    rCoordinate.sortIndex = coordIdx
                }
            }
            changes.add(RItemChanges.paths)
        }
    }

    override fun addTags(annotation: HtmlEpubAnnotation, item: RItem, database: Realm) {
        val allTags = database.where(RTag::class.java)
        for (tag in annotation.tags) {
            val rTag = allTags.equalTo("name", tag.name).findFirst() ?: continue
            val rTypedTag = database.createObject(RTypedTag::class.java)
            rTypedTag.type = RTypedTag.Kind.manual.name
            rTypedTag.item = item
            rTypedTag.tag = rTag
        }
    }
}
