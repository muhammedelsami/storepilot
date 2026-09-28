package com.muhammedelsami.storepilot.api

import java.nio.file.Path
import kotlin.io.path.extension

enum class ArtifactType(val extension: String) {
    BUNDLE("aab"),
    APK("apk"),
}

/** The file to upload. */
data class Artifact(val path: Path, val type: ArtifactType) {
    companion object {
        /** Takes the type from the file extension. */
        fun of(path: Path): Artifact {
            val type = ArtifactType.entries.firstOrNull { it.extension.equals(path.extension, ignoreCase = true) }
            requireNotNull(type) { "Artifact must be an .aab or .apk file, got '$path'" }
            return Artifact(path, type)
        }
    }
}

enum class ReleaseStatus {
    DRAFT,
    IN_PROGRESS,
    HALTED,
    COMPLETED,
}

/**
 * A release on a track.
 *
 * [rollout] is below [Rollout.FULL] for [ReleaseStatus.IN_PROGRESS] and [ReleaseStatus.HALTED], and
 * [Rollout.FULL] otherwise. A draft gets its rollout when it is started.
 */
data class Release(
    val versionCodes: List<Long>,
    val status: ReleaseStatus,
    val rollout: Rollout = Rollout.FULL,
    val releaseNotes: Map<LocaleTag, String> = emptyMap(),
    val name: String? = null,
) {
    init {
        when (status) {
            ReleaseStatus.IN_PROGRESS, ReleaseStatus.HALTED ->
                require(!rollout.isFull) { "A release with status $status needs a rollout below 100%" }
            ReleaseStatus.COMPLETED, ReleaseStatus.DRAFT ->
                require(rollout.isFull) { "A release with status $status has a 100% rollout, got $rollout" }
        }
    }
}
