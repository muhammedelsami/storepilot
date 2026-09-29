package io.github.muhammedelsami.storepilot.api

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun `store IDs are lowercase kebab case`() {
        assertEquals("STOREPILOT_GOOGLE_PLAY_", StoreId("google-play").envPrefix)
        for (bad in listOf("", "Google-Play", "google_play", "-play", "play-", "google--play")) {
            assertFailsWith<IllegalArgumentException>(bad) { StoreId(bad) }
        }
    }

    @Test
    fun `locale tags must be canonical BCP-47`() {
        LocaleTag("en-US")
        LocaleTag("tr-TR")
        LocaleTag("zh-Hans-CN")
        val notCanonical = assertFailsWith<IllegalArgumentException> { LocaleTag("en-us") }
        assertTrue("use 'en-US'" in notCanonical.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { LocaleTag("en_US") }
        assertFailsWith<IllegalArgumentException> { LocaleTag("") }
    }

    @Test
    fun `rollout is a fraction above zero and at most one`() {
        assertEquals("10%", Rollout(0.1).toString())
        assertEquals("12.5%", Rollout(0.125).toString())
        assertEquals("29%", Rollout(0.29).toString())
        assertTrue(Rollout.FULL.isFull)
        for (bad in listOf(0.0, -0.1, 1.01, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { Rollout(bad) }
        }
    }

    @Test
    fun `artifact type comes from the file extension`() {
        assertEquals(ArtifactType.BUNDLE, Artifact.of(Path.of("app-release.aab")).type)
        assertEquals(ArtifactType.APK, Artifact.of(Path.of("app-release.APK")).type)
        assertFailsWith<IllegalArgumentException> { Artifact.of(Path.of("app-release.zip")) }
    }

    @Test
    fun `release status and rollout must fit together`() {
        Release(listOf(1), ReleaseStatus.IN_PROGRESS, Rollout(0.1))
        Release(listOf(1), ReleaseStatus.COMPLETED)
        assertFailsWith<IllegalArgumentException> { Release(listOf(1), ReleaseStatus.IN_PROGRESS) }
        assertFailsWith<IllegalArgumentException> { Release(listOf(1), ReleaseStatus.HALTED) }
        assertFailsWith<IllegalArgumentException> { Release(listOf(1), ReleaseStatus.COMPLETED, Rollout(0.5)) }
        assertFailsWith<IllegalArgumentException> { Release(listOf(1), ReleaseStatus.DRAFT, Rollout(0.5)) }
    }

    @Test
    fun `secrets are hidden in strings`() {
        val secret = Secret("token")
        assertEquals("****", secret.toString())
        assertEquals("token", secret.reveal())
    }
}
