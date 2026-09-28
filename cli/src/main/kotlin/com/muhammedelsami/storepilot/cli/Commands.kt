package com.muhammedelsami.storepilot.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.OptionCallTransformContext
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.Logger
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreException
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.engine.StorePilot
import com.muhammedelsami.storepilot.engine.StoreRegistry
import com.muhammedelsami.storepilot.engine.StoreResult
import com.muhammedelsami.storepilot.engine.config.ConfigParser
import com.muhammedelsami.storepilot.engine.config.EnvironmentSecrets
import com.muhammedelsami.storepilot.engine.config.Overrides
import com.muhammedelsami.storepilot.engine.config.SettingsResolver
import com.muhammedelsami.storepilot.engine.config.StorePilotConfig
import com.muhammedelsami.storepilot.engine.config.StoreSettings
import com.muhammedelsami.storepilot.engine.config.parseRollout
import com.muhammedelsami.storepilot.engine.report.TextReport
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** What the CLI reads from its surroundings. Tests replace it. */
class CliEnvironment(
    val env: Map<String, String>,
    val workingDir: Path,
    val registry: () -> StoreRegistry,
) {
    companion object {
        fun system() = CliEnvironment(System.getenv(), Path.of("").toAbsolutePath(), { StoreRegistry.discover() })
    }
}

fun storePilotCli(environment: CliEnvironment): CliktCommand =
    RootCommand().subcommands(
        PublishCommand(environment),
        PromoteCommand(environment),
        StatusCommand("halt", "Stop the staged rollout on a track.", environment, StorePilot::halt),
        StatusCommand("resume", "Continue the halted staged rollout on a track.", environment, StorePilot::resume),
        ListingCommand().subcommands(
            ListingPushCommand(environment),
            ListingPullCommand(environment),
            ListingDiffCommand(environment),
            ListingValidateCommand(environment),
        ),
    )

private class RootCommand : CliktCommand(name = "storepilot") {
    init {
        versionOption(VERSION, names = setOf("--version"), message = { "storepilot $it" })
    }

    override fun help(context: Context) = "Publish Android releases and store listings from files in your repository."

    override fun run() = Unit
}

private class ListingCommand : CliktCommand(name = "listing") {
    override fun help(context: Context) = "Manage the store listing in the metadata directory."

    override fun run() = Unit
}

private val VERSION = RootCommand::class.java.`package`?.implementationVersion ?: "dev"

/** What a command prints, and its exit code. */
private class Output(val text: String, val json: String, val exitCode: Int = 0)

/**
 * Options every store command has, and the mapping of outcomes to exit codes: 1 for config or
 * validation errors, 2 for store API errors (docs/design.md §6).
 */
private abstract class StoreCommand(name: String, private val environment: CliEnvironment) : CliktCommand(name) {
    private val store by option("--store", help = "Store ID. Default: google-play.")
        .convert { parse { StoreId(it) } }
        .default(StoreId("google-play"), defaultForHelp = "google-play")
    private val config by option("--config", help = "Config file. Default: storepilot.yml in the working directory, if present.")
        .path()
    private val packageName by option("--package", help = "Package name of the app.")
    private val metadataDir by option("--metadata-dir", help = "Metadata directory. Default: store, next to the config file.")
        .path()
    private val output by option("--output", help = "Output format. Default: text.").choice("text", "json").default("text")

    /** False for commands without store calls. */
    protected open val needsPackageName: Boolean = true

    protected abstract fun execute(storePilot: StorePilot, settings: StoreSettings): Output

    protected open fun overrides(): Overrides =
        Overrides(packageName = packageName, metadataDir = metadataDir?.let(::resolve))

    /** Resolves a path from a flag against the working directory. */
    protected fun resolve(path: Path): Path = environment.workingDir.resolve(path)

    protected fun dryRunOption() =
        option("--dry-run", help = "Read from the store and show the changes, but commit nothing.").flag()

    protected fun results(results: List<StoreResult>, dryRun: Boolean) =
        Output(TextReport.render(results).trimEnd(), JsonReport.render(results, dryRun))

