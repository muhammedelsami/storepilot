package io.github.muhammedelsami.storepilot.engine.config

import io.github.muhammedelsami.storepilot.api.Secret
import io.github.muhammedelsami.storepilot.api.Secrets
import io.github.muhammedelsami.storepilot.api.StoreId

/**
 * Store credentials from `STOREPILOT_<STORE_ID>_<FIELD>` environment variables (docs/design.md §8),
 * for example `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. Empty values count as not set.
 */
class EnvironmentSecrets(private val env: Map<String, String>, private val store: StoreId) : Secrets {
    override fun get(field: String): Secret? = env[variableName(field)]?.takeIf { it.isNotBlank() }?.let(::Secret)

    fun variableName(field: String): String = store.envPrefix + field.uppercase().replace('-', '_')
}
