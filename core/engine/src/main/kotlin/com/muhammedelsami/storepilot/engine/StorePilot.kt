package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.Capability
import com.muhammedelsami.storepilot.api.Logger
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.Secrets
import com.muhammedelsami.storepilot.api.StoreContext
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.StoreProvider
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import com.muhammedelsami.storepilot.engine.config.StoreSettings
import com.muhammedelsami.storepilot.engine.config.configName
import com.muhammedelsami.storepilot.engine.listing.ListingDiff
import com.muhammedelsami.storepilot.engine.listing.ListingDiffer
import com.muhammedelsami.storepilot.engine.listing.ListingPuller
import com.muhammedelsami.storepilot.engine.listing.ListingReader
import com.muhammedelsami.storepilot.engine.listing.ListingValidation
import com.muhammedelsami.storepilot.engine.listing.ListingValidator
import com.muhammedelsami.storepilot.engine.listing.PreparedListing
import com.muhammedelsami.storepilot.engine.listing.PullResult
import com.muhammedelsami.storepilot.engine.metadata.ReleaseNotesReader
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** The listing check of one store. */
class ListingCheck(val store: StoreId, val validation: ListingValidation)

/**
 * The release and listing operations shared by the Gradle plugin, the CLI, and the action.
 *
 * Each operation first validates every target store without network calls and throws a
 * [ValidationException] if anything is wrong. Then it runs one edit per store, in order. An edit is
 * committed, or discarded in a dry run or on failure. Stores that come before a failing store keep
 * their committed changes.
 */
