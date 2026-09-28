package com.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.AppEdit
import com.google.api.services.androidpublisher.model.LocalizedText
import com.google.api.services.androidpublisher.model.TrackRelease
import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Logger
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.api.StoreException
import com.muhammedelsami.storepilot.api.Track
import java.io.IOException
import java.util.Locale
import kotlin.io.path.fileSize
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
) : StoreEdit {
    private val edits = publisher.edits()
    private val editId: String = call("start an edit") { edits.insert(packageName, AppEdit()).execute().id }

    /** Play releases seen in this edit, by sorted version codes. */
    private val originals = mutableMapOf<List<Long>, TrackRelease>()

    /** Version codes uploaded in this edit. */
    private val uploaded = mutableSetOf<Long>()

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
                releaseNotes = release.releaseNotes.orEmpty().associate { LocaleTag(canonicalTag(it.language)) to it.text },
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

        // Play may send legacy codes such as iw-IL; LocaleTag needs the canonical he-IL.
        private fun canonicalTag(language: String): String = Locale.forLanguageTag(language).toLanguageTag()

        private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }
}
