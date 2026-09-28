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
    )

private class RootCommand : CliktCommand(name = "storepilot") {
    init {
        versionOption(VERSION, names = setOf("--version"), message = { "storepilot $it" })
    }

    override fun help(context: Context) = "Publish Android releases and store listings from files in your repository."

    override fun run() = Unit
}

private val VERSION = RootCommand::class.java.`package`?.implementationVersion ?: "dev"

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
    protected val dryRun by option("--dry-run", help = "Read from the store and show the changes, but commit nothing.")
        .flag()

    protected abstract fun execute(storePilot: StorePilot, settings: StoreSettings): List<StoreResult>

    protected open fun overrides(): Overrides =
        Overrides(packageName = packageName, metadataDir = metadataDir?.let(::resolve))

    /** Resolves a path from a flag against the working directory. */
    protected fun resolve(path: Path): Path = environment.workingDir.resolve(path)

    final override fun run() {
        val exitCode = try {
            val results = execute(storePilot(), settings())
            echo(if (output == "json") JsonReport.render(results, dryRun) else TextReport.render(results).trimEnd())
            0
        } catch (e: ValidationException) {
            e.problems.forEach { echo(it.toString(), err = true) }
            1
        } catch (e: StoreException) {
            echo("error: ${e.message}", err = true)
            2
        }
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    private fun settings(): StoreSettings {
        val file = config?.let(::resolve)
            ?: environment.workingDir.resolve(ConfigParser.FILE_NAME).takeIf { it.isRegularFile() }
        if (file != null && !file.isRegularFile()) {
            throw ValidationException(Problem.error("The config file does not exist.", file.toString()))
        }
        val parsed = file?.let { ConfigParser.load(it) } ?: StorePilotConfig()
        val configDir = file?.parent ?: environment.workingDir
        return SettingsResolver(parsed, configDir, environment.env, overrides(), environment.workingDir).resolve(store)
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

    override fun help(context: Context) = "Upload an .aab or .apk and release it on a track."

    override fun overrides() = super.overrides().copy(track = track, rollout = rollout)

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        storePilot.publish(artifact.copy(path = resolve(artifact.path)), listOf(settings), dryRun)
}

private class PromoteCommand(environment: CliEnvironment) : StoreCommand("promote", environment) {
    private val from by option("--from", help = "Track that has the release.").convert { parse { Track(it) } }.required()
    private val to by option("--to", help = "Track that gets the release.").convert { parse { Track(it) } }.required()
    private val rollout by option("--rollout", help = "Fraction of users, above 0.0 and at most 1.0.")
        .convert { parse { parseRollout(it) } }
        .default(Rollout.FULL, defaultForHelp = "1.0")

    override fun help(context: Context) = "Put the newest release of one track on another track."

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        storePilot.promote(from, to, rollout, listOf(settings), dryRun)
}

private class StatusCommand(
    name: String,
    private val description: String,
    environment: CliEnvironment,
    private val operation: StorePilot.(Track, List<StoreSettings>, Boolean) -> List<StoreResult>,
) : StoreCommand(name, environment) {
    private val track by option("--track", help = "Track with the staged rollout.").convert { parse { Track(it) } }.required()

    override fun help(context: Context) = description

    override fun execute(storePilot: StorePilot, settings: StoreSettings) =
        storePilot.operation(track, listOf(settings), dryRun)
}

/** Turns the model's argument checks into option errors. */
private inline fun <T> OptionCallTransformContext.parse(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        fail(e.message.orEmpty())
    }
