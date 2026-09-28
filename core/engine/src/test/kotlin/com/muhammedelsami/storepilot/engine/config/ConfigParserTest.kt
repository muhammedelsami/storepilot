package com.muhammedelsami.storepilot.engine.config

import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigParserTest {
    @Test
    fun `reads the example from the design`() {
        val config = ConfigParser.parse(
            """
            version: 1
            metadataDir: store
            track: testing
            rollout: 0.2

            stores:
              google-play:
                packageName: com.example.app
                track: production
                rollout: 0.1
                releaseStatus: inProgress
                inAppUpdatePriority: 3
                changesNotSentForReview: false
            """.trimIndent(),
        )

        assertEquals(
            StorePilotConfig(
                metadataDir = "store",
                track = Track.TESTING,
                rollout = Rollout(0.2),
                stores = mapOf(
                    StoreId("google-play") to StoreConfig(
                        packageName = "com.example.app",
                        track = Track.PRODUCTION,
                        rollout = Rollout(0.1),
                        releaseStatus = ReleaseStatus.IN_PROGRESS,
                        options = mapOf("inAppUpdatePriority" to "3", "changesNotSentForReview" to "false"),
                    ),
                ),
            ),
            config,
        )
    }

    @Test
    fun `reads the listing and aso sections`() {
        val config = ConfigParser.parse(
            """
            version: 1
            fallbackToDefaultLanguage: true
            listing:
              graphics: false
              replaceScreenshots: false
            aso:
              disable: [title-in-short-description, empty-locale]
              warningsAsErrors: true
            """.trimIndent(),
        )

        assertEquals(true, config.fallbackToDefaultLanguage)
        assertEquals(ListingConfig(graphics = false, replaceScreenshots = false), config.listing)
        assertEquals(AsoConfig(listOf("title-in-short-description", "empty-locale"), warningsAsErrors = true), config.aso)
    }

    @Test
    fun `rejects unknown lint rules`() {
        assertProblem(
            "storepilot.yml:3:13: error: Unknown lint rule 'no-emoji'. Rules: title-in-short-description, " +
                "trailing-whitespace, empty-locale, mixed-orientation, image-alpha.",
            "version: 1\naso:\n  disable: [no-emoji]\n",
        )
    }

    @Test
    fun `version alone is enough`() {
        assertEquals(StorePilotConfig(), ConfigParser.parse("version: 1"))
    }

    @Test
    fun `reports every problem with its line and column`() {
        val e = assertFailsWith<ValidationException> {
            ConfigParser.parse(
                """
                version: 1
                rollout: 1.5
                onUnsupported: ignore
                trak: production
                stores:
                  Google-Play: {}
                  google-play:
                    rollout: half
                    releaseStatus: live
                    nested:
                      key: value
                """.trimIndent(),
            )
        }

        assertEquals(
            listOf(
                "storepilot.yml:2:10: error: Invalid 'rollout': Rollout must be greater than 0.0 and at most 1.0, got 1.5",
                "storepilot.yml:3:16: error: Invalid 'onUnsupported': expected one of fail, warn, got 'ignore'",
                "storepilot.yml:4:1: error: Unknown key 'trak'.",
                "storepilot.yml:6:3: error: Invalid 'stores': Store ID must be lowercase letters and digits separated by '-', got 'Google-Play'",
                "storepilot.yml:8:14: error: Invalid 'stores.google-play.rollout': expected a number greater than 0.0 and at most 1.0, got 'half'",
                "storepilot.yml:9:20: error: Invalid 'stores.google-play.releaseStatus': expected one of draft, inProgress, halted, completed, got 'live'",
                "storepilot.yml:11:7: error: 'stores.google-play.nested' must be a single value.",
            ),
            e.problems.map { it.toString() },
        )
    }

    @Test
    fun `version is required and must be 1`() {
        assertProblem("storepilot.yml:1:1: error: Missing 'version: 1'.", "track: internal")
        assertProblem("storepilot.yml:1:10: error: Unsupported version '2'. This StorePilot reads version 1.", "version: 2")
        assertProblem("storepilot.yml: error: The file is empty. It needs at least 'version: 1'.", "")
    }

    @Test
    fun `values must not be empty and keys must not repeat`() {
        assertProblem("storepilot.yml:2:7: error: 'track' has no value.", "version: 1\ntrack:\n")
        assertProblem("storepilot.yml:3:1: error: Duplicate key 'track'.", "version: 1\ntrack: a\ntrack: b\n")
    }

    @Test
    fun `invalid YAML is reported`() {
        val e = assertFailsWith<ValidationException> { ConfigParser.parse("version: [1", "config.yml") }
        assertEquals("config.yml", e.problems.single().source)
    }

    private fun assertProblem(expected: String, yaml: String) {
        val e = assertFailsWith<ValidationException> { ConfigParser.parse(yaml) }
        assertEquals(listOf(expected), e.problems.map { it.toString() })
    }
}
