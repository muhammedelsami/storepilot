package io.github.muhammedelsami.storepilot.gradle

import io.github.muhammedelsami.storepilot.api.Logger
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.Rollout
import io.github.muhammedelsami.storepilot.api.Secret
import io.github.muhammedelsami.storepilot.api.Secrets
import io.github.muhammedelsami.storepilot.api.StoreException
import io.github.muhammedelsami.storepilot.api.StoreId
import io.github.muhammedelsami.storepilot.api.Track
import io.github.muhammedelsami.storepilot.api.ValidationException
import io.github.muhammedelsami.storepilot.engine.StorePilot
import io.github.muhammedelsami.storepilot.engine.StoreRegistry
import io.github.muhammedelsami.storepilot.engine.config.AsoConfig
import io.github.muhammedelsami.storepilot.engine.config.EnvironmentSecrets
import io.github.muhammedelsami.storepilot.engine.config.ListingConfig
import io.github.muhammedelsami.storepilot.engine.config.OnUnsupported
import io.github.muhammedelsami.storepilot.engine.config.Overrides
import io.github.muhammedelsami.storepilot.engine.config.SettingsResolver
import io.github.muhammedelsami.storepilot.engine.config.StoreConfig
import io.github.muhammedelsami.storepilot.engine.config.StorePilotConfig
import io.github.muhammedelsami.storepilot.engine.config.StoreSettings
import io.github.muhammedelsami.storepilot.engine.listing.LintRule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.work.DisableCachingByDefault

/**
 * Settings every StorePilot task shares. The plugin sets them from the `storepilot { }` block; the
 * tasks turn them into the engine's settings with the same rules as the CLI.
 */
@DisableCachingByDefault(because = "Most StorePilot tasks talk to a store")
abstract class StorePilotTask : DefaultTask() {
    /** Store ID. Default: `google-play`. */
    @get:Input
    abstract val store: Property<String>

    @get:Internal
    abstract val metadataDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val packageName: Property<String>

    /** Android application IDs of the selected variants, used when [packageName] is not set. */
    @get:Input
    abstract val applicationIds: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val track: Property<String>

    @get:Input
    @get:Optional
    abstract val rollout: Property<Double>

    @get:Input
    @get:Optional
    abstract val releaseStatus: Property<ReleaseStatus>

    @get:Input
    abstract val onUnsupported: Property<OnUnsupported>

    /** Store-specific options, such as `inAppUpdatePriority` for Google Play. */
    @get:Input
    abstract val storeOptions: MapProperty<String, String>

    @get:Input
    abstract val fallbackToDefaultLanguage: Property<Boolean>

    @get:Input
    abstract val listingGraphics: Property<Boolean>

    @get:Input
    abstract val replaceScreenshots: Property<Boolean>

    @get:Input
    abstract val disabledLintRules: SetProperty<String>

    @get:Input
    abstract val warningsAsErrors: Property<Boolean>

    @get:Internal
    abstract val serviceAccountJson: Property<String>

    @get:Internal
    abstract val serviceAccountFile: RegularFileProperty

    /** The `storepilot.dryRun` Gradle property: `true` reads from the store but commits nothing. */
    @get:Internal
    abstract val dryRunProperty: Property<String>

    @get:Internal
    protected val dryRun: Boolean
        get() = dryRunProperty.orNull.toBoolean()

    init {
        group = "StorePilot"
    }

    /**
     * The engine settings, built with the CLI's rules. [artifactPackageName] reads the package name
     * from the artifact when nothing else sets it.
     */
    protected fun storeSettings(
        requirePackageName: Boolean = true,
        artifactPackageName: (() -> String)? = null,
    ): StoreSettings = engineCall {
        val storeId = StoreId(store.get())
        val unknownRules = disabledLintRules.get() - LintRule.entries.map { it.id }.toSet()
        if (unknownRules.isNotEmpty()) {
            throw GradleException(
                "Unknown lint rules in storepilot.aso: ${unknownRules.joinToString()}. " +
                    "Rules: ${LintRule.entries.joinToString { it.id }}.",
            )
        }
        val config = StorePilotConfig(
            onUnsupported = onUnsupported.get(),
            fallbackToDefaultLanguage = fallbackToDefaultLanguage.get(),
            listing = ListingConfig(listingGraphics.get(), replaceScreenshots.get()),
            aso = AsoConfig(disabledLintRules.get().toList(), warningsAsErrors.get()),
            stores = mapOf(
                storeId to StoreConfig(
                    track = track.orNull?.let(::Track),
                    rollout = rollout.orNull?.let(::Rollout),
                    releaseStatus = releaseStatus.orNull,
                    options = storeOptions.get(),
                ),
            ),
        )
        val dir = metadataDir.get().asFile.toPath()
        val overrides = Overrides(packageName = resolvePackageName(requirePackageName), metadataDir = dir)
        SettingsResolver(config, dir, emptyMap(), overrides, dir).resolve(storeId, requirePackageName, artifactPackageName)
    }

    protected fun storePilot(): StorePilot {
        val gradleLogger = logger
        val log = object : Logger {
            override fun info(message: String) = gradleLogger.lifecycle(message)

            override fun warn(message: String) = gradleLogger.warn("warning: $message")
        }
        return StorePilot(StoreRegistry.discover(StorePilotTask::class.java.classLoader), ::secrets, log)
    }

    /** Runs engine code and turns its failures into build failures with readable messages. */
    protected fun <T> engineCall(block: () -> T): T =
        try {
            block()
        } catch (e: ValidationException) {
            e.problems.filter { !it.isError }.forEach { logger.warn(it.toString()) }
            throw GradleException(e.problems.filter { it.isError }.joinToString("\n"), e)
        } catch (e: StoreException) {
            throw GradleException(e.message.orEmpty(), e)
        } catch (e: IllegalArgumentException) {
            throw GradleException("Invalid StorePilot setting: ${e.message}", e)
        }

    /** DSL credentials first, then the `STOREPILOT_<STORE_ID>_<FIELD>` environment variables. */
    private fun secrets(storeId: StoreId): Secrets {
        val environment = EnvironmentSecrets(System.getenv(), storeId)
        return Secrets { field ->
            val value = when (field) {
                "service-account-json" -> serviceAccountJson.orNull
                "service-account-file" -> serviceAccountFile.orNull?.asFile?.path
                else -> null
            }
            value?.let(::Secret) ?: environment[field]
        }
    }

    private fun resolvePackageName(required: Boolean): String? {
        packageName.orNull?.let { return it }
        val ids = applicationIds.get().distinct()
        if (ids.size > 1 && required) {
            throw GradleException(
                "The selected variants have different application IDs (${ids.joinToString()}). " +
                    "Set storepilot.packageName or storepilot.variants.",
            )
        }
        return ids.singleOrNull()
    }
}
