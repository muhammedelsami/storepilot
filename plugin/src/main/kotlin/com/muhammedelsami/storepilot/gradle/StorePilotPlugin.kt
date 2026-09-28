package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * The `com.muhammedelsami.storepilot` plugin (docs/design.md §5). Listing tasks are per project;
 * release tasks are per Android variant, or without a variant name when the Android application
 * plugin is not applied.
 */
class StorePilotPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("storepilot", StorePilotExtension::class.java)
        extension.metadataDir.convention(project.layout.projectDirectory.dir("store"))
        extension.artifactType.convention(ArtifactType.BUNDLE)
        extension.onUnsupported.convention(OnUnsupported.FAIL)
        extension.fallbackToDefaultLanguage.convention(false)
        extension.listing.graphics.convention(true)
        extension.listing.replaceScreenshots.convention(true)
        extension.aso.warningsAsErrors.convention(false)

        project.tasks.withType(StorePilotTask::class.java).configureEach { task -> configureDefaults(project, task, extension) }
        project.tasks.withType(PublishTask::class.java).configureEach { it.withListing.convention(false) }
        project.tasks.withType(PullListingTask::class.java).configureEach { it.overwrite.convention(false) }

        registerListingTasks(project)

        var android = false
        project.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN) {
            android = true
            AndroidSupport.register(project, extension, this)
        }
        project.afterEvaluate {
            if (!android) registerStandaloneReleaseTasks(project, extension)
        }
    }

    /**
     * Registers the release tasks of one Android variant. [configure] points the tasks at the
     * variant's artifacts and application ID.
     */
    internal fun registerVariantReleaseTasks(project: Project, variantName: String, configure: (PublishArtifacts) -> Unit) {
        val name = variantName.replaceFirstChar { it.uppercaseChar() }
        val tasks = project.tasks
        val bundle = tasks.register("publish${name}Bundle", PublishTask::class.java) {
            it.description = "Uploads the $variantName bundle and releases it on the configured track."
        }
        val apk = tasks.register("publish${name}Apk", PublishTask::class.java) {
            it.description = "Uploads the $variantName APK and releases it on the configured track."
        }
        val aggregate = tasks.register("publish$name", PublishTask::class.java) {
            it.description = "Uploads the $variantName artifact and pushes the listing in one edit."
            it.withListing.set(true)
        }
        registerRolloutTasks(project, name)
        configure(PublishArtifacts(bundle, apk, aggregate))
    }

    /**
     * Without the Android plugin, `storepilot.artifact` is the file to upload. "publish" alone would
     * clash with the maven-publish plugin, so the aggregate task is `publishStore`.
     */
    private fun registerStandaloneReleaseTasks(project: Project, extension: StorePilotExtension) {
        val tasks = project.tasks
        tasks.register("publishArtifact", PublishTask::class.java) {
            it.description = "Uploads storepilot.artifact and releases it on the configured track."
            it.artifact.set(extension.artifact)
        }
        tasks.register("publishStore", PublishTask::class.java) {
            it.description = "Uploads storepilot.artifact and pushes the listing in one edit."
            it.artifact.set(extension.artifact)
            it.withListing.set(true)
        }
        registerRolloutTasks(project, name = "")
    }

    private fun registerRolloutTasks(project: Project, name: String) {
        val tasks = project.tasks
        tasks.register("promote${name}Release", PromoteTask::class.java) {
            it.description = "Moves the newest release of a track to another track: --from=<track> --to=<track> [--rollout=<fraction>]."
        }
        tasks.register("halt${name}Release", RolloutStatusTask::class.java) {
            it.description = "Stops the staged rollout on the configured track, or --track=<track>."
            it.halt.set(true)
        }
        tasks.register("resume${name}Release", RolloutStatusTask::class.java) {
            it.description = "Continues the halted staged rollout on the configured track, or --track=<track>."
            it.halt.set(false)
        }
    }

    /** The publish tasks of one variant. */
    internal class PublishArtifacts(
        val bundle: TaskProvider<PublishTask>,
        val apk: TaskProvider<PublishTask>,
        val aggregate: TaskProvider<PublishTask>,
    )

    private fun registerListingTasks(project: Project) {
        val tasks = project.tasks
        tasks.register("publishListing", PublishListingTask::class.java) {
            it.description = "Pushes details, listing text, and graphics from the metadata directory."
            it.textOnly.set(false)
        }
        tasks.register("publishListingText", PublishListingTask::class.java) {
            it.description = "Pushes details and listing text, no graphics."
            it.textOnly.set(true)
        }
        tasks.register("pullListing", PullListingTask::class.java) {
            it.description = "Writes the store listing into the metadata directory. Existing files need --overwrite."
        }
        tasks.register("diffListing", DiffListingTask::class.java) {
            it.description = "Compares the metadata directory with the store listing. Only reads."
        }
        val validate = tasks.register("validateListing", ValidateListingTask::class.java) { task ->
            task.description = "Checks the metadata directory against the store's rules. No network."
            task.listingFiles.from(task.metadataDir.file("details.yml"), task.metadataDir.dir("listing"))
            task.reportFile.set(project.layout.buildDirectory.file("reports/storepilot/validateListing.txt"))
        }
        tasks.register("exportStorepilotConfig", ExportConfigTask::class.java) { task ->
            task.description = "Writes the storepilot { } settings as storepilot.yml for the CLI and the GitHub Action."
            task.outputFile.set(project.layout.projectDirectory.file("storepilot.yml"))
        }
        project.pluginManager.withPlugin("lifecycle-base") {
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { it.dependsOn(validate) }
        }
    }

    private fun configureDefaults(project: Project, task: StorePilotTask, extension: StorePilotExtension) {
        val googlePlay = extension.googlePlay
        task.store.convention("google-play")
        task.metadataDir.convention(extension.metadataDir)
        task.packageName.convention(extension.packageName)
        task.track.convention(googlePlay.track.orElse(extension.track))
        task.rollout.convention(googlePlay.rollout.orElse(extension.rollout))
        task.releaseStatus.convention(googlePlay.releaseStatus)
        task.onUnsupported.convention(extension.onUnsupported)
        task.storeOptions.convention(
            project.provider {
                buildMap {
                    googlePlay.inAppUpdatePriority.orNull?.let { put("inAppUpdatePriority", it.toString()) }
                    googlePlay.changesNotSentForReview.orNull?.let { put("changesNotSentForReview", it.toString()) }
                }
            },
        )
        task.fallbackToDefaultLanguage.convention(extension.fallbackToDefaultLanguage)
        task.listingGraphics.convention(extension.listing.graphics)
        task.replaceScreenshots.convention(extension.listing.replaceScreenshots)
        task.disabledLintRules.convention(extension.aso.disabledRules)
        task.warningsAsErrors.convention(extension.aso.warningsAsErrors)
        task.serviceAccountJson.convention(googlePlay.serviceAccountJson)
        task.serviceAccountFile.convention(googlePlay.serviceAccountFile)
        task.dryRunProperty.convention(project.providers.gradleProperty(DRY_RUN_PROPERTY))
    }

    companion object {
        const val DRY_RUN_PROPERTY = "storepilot.dryRun"
        internal const val ANDROID_APPLICATION_PLUGIN = "com.android.application"
    }
}