    final override fun run() {
        val exitCode = try {
            val output = execute(storePilot(), settings())
            echo(if (this.output == "json") output.json else shorten(output.text))
            output.exitCode
        } catch (e: ValidationException) {
            e.problems.forEach { echo(shorten(it.toString()), err = true) }
            1
        } catch (e: StoreException) {
            echo("error: ${e.message}", err = true)
            2
        }
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    /** Shows paths in text output relative to the working directory. JSON keeps them absolute. */
    private fun shorten(text: String): String = text.replace(environment.workingDir.toString() + File.separator, "")

    private fun settings(): StoreSettings {
        val file = config?.let(::resolve)
            ?: environment.workingDir.resolve(ConfigParser.FILE_NAME).takeIf { it.isRegularFile() }
        if (file != null && !file.isRegularFile()) {
            throw ValidationException(Problem.error("The config file does not exist.", file.toString()))
        }
        val parsed = file?.let { ConfigParser.load(it) } ?: StorePilotConfig()
        val configDir = file?.parent ?: environment.workingDir
        return SettingsResolver(parsed, configDir, environment.env, overrides(), environment.workingDir)
            .resolve(store, needsPackageName)
    }

    private fun storePilot(): StorePilot {
        val logger = object : Logger {
            override fun info(message: String) = echo(message, err = true)

            override fun warn(message: String) = echo("warning: $message", err = true)
        }
        return StorePilot(environment.registry(), secrets = { EnvironmentSecrets(environment.env, it) }, logger)
    }
}

private class PublishCommand(environment: CliEnvironment) : StoreCommand("publish", environment) {
    private val artifact by option("--artifact", help = "The .aab or .apk file to upload.")
        .convert { parse { Artifact.of(Path.of(it)) } }
        .required()
    private val track by option("--track", help = "Track. Default: from the config, else internal.")
        .convert { parse { Track(it) } }
    private val rollout by option("--rollout", help = "Fraction of users, above 0.0 and at most 1.0. Default: 1.0.")
        .convert { parse { parseRollout(it) } }
    private val withListing by option("--with-listing", help = "Also push the listing, in the same edit.").flag()
    private val dryRun by dryRunOption()

    override fun help(context: Context) = "Upload an .aab or .apk and release it on a track."

    override fun overrides() = super.overrides().copy(track = track, rollout = rollout)

    override fun execute(storePilot: StorePilot, settings: StoreSettings) = results(
        storePilot.publish(artifact.copy(path = resolve(artifact.path)), listOf(settings), dryRun, withListing),
        dryRun,
    )
}

private class PromoteCommand(environment: CliEnvironment) : StoreCommand("promote", environment) {
    private val from by option("--from", help = "Track that has the release.").convert { parse { Track(it) } }.required()
    private val to by option("--to", help = "Track that gets the release.").convert { parse { Track(it) } }.required()
    private val rollout by option("--rollout", help = "Fraction of users, above 0.0 and at most 1.0.")
        .convert { parse { parseRollout(it) } }
        .default(Rollout.FULL, defaultForHelp = "1.0")
    private val dryRun by dryRunOption()

    override fun help(context: Context) = "Put the newest release of one track on another track."

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        results(storePilot.promote(from, to, rollout, listOf(settings), dryRun), dryRun)
}

private class StatusCommand(
    name: String,
    private val description: String,
    environment: CliEnvironment,
    private val operation: StorePilot.(Track, List<StoreSettings>, Boolean) -> List<StoreResult>,
) : StoreCommand(name, environment) {
    private val track by option("--track", help = "Track with the staged rollout.").convert { parse { Track(it) } }.required()
    private val dryRun by dryRunOption()

    override fun help(context: Context) = description

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        results(storePilot.operation(track, listOf(settings), dryRun), dryRun)
}

private class ListingPushCommand(environment: CliEnvironment) : StoreCommand("push", environment) {
    private val textOnly by option("--text-only", help = "Push details and text, no graphics.").flag()
    private val dryRun by dryRunOption()

    override fun help(context: Context) = "Make the store listing equal to the metadata directory."

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        results(storePilot.pushListing(listOf(settings), textOnly, dryRun), dryRun)
}

private class ListingPullCommand(environment: CliEnvironment) : StoreCommand("pull", environment) {
    private val overwrite by option("--overwrite", help = "Replace existing files.").flag()

    override fun help(context: Context) = "Write the store listing into the metadata directory."

    override fun execute(storePilot: StorePilot, settings: StoreSettings): Output {
        val result = storePilot.pullListing(settings, overwrite)
        return Output(TextReport.renderPull(result).trimEnd(), JsonReport.renderPull(result))
    }
}

private class ListingDiffCommand(environment: CliEnvironment) : StoreCommand("diff", environment) {
    private val exitCode by option("--exit-code", help = "Exit with 3 when the store listing differs.").flag()

    override fun help(context: Context) = "Compare the metadata directory with the store listing. Only reads."

    override fun execute(storePilot: StorePilot, settings: StoreSettings): Output {
        val results = storePilot.diffListing(listOf(settings))
        val changed = results.any { it.listingDiff?.hasChanges == true }
        return Output(TextReport.renderDiff(results).trimEnd(), JsonReport.renderDiff(results), if (exitCode && changed) 3 else 0)
    }
}

private class ListingValidateCommand(environment: CliEnvironment) : StoreCommand("validate", environment) {
    override val needsPackageName = false

    override fun help(context: Context) = "Check the metadata directory against the store's rules. No network."

    override fun execute(storePilot: StorePilot, settings: StoreSettings): Output {
        val checks = storePilot.validateListing(listOf(settings))
        val failed = checks.any { it.validation.hasErrors }
        return Output(TextReport.renderValidation(checks).trimEnd(), JsonReport.renderValidation(checks), if (failed) 1 else 0)
    }
}

/** Turns the model's argument checks into option errors. */
private inline fun <T> OptionCallTransformContext.parse(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        fail(e.message.orEmpty())
    }
