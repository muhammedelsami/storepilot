package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.AppDetails
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.RemoteImage
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.engine.Change
import java.nio.file.Path

enum class DiffStatus {
    UNCHANGED,
    CHANGED,

    /** In the repository, not in the store. Push adds it. */
    ADDED,

    /** In the store, not in the repository. Push removes it (images of a replaced type only). */
    REMOVED,

    /** In the store, not in the repository. Push leaves it alone. */
    REMOTE_ONLY,
    ;

    val isChange: Boolean
        get() = this == CHANGED || this == ADDED || this == REMOVED
}

data class DetailsDiff(val field: String, val status: DiffStatus, val local: String?, val remote: String?)

data class TextDiff(
    val locale: LocaleTag,
    val field: ListingField,
    val status: DiffStatus,
    val local: String?,
    val remote: String?,
)

/** One image, compared by SHA-256. */
data class ImageDiff(
    val locale: LocaleTag,
    val type: GraphicType,
    val status: DiffStatus,
    val sha256: String,
    val local: Path?,
    val remote: RemoteImage?,
)

/** What a push does to one graphic type in one locale. */
data class GraphicsAction(
    val locale: LocaleTag,
    val type: GraphicType,
    /** True: delete the store's images of this type first, so the result equals [upload] in order. */
    val replace: Boolean,
    val upload: List<LocalImage>,
    val before: List<RemoteImage>,
)

/** The repository listing compared with the store (docs/design.md §4, remote diff). */
data class ListingDiff(
    val details: List<DetailsDiff>,
    val text: List<TextDiff>,
    val images: List<ImageDiff>,
    val graphicsActions: List<GraphicsAction>,
) {
    val hasChanges: Boolean
        get() = details.any { it.status.isChange } || text.any { it.status.isChange } || graphicsActions.isNotEmpty()
}

/** Compares a prepared listing with the store, and applies the difference. */
object ListingDiffer {
    /** Only read calls. Graphic types that the repository does not have are not read. */
    fun diff(edit: StoreEdit, listing: PreparedListing, replaceScreenshots: Boolean): ListingDiff {
        val details = listing.details?.let { diffDetails(it, edit.details()) }.orEmpty()

        val remoteText = edit.listings().mapValues { (_, fields) -> fields.mapValues { ListingReader.normalizeText(it.value) } }
        val text = mutableListOf<TextDiff>()
        for ((locale, fields) in listing.text) {
            val remote = remoteText[locale].orEmpty()
            for ((field, value) in fields.toSortedMap()) {
                val status = when (remote[field]) {
                    null -> DiffStatus.ADDED
                    value.value -> DiffStatus.UNCHANGED
                    else -> DiffStatus.CHANGED
                }
                text += TextDiff(locale, field, status, value.value, remote[field])
            }
        }
        for ((locale, fields) in remoteText.toSortedMap(compareBy { it.value })) {
            for ((field, value) in fields.toSortedMap()) {
                if (listing.text[locale]?.containsKey(field) != true) {
                    text += TextDiff(locale, field, DiffStatus.REMOTE_ONLY, null, value)
                }
            }
        }

        val images = mutableListOf<ImageDiff>()
        val actions = mutableListOf<GraphicsAction>()
        for ((locale, graphics) in listing.graphics) {
            for ((type, local) in graphics) {
                val remote = edit.images(locale, type)
                val replace = replaceScreenshots || !type.multiple
                val localHashes = local.map { it.info.sha256 }
                val remoteHashes = remote.map { it.sha256 }
                for (image in local) {
                    val status = if (image.info.sha256 in remoteHashes) DiffStatus.UNCHANGED else DiffStatus.ADDED
                    images += ImageDiff(locale, type, status, image.info.sha256, image.path, remote.firstOrNull { it.sha256 == image.info.sha256 })
                }
                for (image in remote.filter { it.sha256 !in localHashes }) {
                    images += ImageDiff(locale, type, if (replace) DiffStatus.REMOVED else DiffStatus.REMOTE_ONLY, image.sha256, null, image)
                }
                if (replace && localHashes != remoteHashes) {
                    actions += GraphicsAction(locale, type, replace = true, upload = local, before = remote)
                } else if (!replace) {
                    val missing = local.filter { it.info.sha256 !in remoteHashes }
                    if (missing.isNotEmpty()) actions += GraphicsAction(locale, type, replace = false, upload = missing, before = remote)
                }
            }
        }
        return ListingDiff(details, text, images, actions)
    }

    /** Makes the store calls for every change in [diff], unless [dryRun]. Returns the changes. */
    fun apply(edit: StoreEdit, diff: ListingDiff, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()

        val details = diff.details.filter { it.status.isChange }
        if (details.isNotEmpty()) {
            val values = details.associate { it.field to it.local }
            if (!dryRun) {
                edit.setDetails(
                    AppDetails(
                        defaultLanguage = values[DEFAULT_LANGUAGE]?.let(::LocaleTag),
                        contactEmail = values[CONTACT_EMAIL],
                        contactWebsite = values[CONTACT_WEBSITE],
                        contactPhone = values[CONTACT_PHONE],
                    ),
                )
            }
            details.mapTo(changes) { Change.DetailsUpdate(it.field, it.remote, it.local!!) }
        }

        for ((locale, fields) in diff.text.filter { it.status.isChange }.groupBy { it.locale }) {
            if (!dryRun) edit.setListing(locale, fields.associate { it.field to it.local!! })
            fields.mapTo(changes) { Change.ListingTextUpdate(locale, it.field, it.remote, it.local!!) }
        }

        for (action in diff.graphicsActions) {
            if (!dryRun) {
                if (action.replace && action.before.isNotEmpty()) edit.deleteImages(action.locale, action.type)
                action.upload.forEach { edit.uploadImage(action.locale, action.type, it.path) }
            }
            changes += Change.GraphicsUpdate(action.locale, action.type, action.before, action.upload.map { it.path }, action.replace)
        }
        return changes
    }

    private fun diffDetails(local: AppDetails, remote: AppDetails): List<DetailsDiff> =
        listOf(
            Triple(DEFAULT_LANGUAGE, local.defaultLanguage?.value, remote.defaultLanguage?.value),
            Triple(CONTACT_EMAIL, local.contactEmail, remote.contactEmail),
            Triple(CONTACT_WEBSITE, local.contactWebsite, remote.contactWebsite),
            Triple(CONTACT_PHONE, local.contactPhone, remote.contactPhone),
        ).mapNotNull { (field, localValue, remoteValue) ->
            val status = when {
                localValue == null && remoteValue == null -> return@mapNotNull null
                localValue == null -> DiffStatus.REMOTE_ONLY
                remoteValue == null -> DiffStatus.ADDED
                localValue == remoteValue -> DiffStatus.UNCHANGED
                else -> DiffStatus.CHANGED
            }
            DetailsDiff(field, status, localValue, remoteValue)
        }

    // The keys in details.yml.
    const val DEFAULT_LANGUAGE = "default-language"
    const val CONTACT_EMAIL = "contact-email"
    const val CONTACT_WEBSITE = "contact-website"
    const val CONTACT_PHONE = "contact-phone"
}
