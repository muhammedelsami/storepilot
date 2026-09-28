package com.muhammedelsami.storepilot.engine.config

import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track

/** The contents of `storepilot.yml`. Every field is optional; [SettingsResolver] fills the gaps. */
data class StorePilotConfig(
    /** Relative to the directory of the config file. */
    val metadataDir: String? = null,
    val track: Track? = null,
    val rollout: Rollout? = null,
    val onUnsupported: OnUnsupported? = null,
    val stores: Map<StoreId, StoreConfig> = emptyMap(),
)

/** One entry under `stores:`. */
data class StoreConfig(
    val packageName: String? = null,
    val track: Track? = null,
    val rollout: Rollout? = null,
    val releaseStatus: ReleaseStatus? = null,
    /** Keys that only the store adapter knows, with their values as written. */
    val options: Map<String, String> = emptyMap(),
)

/** What to do when a store cannot do what the config asks (docs/design.md §3.4). */
enum class OnUnsupported {
    /** Stop at validation. */
    FAIL,

    /** Skip the option for that store and report a warning. */
    WARN,
}

/** The spelling of an enum value in `storepilot.yml` and environment variables, for example `inProgress`. */
val Enum<*>.configName: String
    get() = name.lowercase().split('_').mapIndexed { index, word ->
        if (index == 0) word else word.replaceFirstChar { it.uppercaseChar() }
    }.joinToString("")

internal inline fun <reified E : Enum<E>> parseConfigEnum(value: String): E =
    enumValues<E>().firstOrNull { it.configName == value }
        ?: throw IllegalArgumentException(
            "expected one of ${enumValues<E>().joinToString { it.configName }}, got '$value'",
        )

/** Parses a rollout as written in the config, environment variables, and CLI flags, for example `0.1`. */
fun parseRollout(value: String): Rollout {
    val fraction = value.toDoubleOrNull()
        ?: throw IllegalArgumentException("expected a number greater than 0.0 and at most 1.0, got '$value'")
    return Rollout(fraction)
}
