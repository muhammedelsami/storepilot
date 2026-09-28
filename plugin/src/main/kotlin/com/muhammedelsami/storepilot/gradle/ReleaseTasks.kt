package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.StoreResult
import com.muhammedelsami.storepilot.engine.config.parseRollout
import com.muhammedelsami.storepilot.engine.report.TextReport
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

/** Base of the tasks that change a store. They are never up-to-date. */
@DisableCachingByDefault(because = "Changes a store")
abstract class StoreChangeTask : StorePilotTask() {
    init {
        doNotTrackState("Changes a store")
    }

    protected fun report(results: List<StoreResult>) {
        logger.lifecycle(TextReport.render(results).trimEnd())
    }
}

/** `publish<Variant>Bundle`, `publish<Variant>Apk`, and `publish<Variant>` (with the listing). */
@DisableCachingByDefault(because = "Changes a store")
abstract class PublishTask : StoreChangeTask() {
    /** The `.aab` or `.apk` file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val artifact: RegularFileProperty

    /** The Android plugin's APK output directory, used when [artifact] is not set. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val apkDirectory: DirectoryProperty

    /** Also push the listing, in the same edit. */
    @get:Input
    abstract val withListing: Property<Boolean>

    @TaskAction
    fun publish() {
        val settings = storeSettings()
        val artifact = engineCall { Artifact.of(artifactFile()) }
        report(engineCall { storePilot().publish(artifact, listOf(settings), dryRun, withListing.get()) })
    }

    private fun artifactFile(): Path {
        artifact.orNull?.let { return it.asFile.toPath() }
        val dir = apkDirectory.orNull?.asFile?.toPath()
            ?: throw GradleException("No artifact. Set storepilot.artifact, or apply the Android application plugin.")
        val apks = dir.listDirectoryEntries().filter { it.extension == "apk" }
        return apks.singleOrNull() ?: throw GradleException(
            "Expected one APK in $dir, found ${apks.size}. Publish an app bundle instead, or set storepilot.artifact.",
        )
    }
}

/** `promote<Variant>Release --from=testing --to=production --rollout=0.5`. */
@DisableCachingByDefault(because = "Changes a store")
abstract class PromoteTask : StoreChangeTask() {
    @get:Input
    @get:Optional
    @get:Option(option = "from", description = "Track that has the release.")
    abstract val fromTrack: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "to", description = "Track that gets the release.")
    abstract val toTrack: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "rollout", description = "Fraction of users, above 0.0 and at most 1.0. Default: 1.0.")
    abstract val promoteRollout: Property<String>

    @TaskAction
    fun promote() {
        val from = fromTrack.orNull ?: throw GradleException("Set the track to promote from: --from=<track>.")
        val to = toTrack.orNull ?: throw GradleException("Set the track to promote to: --to=<track>.")
        val settings = storeSettings()
        report(
            engineCall {
                val rollout = parseRollout(promoteRollout.getOrElse("1.0"))
                storePilot().promote(Track(from), Track(to), rollout, listOf(settings), dryRun)
            },
        )
    }
}

/** `halt<Variant>Release` and `resume<Variant>Release`. The track defaults to the configured one. */
@DisableCachingByDefault(because = "Changes a store")
abstract class RolloutStatusTask : StoreChangeTask() {
    @get:Input
    abstract val halt: Property<Boolean>

    @get:Input
    @get:Optional
    @get:Option(option = "track", description = "Track with the staged rollout. Default: the configured track.")
    abstract val rolloutTrack: Property<String>

    @TaskAction
    fun changeStatus() {
        val settings = storeSettings()
        report(
            engineCall {
                val track = rolloutTrack.orNull?.let(::Track) ?: settings.track
                val storePilot = storePilot()
                if (halt.get()) {
                    storePilot.halt(track, listOf(settings), dryRun)
                } else {
                    storePilot.resume(track, listOf(settings), dryRun)
                }
            },
        )
    }
}
