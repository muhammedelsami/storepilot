package com.muhammedelsami.storepilot.engine.config

import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import java.nio.file.Path

/** Values from CLI flags or action inputs. They win over everything else. */
data class Overrides(
    val packageName: String? = null,
    val metadataDir: Path? = null,
    val track: Track? = null,
    val rollout: Rollout? = null,
)

/** The settings for one store after [SettingsResolver] applied the precedence rules. */
data class StoreSettings(
    val store: StoreId,
    val packageName: String,
    val metadataDir: Path,
    val track: Track,
    val rollout: Rollout,
    /** Null when not set anywhere: the engine derives it from [rollout]. */
    val releaseStatus: ReleaseStatus?,
    val onUnsupported: OnUnsupported,
    val options: Map<String, String>,
)

/**
 * Applies the precedence from docs/design.md §6, highest first: [Overrides], environment variables,
 * `storepilot.yml`, defaults. In the environment and in the file, a store-specific value wins over a
 * top-level one.
 *
 * Environment variables: `STOREPILOT_METADATA_DIR`, `STOREPILOT_TRACK`, `STOREPILOT_ROLLOUT`,
 * `STOREPILOT_ON_UNSUPPORTED`, and per store `STOREPILOT_<STORE_ID>_PACKAGE_NAME`, `..._TRACK`,
 * `..._ROLLOUT`, `..._RELEASE_STATUS`. Empty values count as not set.
 */
class SettingsResolver(
    private val config: StorePilotConfig,
    /** Directory that relative paths in [config] are resolved against: the config file's directory. */
    private val configDir: Path,
    private val env: Map<String, String> = emptyMap(),
    private val overrides: Overrides = Overrides(),
    /** Directory that relative paths in environment variables are resolved against. */
    private val workingDir: Path = Path.of("").toAbsolutePath(),
) {
    fun resolve(store: StoreId): StoreSettings {
        val problems = mutableListOf<Problem>()
        val storeConfig = config.stores[store] ?: StoreConfig()
        val prefix = store.envPrefix

        fun <T> fromEnv(name: String, parse: (String) -> T): T? {
            val value = env[name]?.takeIf { it.isNotBlank() } ?: return null
            return try {
                parse(value)
            } catch (e: IllegalArgumentException) {
                problems += Problem.error("Invalid value: ${e.message}", name)
                null
            }
        }

        val packageName = overrides.packageName
            ?: fromEnv("${prefix}PACKAGE_NAME") { it }
            ?: storeConfig.packageName
        val metadataDir = overrides.metadataDir
            ?: fromEnv("STOREPILOT_METADATA_DIR") { workingDir.resolve(it) }
            ?: configDir.resolve(config.metadataDir ?: DEFAULT_METADATA_DIR)
        val track = overrides.track
            ?: fromEnv("${prefix}TRACK", ::Track)
            ?: fromEnv("STOREPILOT_TRACK", ::Track)
            ?: storeConfig.track
            ?: config.track
            ?: Track.INTERNAL
        val rollout = overrides.rollout
            ?: fromEnv("${prefix}ROLLOUT", ::parseRollout)
            ?: fromEnv("STOREPILOT_ROLLOUT", ::parseRollout)
            ?: storeConfig.rollout
            ?: config.rollout
            ?: Rollout.FULL
        val releaseStatus = fromEnv("${prefix}RELEASE_STATUS") { parseConfigEnum<ReleaseStatus>(it) }
            ?: storeConfig.releaseStatus
        val onUnsupported = fromEnv("STOREPILOT_ON_UNSUPPORTED") { parseConfigEnum<OnUnsupported>(it) }
            ?: config.onUnsupported
            ?: OnUnsupported.FAIL

        if (packageName == null) {
            problems += Problem.error(
                "No package name for store '$store'. Set 'stores.$store.packageName' in ${ConfigParser.FILE_NAME} " +
                    "or ${prefix}PACKAGE_NAME.",
            )
        } else if (!PACKAGE_NAME.matches(packageName)) {
            problems += Problem.error("'$packageName' is not a valid Android package name.", "stores.$store.packageName")
        }
        if (problems.isNotEmpty()) throw ValidationException(problems)

        return StoreSettings(
            store = store,
            packageName = packageName!!,
            metadataDir = metadataDir.normalize(),
            track = track,
            rollout = rollout,
            releaseStatus = releaseStatus,
            onUnsupported = onUnsupported,
            options = storeConfig.options,
        )
    }

    private companion object {
        const val DEFAULT_METADATA_DIR = "store"
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
