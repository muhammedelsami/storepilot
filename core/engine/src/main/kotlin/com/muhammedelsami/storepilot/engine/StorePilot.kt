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
import com.muhammedelsami.storepilot.engine.metadata.ReleaseNotesReader
import kotlin.io.path.isRegularFile

/**
 * The release operations shared by the Gradle plugin, the CLI, and the action.
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

    /** Uploads [artifact] and puts it on each target's track with its rollout and release notes. */
    fun publish(artifact: Artifact, targets: List<StoreSettings>, dryRun: Boolean = false): List<StoreResult> {
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
                calls = calls@{ edit ->
                    val track = settings.track
                    val before = edit.releases(track)
                    if (dryRun) {
                        val after = Release(emptyList(), status, settings.rollout, notes)
                        return@calls listOf(Change.ArtifactUpload(artifact, null), Change.ReleaseUpdate(track, before, after))
                    }
                    val versionCode = edit.upload(artifact)
                    val after = Release(listOf(versionCode), status, settings.rollout, notes)
                    edit.setRelease(track, after)
                    listOf(Change.ArtifactUpload(artifact, versionCode), Change.ReleaseUpdate(track, before, after))
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
        val edit = job.provider.openEdit(
            StoreContext(settings.packageName, settings.options, secrets(settings.store), logger),
        )
        val changes = discardOnFailure(edit) { job.calls(edit) }
        if (dryRun) {
            edit.discard()
        } else {
            discardOnFailure(edit) { edit.commit() }
        }
        return StoreResult(settings.store, settings.packageName, !dryRun, changes, job.problems)
    }

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
