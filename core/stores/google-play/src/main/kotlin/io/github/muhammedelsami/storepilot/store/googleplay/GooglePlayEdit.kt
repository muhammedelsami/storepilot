package io.github.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.AppEdit
import com.google.api.services.androidpublisher.model.Listing
import com.google.api.services.androidpublisher.model.LocalizedText
import com.google.api.services.androidpublisher.model.TrackRelease
import io.github.muhammedelsami.storepilot.api.AppDetails
import io.github.muhammedelsami.storepilot.api.Artifact
import io.github.muhammedelsami.storepilot.api.ArtifactType
import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.Logger
import io.github.muhammedelsami.storepilot.api.Release
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.RemoteImage
import io.github.muhammedelsami.storepilot.api.Rollout
import io.github.muhammedelsami.storepilot.api.StoreEdit
import io.github.muhammedelsami.storepilot.api.StoreException
import io.github.muhammedelsami.storepilot.api.Track
import java.io.IOException
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import com.google.api.services.androidpublisher.model.AppDetails as PlayAppDetails
import com.google.api.services.androidpublisher.model.Track as PlayTrack

/**
 * One Play edit. Releases read from Play are kept as Play sent them, so that fields StorePilot does
 * not model (country targeting, in-app update priority) survive a status change or a promotion.
 */
