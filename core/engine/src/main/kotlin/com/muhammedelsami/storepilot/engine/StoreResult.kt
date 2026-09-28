package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.RemoteImage
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.listing.ListingDiff
import java.nio.file.Path

/** What an operation did in one store, or would do in a dry run. */
data class StoreResult(
    val store: StoreId,
    val packageName: String,
    /** False in a dry run: the edit was discarded. */
    val committed: Boolean,
    val changes: List<Change>,
    val warnings: List<Problem>,
    /** The full listing comparison, for operations that include the listing. */
    val listingDiff: ListingDiff? = null,
)

sealed interface Change {
    /** [versionCode] is null in a dry run, because nothing was uploaded. */
    data class ArtifactUpload(val artifact: Artifact, val versionCode: Long?) : Change

    /**
     * [after] was put on [track], which held [before]. In a dry run of a publish, [after] has no version
     * codes, because they come from the upload.
     */
    data class ReleaseUpdate(val track: Track, val before: List<Release>, val after: Release) : Change

    /** [field] is the key in `details.yml`. */
    data class DetailsUpdate(val field: String, val before: String?, val after: String) : Change

    data class ListingTextUpdate(val locale: LocaleTag, val field: ListingField, val before: String?, val after: String) : Change

    /** [replaced]: the store's images of the type were deleted first. Otherwise [after] was added. */
    data class GraphicsUpdate(
        val locale: LocaleTag,
        val type: GraphicType,
        val before: List<RemoteImage>,
        val after: List<Path>,
        val replaced: Boolean,
    ) : Change
}