class StorePilot(
    private val stores: StoreRegistry,
    private val secrets: (StoreId) -> Secrets,
    private val logger: Logger = Logger.NONE,
) {
    private val releaseNotes = ReleaseNotesReader(stores.ids)

    /**
     * Uploads [artifact] and puts it on each target's track with its rollout and release notes. With
     * [withListing], the listing is pushed in the same edit, so both are committed together or not at all.
     */
    fun publish(
        artifact: Artifact,
        targets: List<StoreSettings>,
        dryRun: Boolean = false,
        withListing: Boolean = false,
    ): List<StoreResult> {
        val artifactProblems = if (artifact.path.isRegularFile()) {
            emptyList()
        } else {
            listOf(Problem.error("The artifact file does not exist.", artifact.path.toString()))
        }
        val jobs = targets.map { settings ->
            Job(settings, stores[settings.store]).apply {
                require(Capability.Upload(artifact.type), "${artifact.type.extension} uploads")
                val status = releaseStatus()
                val notes = releaseNotes.read(settings.metadataDir, settings.store).let { result ->
                    problems += result.problems
                    if (result.notes.isNotEmpty() && !optional(Capability.ReleaseNotes, "release notes")) {
                        emptyMap()
                    } else {
                        result.notes
                    }
                }
                val limit = provider.listingRules.releaseNotesLimit
                for ((locale, text) in notes) {
                    val length = text.codePointCount(0, text.length)
                    if (limit != null && length > limit) {
                        problems += Problem.error(
                            "The $locale release notes have $length characters; ${provider.displayName} allows $limit.",
                            settings.metadataDir.resolve("release-notes").toString(),
                        )
                    }
                }
                val listing = if (withListing) prepareListing(textOnly = false) else null
                calls = { edit ->
                    val track = settings.track
                    val before = edit.releases(track)
                    val release = if (dryRun) {
                        val after = Release(emptyList(), status, settings.rollout, notes)
                        listOf(Change.ArtifactUpload(artifact, null), Change.ReleaseUpdate(track, before, after))
                    } else {
                        val versionCode = edit.upload(artifact)
                        val after = Release(listOf(versionCode), status, settings.rollout, notes)
                        edit.setRelease(track, after)
                        listOf(Change.ArtifactUpload(artifact, versionCode), Change.ReleaseUpdate(track, before, after))
                    }
                    release + listing?.let { pushListing(edit, it, dryRun) }.orEmpty()
                }
            }
        }
        return execute(jobs, artifactProblems, dryRun)
    }

    /** Puts the newest release on [from] on [to] with [rollout]. Release notes and name stay the same. */
    fun promote(
        from: Track,
        to: Track,
        rollout: Rollout,
        targets: List<StoreSettings>,
        dryRun: Boolean = false,
    ): List<StoreResult> {
        val jobs = targets.map { settings ->
            Job(settings, stores[settings.store]).apply {
                require(Capability.Promote, "promoting a release")
                if (from == to) problems += Problem.error("Cannot promote from track '$from' to itself.", source)
                if (!rollout.isFull) require(Capability.StagedRollout, "staged rollouts ($rollout)")
                val status = if (rollout.isFull) ReleaseStatus.COMPLETED else ReleaseStatus.IN_PROGRESS
                calls = { edit ->
                    val release = edit.releases(from)
                        .filter { it.status != ReleaseStatus.DRAFT }
                        .maxByOrNull { it.versionCodes.maxOrNull() ?: 0L }
                        ?: throw ValidationException(Problem.error("Track '$from' has no release to promote.", source))
                    val before = edit.releases(to)
                    val after = release.copy(status = status, rollout = rollout)
                    if (!dryRun) edit.setRelease(to, after)
                    listOf(Change.ReleaseUpdate(to, before, after))
                }
            }
        }
        return execute(jobs, emptyList(), dryRun)
    }

    /** Checks each target's listing against its store's rules and the lint rules. No network calls. */
    fun validateListing(targets: List<StoreSettings>): List<ListingCheck> {
        val missing = targets.map { it.metadataDir }.distinct().filter { !it.isDirectory() }
        if (missing.isNotEmpty()) {
            throw ValidationException(missing.map { Problem.error("The metadata directory does not exist.", it.toString()) })
        }
        return targets.map { settings ->
            val provider = stores[settings.store]
            val validation = ListingValidator(provider.listingRules, provider.displayName, settings)
                .validate(ListingReader.read(settings.metadataDir))
            ListingCheck(settings.store, validation)
        }
    }

    /**
     * Makes the store listing equal to the repository: details, text, and (unless [textOnly]) graphics.
     * Each result holds the full comparison in [StoreResult.listingDiff].
     */
    fun pushListing(targets: List<StoreSettings>, textOnly: Boolean = false, dryRun: Boolean = false): List<StoreResult> {
        val jobs = targets.map { settings ->
            Job(settings, stores[settings.store]).apply {
                val listing = prepareListing(textOnly)
                calls = { edit -> pushListing(edit, listing, dryRun) }
            }
        }
        return execute(jobs, emptyList(), dryRun)
    }

    /** Compares the repository listing with the store. Only reads; the same as a dry run of [pushListing]. */
    fun diffListing(targets: List<StoreSettings>): List<StoreResult> = pushListing(targets, dryRun = true)

    /** Writes the store's listing into the metadata directory. Existing files need [overwrite]. */
    fun pullListing(target: StoreSettings, overwrite: Boolean = false): PullResult {
        val provider = stores[target.store]
        if (Capability.Listing !in provider.capabilities) {
            throw ValidationException(Problem.error("${provider.displayName} does not support store listings."))
        }
        val edit = provider.openEdit(context(target))
        val result = discardOnFailure(edit) {
            ListingPuller(edit, provider.listingRules).pull(target.store, target.metadataDir, overwrite)
        }
        edit.discard()
        return result
    }

    /** Stops the staged rollout on [track]. */
    fun halt(track: Track, targets: List<StoreSettings>, dryRun: Boolean = false): List<StoreResult> =
        changeStatus(track, ReleaseStatus.IN_PROGRESS, ReleaseStatus.HALTED, targets, dryRun)

    /** Continues the halted staged rollout on [track]. */
    fun resume(track: Track, targets: List<StoreSettings>, dryRun: Boolean = false): List<StoreResult> =
        changeStatus(track, ReleaseStatus.HALTED, ReleaseStatus.IN_PROGRESS, targets, dryRun)

    private fun changeStatus(
        track: Track,
        from: ReleaseStatus,
        to: ReleaseStatus,
        targets: List<StoreSettings>,
        dryRun: Boolean,
    ): List<StoreResult> {
        val jobs = targets.map { settings ->
            Job(settings, stores[settings.store]).apply {
                require(Capability.HaltAndResume, "halting and resuming rollouts")
                calls = { edit ->
                    val before = edit.releases(track)
                    val release = before.firstOrNull { it.status == from } ?: throw ValidationException(
                        Problem.error("Track '$track' has no release with status ${from.configName}.", source),
                    )
                    val after = release.copy(status = to)
                    if (!dryRun) edit.setRelease(track, after)
                    listOf(Change.ReleaseUpdate(track, before, after))
                }
            }
        }
        return execute(jobs, emptyList(), dryRun)
    }

    private fun execute(jobs: List<Job>, problems: List<Problem>, dryRun: Boolean): List<StoreResult> {
        val all = problems + jobs.flatMap { it.problems }
        if (all.any { it.isError }) throw ValidationException(all)
        return jobs.map { run(it, dryRun) }
    }

    private fun run(job: Job, dryRun: Boolean): StoreResult {
        val settings = job.settings
        val edit = job.provider.openEdit(context(settings))
        val changes = discardOnFailure(edit) { job.calls(edit) }
        if (dryRun) {
            edit.discard()
        } else {
            discardOnFailure(edit) { edit.commit() }
        }
        return StoreResult(settings.store, settings.packageName, !dryRun, changes, job.problems, job.listingDiff)
    }

    private fun context(settings: StoreSettings) =
        StoreContext(settings.packageName, settings.options, secrets(settings.store), logger)

    private inline fun <T> discardOnFailure(edit: StoreEdit, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            try {
                edit.discard()
            } catch (discardFailure: Exception) {
                e.addSuppressed(discardFailure)
            }
            throw e
        }

    /** Validation state and the store calls for one target store. */
    private class Job(val settings: StoreSettings, val provider: StoreProvider) {
        val source = "stores.${settings.store}"
        val problems = mutableListOf<Problem>()
        var listingDiff: ListingDiff? = null
        /** The store calls. They only read when the operation is a dry run. */
        lateinit var calls: (StoreEdit) -> List<Change>

        init {
            provider.validateOptions(settings.options).mapTo(problems) { it.copy(source = it.source ?: source) }
        }

        /** Something the operation cannot do without, whatever `onUnsupported` says. */
        fun require(capability: Capability, what: String) {
            if (capability !in provider.capabilities) {
                problems += Problem.error("${provider.displayName} does not support $what.", source)
            }
        }

        /** Returns false when unsupported. With `onUnsupported: warn`, the caller then skips it. */
        fun optional(capability: Capability, what: String): Boolean {
            if (capability in provider.capabilities) return true
            problems += when (settings.onUnsupported) {
                OnUnsupported.FAIL -> Problem.error(
                    "${provider.displayName} does not support $what. Set onUnsupported to warn to skip them for this store.",
                    source,
                )
                OnUnsupported.WARN -> Problem.warning("${provider.displayName} does not support $what, so they are skipped.", source)
            }
            return false
        }

        /** Reads and validates the listing. Its problems join the others. */
        fun prepareListing(textOnly: Boolean): PreparedListing {
            require(Capability.Listing, "store listings")
            if (!settings.metadataDir.isDirectory()) {
                problems += Problem.error("The metadata directory does not exist.", settings.metadataDir.toString())
            }
            val listingSettings = if (textOnly) settings.copy(listing = settings.listing.copy(graphics = false)) else settings
            val validation = ListingValidator(provider.listingRules, provider.displayName, listingSettings)
                .validate(ListingReader.read(settings.metadataDir))
            problems += validation.problems
            return validation.listing
        }

        fun pushListing(edit: StoreEdit, listing: PreparedListing, dryRun: Boolean): List<Change> {
            val diff = ListingDiffer.diff(edit, listing, settings.listing.replaceScreenshots)
            listingDiff = diff
            return ListingDiffer.apply(edit, diff, dryRun)
        }

        /**
         * The status of a new release: the configured one, or derived from the rollout. Adds a problem
         * when status and rollout do not fit together. A staged rollout is never skipped with
         * `onUnsupported: warn`, because a full release in its place cannot be undone.
         */
        fun releaseStatus(): ReleaseStatus {
            val rollout = settings.rollout
            val status = settings.releaseStatus
                ?: if (rollout.isFull) ReleaseStatus.COMPLETED else ReleaseStatus.IN_PROGRESS
            val fits = when (status) {
                ReleaseStatus.IN_PROGRESS, ReleaseStatus.HALTED -> !rollout.isFull
                ReleaseStatus.COMPLETED, ReleaseStatus.DRAFT -> rollout.isFull
            }
            if (!fits) {
                val needed = if (rollout.isFull) "a rollout below 100%" else "a 100% rollout"
                problems += Problem.error("releaseStatus ${status.configName} needs $needed, got $rollout.", source)
            } else if (!rollout.isFull) {
                require(Capability.StagedRollout, "staged rollouts ($rollout)")
            }
            return status
        }
    }
}
