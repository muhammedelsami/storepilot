package com.muhammedelsami.storepilot.engine.config

import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.ValidationException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsResolverTest {
    private val store = StoreId("google-play")
    private val configDir = Path.of("/repo/app")
    private val workingDir = Path.of("/work")

    @Test
    fun `defaults apply when nothing is set`() {
        val settings = resolve(StorePilotConfig(stores = mapOf(store to StoreConfig(packageName = "com.example.app"))))

        assertEquals(
            StoreSettings(
                store = store,
                packageName = "com.example.app",
                metadataDir = Path.of("/repo/app/store"),
                track = Track.INTERNAL,
                rollout = Rollout.FULL,
                releaseStatus = null,
                onUnsupported = OnUnsupported.FAIL,
                options = emptyMap(),
            ),
            settings,
        )
    }

    @Test
    fun `store values in the file win over top-level values`() {
        val config = StorePilotConfig(
            track = Track.TESTING,
            rollout = Rollout(0.5),
            stores = mapOf(store to StoreConfig(packageName = "com.example.app", track = Track.PRODUCTION)),
        )

        val settings = resolve(config)

        assertEquals(Track.PRODUCTION, settings.track)
        assertEquals(Rollout(0.5), settings.rollout)
    }

    @Test
    fun `environment wins over the file and overrides win over the environment`() {
        val config = StorePilotConfig(
            metadataDir = "../metadata",
            track = Track.TESTING,
            onUnsupported = OnUnsupported.FAIL,
            stores = mapOf(store to StoreConfig(packageName = "com.example.file", track = Track.PRODUCTION, rollout = Rollout(0.1))),
        )
        val env = mapOf(
            "STOREPILOT_TRACK" to "beta",
            "STOREPILOT_ROLLOUT" to "0.3",
            "STOREPILOT_GOOGLE_PLAY_ROLLOUT" to "0.2",
            "STOREPILOT_GOOGLE_PLAY_PACKAGE_NAME" to "com.example.env",
            "STOREPILOT_GOOGLE_PLAY_RELEASE_STATUS" to "halted",
            "STOREPILOT_ON_UNSUPPORTED" to "warn",
            "STOREPILOT_METADATA_DIR" to "listing",
        )

        val fromEnv = resolve(config, env)
        assertEquals("com.example.env", fromEnv.packageName)
        assertEquals(Track("beta"), fromEnv.track)
        assertEquals(Rollout(0.2), fromEnv.rollout)
        assertEquals(ReleaseStatus.HALTED, fromEnv.releaseStatus)
        assertEquals(OnUnsupported.WARN, fromEnv.onUnsupported)
        assertEquals(Path.of("/work/listing"), fromEnv.metadataDir)

        val overrides = Overrides(packageName = "com.example.flag", metadataDir = Path.of("/flag"), track = Track.INTERNAL, rollout = Rollout(0.05))
        val fromFlags = resolve(config, env, overrides)
        assertEquals("com.example.flag", fromFlags.packageName)
        assertEquals(Track.INTERNAL, fromFlags.track)
        assertEquals(Rollout(0.05), fromFlags.rollout)
        assertEquals(Path.of("/flag"), fromFlags.metadataDir)
    }

    @Test
    fun `metadata dir in the file is relative to the config file`() {
        val config = StorePilotConfig(metadataDir = "../metadata", stores = mapOf(store to StoreConfig(packageName = "com.example.app")))
        assertEquals(Path.of("/repo/metadata"), resolve(config).metadataDir)
    }

    @Test
    fun `empty environment values count as not set`() {
        val config = StorePilotConfig(track = Track.TESTING, stores = mapOf(store to StoreConfig(packageName = "com.example.app")))
        assertEquals(Track.TESTING, resolve(config, mapOf("STOREPILOT_TRACK" to "", "STOREPILOT_ROLLOUT" to " ")).track)
    }

    @Test
    fun `reports a missing package name and invalid environment values together`() {
        val e = assertFailsWith<ValidationException> {
            resolve(StorePilotConfig(), mapOf("STOREPILOT_ROLLOUT" to "2", "STOREPILOT_ON_UNSUPPORTED" to "ignore"))
        }
        assertEquals(
            listOf(
                "STOREPILOT_ROLLOUT: error: Invalid value: Rollout must be greater than 0.0 and at most 1.0, got 2.0",
                "STOREPILOT_ON_UNSUPPORTED: error: Invalid value: expected one of fail, warn, got 'ignore'",
                "error: No package name for store 'google-play'. Set 'stores.google-play.packageName' in storepilot.yml " +
                    "or STOREPILOT_GOOGLE_PLAY_PACKAGE_NAME.",
            ),
            e.problems.map { it.toString() },
        )
    }

    @Test
    fun `rejects an invalid package name`() {
        val e = assertFailsWith<ValidationException> {
            resolve(StorePilotConfig(stores = mapOf(store to StoreConfig(packageName = "example"))))
        }
        assertEquals("'example' is not a valid Android package name.", e.problems.single().message)
    }

    private fun resolve(
        config: StorePilotConfig,
        env: Map<String, String> = emptyMap(),
        overrides: Overrides = Overrides(),
    ): StoreSettings = SettingsResolver(config, configDir, env, overrides, workingDir).resolve(store)
}
