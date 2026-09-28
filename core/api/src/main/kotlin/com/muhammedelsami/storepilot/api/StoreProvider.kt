package com.muhammedelsami.storepilot.api

import java.nio.file.Path

/**
 * A store adapter. Implementations are found through [java.util.ServiceLoader] and need a public
 * no-argument constructor.
 */
interface StoreProvider {
    val id: StoreId

    /** For messages, for example `Google Play`. */
    val displayName: String

    val capabilities: Set<Capability>

    /** What the store accepts in a listing. Only used when [capabilities] has [Capability.Listing]. */
    val listingRules: ListingRules
        get() = ListingRules(textLimits = emptyMap(), releaseNotesLimit = null, graphics = emptyMap())

    /**
     * Checks the store-specific options from the config (the keys under `stores.<id>` that StorePilot
     * does not know). The default rejects every option.
     */
    fun validateOptions(options: Map<String, String>): List<Problem> =
        options.keys.map { Problem.error("$displayName has no option '$it'.") }

    /** Starts an edit for [StoreContext.packageName]. Only read calls may happen before [StoreEdit.commit]. */
    fun openEdit(context: StoreContext): StoreEdit
}

/** What a store supports. The engine checks the config against it before any network call. */
sealed interface Capability {
    data class Upload(val type: ArtifactType) : Capability

    /** Releases to less than 100% of users. */
    data object StagedRollout : Capability

    data object HaltAndResume : Capability

    /** Moves an existing release to another track. */
    data object Promote : Capability

    data object ReleaseNotes : Capability

    /** Listing text and app details. Supported graphic types are the keys of [ListingRules.graphics]. */
    data object Listing : Capability
}

class StoreContext(
    val packageName: String,
    /** Store-specific options, already checked with [StoreProvider.validateOptions]. */
    val options: Map<String, String>,
    val secrets: Secrets,
    val logger: Logger,
)

/**
 * A set of changes to one app in one store. Nothing is visible in the store until [commit].
 * After [commit] or [discard], no other method may be called.
 */
interface StoreEdit {
    /** The releases on [track], newest first. Empty when the track has no releases. */
    fun releases(track: Track): List<Release>

    /** Uploads [artifact] and returns the version code the store read from it. */
    fun upload(artifact: Artifact): Long

    /**
     * Puts [release] on [track]. The adapter applies the store's rules for the releases already on the
     * track, for example a staged rollout keeps the last completed release available.
     */
    fun setRelease(track: Track, release: Release)

    // Listing calls. The engine only makes them when the store has Capability.Listing.

    fun details(): AppDetails = unsupported()

    /** Changes the fields of [details] that are not null. */
    fun setDetails(details: AppDetails): Unit = unsupported()

    /** The listing text of every language the store has. */
    fun listings(): Map<LocaleTag, Map<ListingField, String>> = unsupported()

    /** Changes the given fields of the listing in [locale], and creates the listing if needed. */
    fun setListing(locale: LocaleTag, fields: Map<ListingField, String>): Unit = unsupported()

    /** The images of [type] in [locale], in display order. */
    fun images(locale: LocaleTag, type: GraphicType): List<RemoteImage> = unsupported()

    fun deleteImages(locale: LocaleTag, type: GraphicType): Unit = unsupported()

    /** Adds [file] after the existing images of [type]. */
    fun uploadImage(locale: LocaleTag, type: GraphicType, file: Path): RemoteImage = unsupported()

    /** The full-size content of [image]. */
    fun downloadImage(image: RemoteImage): ByteArray = unsupported()

    fun commit()

    fun discard()
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("This store does not support listings")

/** Thrown by adapters when a store call fails. */
open class StoreException(message: String, cause: Throwable? = null) : StorePilotException(message, cause)

open class StorePilotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