internal class GooglePlayEdit(
    publisher: AndroidPublisher,
    private val packageName: String,
    private val options: PlayOptions,
    private val logger: Logger,
    private val download: (url: String) -> ByteArray,
) : StoreEdit {
    private val edits = publisher.edits()
    private val editId: String = call("start an edit") { edits.insert(packageName, AppEdit()).execute().id }

    /** Play releases seen in this edit, by sorted version codes. */
    private val originals = mutableMapOf<List<Long>, TrackRelease>()

    /** Version codes uploaded in this edit. */
    private val uploaded = mutableSetOf<Long>()

    /** Play's own code for each language read in this edit, such as `iw-IL` for `he-IL`. */
    private val playLanguages = mutableMapOf<LocaleTag, String>()

    /** Play's listings as read in this edit, by language. Updates start from them. */
    private var remoteListings: MutableMap<LocaleTag, Listing>? = null

    override fun releases(track: Track): List<Release> =
        playReleases(track)
            .map { original ->
                originals[key(original.versionCodes)] = original
                toRelease(original)
            }
            .sortedByDescending { it.versionCodes.maxOrNull() ?: 0L }

    override fun upload(artifact: Artifact): Long {
        val file = artifact.path.toFile()
        logger.info("Uploading ${artifact.path.fileName} (${megabytes(artifact.path.fileSize())}) to Google Play")
        val versionCode = call("upload ${artifact.path.fileName}") {
            when (artifact.type) {
                ArtifactType.BUNDLE ->
                    edits.bundles().upload(packageName, editId, FileContent(BUNDLE_MEDIA_TYPE, file)).execute().versionCode
                ArtifactType.APK ->
                    edits.apks().upload(packageName, editId, FileContent(APK_MEDIA_TYPE, file)).execute().versionCode
            }
        }.toLong()
        uploaded += versionCode
        return versionCode
    }

    /**
     * Applies Play's track rules: a completed release replaces the track, a staged or halted release
     * keeps the last completed release, and a draft keeps everything except the previous draft.
     */
    override fun setRelease(track: Track, release: Release) {
        val current = playReleases(track)
        val kept = when (release.status) {
            ReleaseStatus.COMPLETED -> emptyList()
            ReleaseStatus.IN_PROGRESS, ReleaseStatus.HALTED -> listOfNotNull(current.firstOrNull { it.status == "completed" })
            ReleaseStatus.DRAFT -> current.filter { it.status != "draft" }
        }
        val key = key(release.versionCodes)
        val releases = listOf(toTrackRelease(release)) + kept.filter { key(it.versionCodes) != key }
        val name = trackName(track)
        call("update track '$name'") {
            edits.tracks().update(packageName, editId, name, PlayTrack().setTrack(name).setReleases(releases)).execute()
        }
    }

    override fun details(): AppDetails {
        val details = playDetails()
        return AppDetails(
            defaultLanguage = details.defaultLanguage?.let(::locale),
            contactEmail = details.contactEmail?.ifBlank { null },
            contactWebsite = details.contactWebsite?.ifBlank { null },
            contactPhone = details.contactPhone?.ifBlank { null },
        )
    }

    // Updates send the whole resource: the Java client's default transport cannot send PATCH requests.
    override fun setDetails(details: AppDetails) {
        val update = playDetails().apply {
            details.defaultLanguage?.let { defaultLanguage = playLanguage(it) }
            details.contactEmail?.let { contactEmail = it }
            details.contactWebsite?.let { contactWebsite = it }
            details.contactPhone?.let { contactPhone = it }
        }
        call("update the app details") { edits.details().update(packageName, editId, update).execute() }
    }

    override fun listings(): Map<LocaleTag, Map<ListingField, String>> =
        playListings().mapValues { (_, listing) ->
            listOfNotNull(
                listing.title?.let { ListingField.TITLE to it },
                listing.shortDescription?.let { ListingField.SHORT_DESCRIPTION to it },
                listing.fullDescription?.let { ListingField.FULL_DESCRIPTION to it },
                listing.video?.let { ListingField.VIDEO_URL to it },
            ).filter { it.second.isNotBlank() }.toMap()
        }

    override fun setListing(locale: LocaleTag, fields: Map<ListingField, String>) {
        val listings = playListings()
        val language = playLanguage(locale)
        val listing = listings[locale]?.clone() ?: Listing()
        listing.language = language
        for ((field, value) in fields) {
            when (field) {
                ListingField.TITLE -> listing.title = value
                ListingField.SHORT_DESCRIPTION -> listing.shortDescription = value
                ListingField.FULL_DESCRIPTION -> listing.fullDescription = value
                ListingField.VIDEO_URL -> listing.video = value
            }
        }
        call("update the $language listing") { edits.listings().update(packageName, editId, language, listing).execute() }
        listings[locale] = listing
    }

    override fun images(locale: LocaleTag, type: GraphicType): List<RemoteImage> {
        val language = playLanguage(locale)
        val response = call("read the $language ${type.fileName} images") {
            edits.images().list(packageName, editId, language, imageType(type)).execute()
        }
        return response.images.orEmpty().map { RemoteImage(it.id, it.sha256.orEmpty().lowercase(), it.url) }
    }

    override fun deleteImages(locale: LocaleTag, type: GraphicType) {
        val language = playLanguage(locale)
        call("delete the $language ${type.fileName} images") {
            edits.images().deleteall(packageName, editId, language, imageType(type)).execute()
        }
    }

    override fun uploadImage(locale: LocaleTag, type: GraphicType, file: Path): RemoteImage {
        val language = playLanguage(locale)
        val mediaType = if (file.extension.lowercase() == "png") "image/png" else "image/jpeg"
        logger.info("Uploading ${file.fileName} to the $language ${type.fileName} images")
        val image = call("upload ${file.fileName}") {
            edits.images().upload(packageName, editId, language, imageType(type), FileContent(mediaType, file.toFile())).execute()
        }.image
        return RemoteImage(image.id, image.sha256.orEmpty().lowercase(), image.url)
    }

    override fun downloadImage(image: RemoteImage): ByteArray {
        val url = image.url ?: throw StoreException("Google Play sent no URL for image ${image.id}.")
        return call("download image ${image.id}") { download(url) }
    }

    override fun commit() {
        call("commit the edit") {
            edits.commit(packageName, editId)
                .apply { if (options.changesNotSentForReview) changesNotSentForReview = true }
                .execute()
        }
    }

    override fun discard() {
        call("delete the edit") { edits.delete(packageName, editId).execute() }
    }

    private fun playReleases(track: Track): List<TrackRelease> {
        val name = trackName(track)
        return call("read track '$name'") { edits.tracks().get(packageName, editId, name).execute() }.releases.orEmpty()
    }

    private fun toRelease(release: TrackRelease): Release =
        try {
            Release(
                versionCodes = release.versionCodes.orEmpty(),
                status = when (release.status) {
                    "draft" -> ReleaseStatus.DRAFT
                    "inProgress" -> ReleaseStatus.IN_PROGRESS
                    "halted" -> ReleaseStatus.HALTED
                    "completed" -> ReleaseStatus.COMPLETED
                    else -> throw IllegalArgumentException("unknown status '${release.status}'")
                },
                rollout = release.userFraction?.let(::Rollout) ?: Rollout.FULL,
                releaseNotes = release.releaseNotes.orEmpty().associate { locale(it.language) to it.text },
                name = release.name,
            )
        } catch (e: IllegalArgumentException) {
            throw StoreException("Google Play returned a release StorePilot cannot read (${e.message}): $release", e)
        }

    private fun toTrackRelease(release: Release): TrackRelease {
        val original = originals[key(release.versionCodes)]
        val playRelease = original?.clone() ?: TrackRelease()
        playRelease.versionCodes = release.versionCodes
        playRelease.status = when (release.status) {
            ReleaseStatus.DRAFT -> "draft"
            ReleaseStatus.IN_PROGRESS -> "inProgress"
            ReleaseStatus.HALTED -> "halted"
            ReleaseStatus.COMPLETED -> "completed"
        }
        playRelease.userFraction = if (release.rollout.isFull) null else release.rollout.fraction
        // Unchanged notes and names are sent back exactly as Play sent them.
        if (original == null || toRelease(original).releaseNotes != release.releaseNotes) {
            playRelease.releaseNotes = release.releaseNotes
                .map { (locale, text) -> LocalizedText().setLanguage(locale.value).setText(text) }
                .ifEmpty { null }
        }
        if (original == null || original.name != release.name) playRelease.name = release.name
        if (release.versionCodes.any { it in uploaded }) {
            options.inAppUpdatePriority?.let { playRelease.inAppUpdatePriority = it }
        }
        return playRelease
    }

    private fun playDetails(): PlayAppDetails =
        call("read the app details") { edits.details().get(packageName, editId).execute() }
            .also { details -> details.defaultLanguage?.let(::locale) }

    private fun playListings(): MutableMap<LocaleTag, Listing> =
        remoteListings ?: call("read the listings") { edits.listings().list(packageName, editId).execute() }
            .listings.orEmpty()
            .associateByTo(mutableMapOf()) { locale(it.language) }
            .also { remoteListings = it }

    /** Reads a Play language code and remembers it, so that writes use Play's own code. */
    private fun locale(language: String): LocaleTag {
        val locale = LocaleTag(Locale.forLanguageTag(language).toLanguageTag())
        playLanguages[locale] = language
        return locale
    }

    private fun playLanguage(locale: LocaleTag): String = playLanguages[locale] ?: locale.value

    private inline fun <T> call(action: String, block: () -> T): T =
        try {
            block()
        } catch (e: GoogleJsonResponseException) {
            val reason = e.details?.message ?: e.statusMessage
            throw StoreException("Google Play could not $action for $packageName: ${e.statusCode} $reason", e)
        } catch (e: IOException) {
            throw StoreException("Google Play could not $action for $packageName: ${e.message}", e)
        }

    companion object {
        private const val BUNDLE_MEDIA_TYPE = "application/octet-stream"
        private const val APK_MEDIA_TYPE = "application/vnd.android.package-archive"

        /**
         * Play's name for a track. The portable `testing` track is closed testing (`alpha`), so that a
         * testing release is never public by accident. Other names are passed as-is.
         */
        fun trackName(track: Track): String = if (track == Track.TESTING) "alpha" else track.name

        private fun key(versionCodes: List<Long>?): List<Long> = versionCodes.orEmpty().sorted()

        /** Play's AppImageType names. */
        fun imageType(type: GraphicType): String = when (type) {
            GraphicType.ICON -> "icon"
            GraphicType.FEATURE_GRAPHIC -> "featureGraphic"
            GraphicType.PHONE_SCREENSHOTS -> "phoneScreenshots"
            GraphicType.TABLET_7_SCREENSHOTS -> "sevenInchScreenshots"
            GraphicType.TABLET_10_SCREENSHOTS -> "tenInchScreenshots"
            GraphicType.TV_SCREENSHOTS -> "tvScreenshots"
            GraphicType.TV_BANNER -> "tvBanner"
            GraphicType.WEAR_SCREENSHOTS -> "wearScreenshots"
        }

        private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }
}
