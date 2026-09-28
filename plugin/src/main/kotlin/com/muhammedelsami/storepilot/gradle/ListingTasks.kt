package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.engine.report.TextReport
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** `publishListing` and `publishListingText`. */
@DisableCachingByDefault(because = "Changes a store")
abstract class PublishListingTask : StoreChangeTask() {
    @get:Input
    abstract val textOnly: Property<Boolean>

    @TaskAction
    fun push() {
        val settings = storeSettings()
        report(engineCall { storePilot().pushListing(listOf(settings), textOnly.get(), dryRun) })
    }
}

/** `pullListing [--overwrite]`: writes the store listing into the metadata directory. */
@DisableCachingByDefault(because = "Reads a store")
abstract class PullListingTask : StorePilotTask() {
    @get:Input
    @get:Option(option = "overwrite", description = "Replace existing files.")
    abstract val overwrite: Property<Boolean>

    init {
        doNotTrackState("Reads a store and writes into the source directory")
    }

    @TaskAction
    fun pull() {
        val settings = storeSettings()
        val result = engineCall { storePilot().pullListing(settings, overwrite.get()) }
        logger.lifecycle(TextReport.renderPull(result).trimEnd())
    }
}

/** `diffListing`: compares the metadata directory with the store listing. Only reads. */
@DisableCachingByDefault(because = "Reads a store")
abstract class DiffListingTask : StorePilotTask() {
    init {
        doNotTrackState("Reads a store")
    }

    @TaskAction
    fun diff() {
        val settings = storeSettings()
        val results = engineCall { storePilot().diffListing(listOf(settings)) }
        logger.lifecycle(TextReport.renderDiff(results).trimEnd())
    }
}

/** `validateListing`: checks the metadata directory against the store's rules. No network; part of `check`. */
@CacheableTask
abstract class ValidateListingTask : StorePilotTask() {
    /** `details.yml` and `listing/` in the metadata directory. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val listingFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun validate() {
        val settings = storeSettings(requirePackageName = false)
        val checks = engineCall { storePilot().validateListing(listOf(settings)) }
        val report = TextReport.renderValidation(checks)
        reportFile.get().asFile.toPath().also { it.parent.createDirectories() }.writeText(report)
        val problems = checks.flatMap { it.validation.problems }
        problems.filter { !it.isError }.forEach { logger.warn(it.toString()) }
        val errors = problems.filter { it.isError }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "The listing has ${errors.size} error(s):\n" + errors.joinToString("\n") +
                    "\nFull report: ${reportFile.get().asFile}",
            )
        }
    }
}
