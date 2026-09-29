package io.github.muhammedelsami.storepilot.engine.config

import io.github.muhammedelsami.storepilot.api.StoreId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentSecretsTest {
    @Test
    fun `reads store secrets from prefixed variables`() {
        val secrets = EnvironmentSecrets(
            mapOf("STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to "{}", "STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to ""),
            StoreId("google-play"),
        )

        assertEquals("{}", secrets["service-account-json"]?.reveal())
        assertNull(secrets["service-account-file"])
        assertNull(secrets["token"])
    }
}
