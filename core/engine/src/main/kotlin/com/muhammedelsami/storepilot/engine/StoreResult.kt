package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track

/** What an operation did in one store, or would do in a dry run. */
data class StoreResult(
    val store: StoreId,
    val packageName: String,
    /** False in a dry run: the edit was discarded. */
    val committed: Boolean,
    val changes: List<Change>,
    val warnings: List<Problem>,
)

sealed interface Change {
    /** [versionCode] is null in a dry run, because nothing was uploaded. */
    data class ArtifactUpload(val artifact: Artifact, val versionCode: Long?) : Change

    /**
     * [after] was put on [track], which held [before]. In a dry run of a publish, [after] has no version
     * codes, because they come from the upload.
     */
    data class ReleaseUpdate(val track: Track, val before: List<Release>, val after: Release) : Change
}
