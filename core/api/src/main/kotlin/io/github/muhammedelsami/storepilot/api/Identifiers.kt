package io.github.muhammedelsami.storepilot.api

import java.math.BigDecimal
import java.util.Locale

/** A store ID as written in `storepilot.yml`, CLI flags, and action inputs, for example `google-play`. */
@JvmInline
value class StoreId(val value: String) {
    init {
        require(PATTERN.matches(value)) {
            "Store ID must be lowercase letters and digits separated by '-', got '$value'"
        }
    }

    /** Prefix of this store's environment variables, for example `STOREPILOT_GOOGLE_PLAY_`. */
    val envPrefix: String
        get() = "STOREPILOT_" + value.uppercase(Locale.ROOT).replace('-', '_') + "_"

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}

/** A BCP-47 language tag in canonical form, for example `en-US`. Adapters convert it to store codes. */
@JvmInline
value class LocaleTag(val value: String) {
    init {
        val canonical = Locale.forLanguageTag(value).toLanguageTag()
        require(canonical != "und") { "'$value' is not a BCP-47 language tag, for example 'en-US'" }
        require(canonical == value) { "Language tag '$value' is not in canonical form, use '$canonical'" }
    }

    override fun toString(): String = value
}

/**
 * Where a release goes. [INTERNAL], [TESTING], and [PRODUCTION] are portable: adapters map them to
 * the store's own track names. Any other name is passed to the store as-is.
 */
@JvmInline
value class Track(val name: String) {
    init {
        require(name.isNotBlank() && name.trim() == name) { "Track name must not be blank or padded, got '$name'" }
    }

    override fun toString(): String = name

    companion object {
        val INTERNAL = Track("internal")
        val TESTING = Track("testing")
        val PRODUCTION = Track("production")
    }
}

/** The fraction of users that get a release: `0.0 < fraction <= 1.0`. [FULL] is a full release. */
@JvmInline
value class Rollout(val fraction: Double) {
    init {
        require(fraction > 0.0 && fraction <= 1.0) { "Rollout must be greater than 0.0 and at most 1.0, got $fraction" }
    }

    val isFull: Boolean
        get() = fraction == 1.0

    /** For example `10%` or `12.5%`. */
    override fun toString(): String =
        BigDecimal.valueOf(fraction).movePointRight(2).stripTrailingZeros().toPlainString() + "%"

    companion object {
        val FULL = Rollout(1.0)
    }
}
