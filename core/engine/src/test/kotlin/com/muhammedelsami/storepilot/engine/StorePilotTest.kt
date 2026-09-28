package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.Capability
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.ReleaseStatus.COMPLETED
import com.muhammedelsami.storepilot.api.ReleaseStatus.HALTED
import com.muhammedelsami.storepilot.api.ReleaseStatus.IN_PROGRESS
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.Secret
import com.muhammedelsami.storepilot.api.Secrets
import com.muhammedelsami.storepilot.api.StoreException
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.Track.Companion.PRODUCTION
import com.muhammedelsami.storepilot.api.Track.Companion.TESTING
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.api.fake.FakeStoreProvider
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import com.muhammedelsami.storepilot.engine.config.StoreSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorePilotTest {
    @TempDir
    lateinit var dir: Path

    private val packageName = "com.example.app"
    private val en = LocaleTag("en-US")

    @Test
    fun `publish uploads the artifact and puts a full release on the track`() {
        val store = FakeStoreProvider()
        writeReleaseNotes("en-US.txt", "Bug fixes.")

        val result = storePilot(store).publish(bundle(), listOf(settings())).single()

        val release = Release(listOf(1), COMPLETED, releaseNotes = mapOf(en to "Bug fixes."))
        assertEquals(listOf(release), store.state.releases(packageName, PRODUCTION))
        assertEquals(1, store.state.commits)
        assertTrue(result.committed)
        assertEquals(
            listOf(Change.ArtifactUpload(bundle(), 1), Change.ReleaseUpdate(PRODUCTION, emptyList(), release)),
            result.changes,
        )
    }

    @Test
    fun `staged publish keeps the completed release`() {
        val store = FakeStoreProvider()
        store.state.nextVersionCode = 2
        store.state.tracks[packageName] = mutableMapOf(PRODUCTION to listOf(Release(listOf(1), COMPLETED)))

        storePilot(store).publish(bundle(), listOf(settings(rollout = Rollout(0.1))))

        assertEquals(
            listOf(Release(listOf(2), IN_PROGRESS, Rollout(0.1)), Release(listOf(1), COMPLETED)),
            store.state.releases(packageName, PRODUCTION),
        )
    }

    @Test
    fun `dry run reads the track and commits nothing`() {
        val store = FakeStoreProvider()
        val current = Release(listOf(1), COMPLETED)
        store.state.tracks[packageName] = mutableMapOf(PRODUCTION to listOf(current))

        val result = storePilot(store).publish(bundle(), listOf(settings(rollout = Rollout(0.5))), dryRun = true).single()

        assertFalse(result.committed)
        assertEquals(0, store.state.commits)
        assertEquals(1, store.state.discards)
        assertEquals(listOf(current), store.state.releases(packageName, PRODUCTION))
        assertEquals(
            listOf(
                Change.ArtifactUpload(bundle(), null),
                Change.ReleaseUpdate(PRODUCTION, listOf(current), Release(emptyList(), IN_PROGRESS, Rollout(0.5))),
            ),
            result.changes,
        )
    }

    @Test
    fun `validation reports every problem before any store call`() {
        val store = FakeStoreProvider(capabilities = setOf(Capability.Upload(ArtifactType.BUNDLE)))
        val targets = listOf(
            settings(rollout = Rollout(0.1), onUnsupported = OnUnsupported.WARN, options = mapOf("color" to "red")),
            settings(releaseStatus = HALTED),
        )

        val e = assertFailsWith<ValidationException> {
            storePilot(store).publish(Artifact(dir.resolve("missing.apk"), ArtifactType.APK), targets)
        }

        assertEquals(
            listOf(
                "${dir.resolve("missing.apk")}: error: The artifact file does not exist.",
                "stores.fake: error: Fake Store has no option 'color'.",
                "stores.fake: error: Fake Store does not support apk uploads.",
                "stores.fake: error: Fake Store does not support staged rollouts (10%).",
                "stores.fake: error: Fake Store does not support apk uploads.",
                "stores.fake: error: releaseStatus halted needs a rollout below 100%, got 100%.",
            ),
            e.problems.map { it.toString() },
        )
        assertNull(store.lastContext)
    }

    @Test
    fun `unsupported release notes fail by default`() {
        val store = FakeStoreProvider(capabilities = FakeStoreProvider.ALL_CAPABILITIES - Capability.ReleaseNotes)
        writeReleaseNotes("en-US.txt", "Bug fixes.")

        val e = assertFailsWith<ValidationException> { storePilot(store).publish(bundle(), listOf(settings())) }

        assertEquals(
            "Fake Store does not support release notes. Set onUnsupported to warn to skip them for this store.",
            e.problems.single().message,
        )
    }

    @Test
    fun `unsupported release notes are skipped with a warning when onUnsupported is warn`() {
        val store = FakeStoreProvider(capabilities = FakeStoreProvider.ALL_CAPABILITIES - Capability.ReleaseNotes)
        writeReleaseNotes("en-US.txt", "Bug fixes.")

        val result = storePilot(store).publish(bundle(), listOf(settings(onUnsupported = OnUnsupported.WARN))).single()

        assertEquals(emptyMap(), store.state.releases(packageName, PRODUCTION).single().releaseNotes)
        assertEquals(
            listOf("stores.fake: warning: Fake Store does not support release notes, so they are skipped."),
            result.warnings.map { it.toString() },
        )
    }

    @Test
    fun `a failed commit discards the edit`() {
        val store = FakeStoreProvider()
        store.state.commitFailure = "Play said no"

        val e = assertFailsWith<StoreException> { storePilot(store).publish(bundle(), listOf(settings())) }

        assertEquals("Play said no", e.message)
        assertEquals(1, store.state.discards)
        assertEquals(emptyList(), store.state.releases(packageName, PRODUCTION))
    }

    @Test
    fun `the store gets the package name, options, and secrets`() {
        val store = FakeStoreProvider(knownOptions = setOf("color"))
        val secrets = Secrets { field -> if (field == "token") Secret("s3cret") else null }

        StorePilot(StoreRegistry(listOf(store)), secrets = { secrets })
            .publish(bundle(), listOf(settings(options = mapOf("color" to "red"))))

        val context = store.lastContext!!
        assertEquals(packageName, context.packageName)
        assertEquals(mapOf("color" to "red"), context.options)
        assertEquals("s3cret", context.secrets["token"]?.reveal())
    }

    @Test
    fun `promote copies the newest release with its notes to the other track`() {
        val store = FakeStoreProvider()
        val newest = Release(listOf(5), COMPLETED, releaseNotes = mapOf(en to "New."), name = "5.0")
        store.state.tracks[packageName] = mutableMapOf(
            TESTING to listOf(Release(listOf(6), ReleaseStatus.DRAFT), newest, Release(listOf(4), COMPLETED)),
        )

        storePilot(store).promote(TESTING, PRODUCTION, Rollout(0.2), listOf(settings()))

        assertEquals(
            listOf(newest.copy(status = IN_PROGRESS, rollout = Rollout(0.2))),
            store.state.releases(packageName, PRODUCTION),
        )
    }

    @Test
    fun `promote fails when the source track has no release`() {
        val store = FakeStoreProvider()

        val e = assertFailsWith<ValidationException> {
            storePilot(store).promote(TESTING, PRODUCTION, Rollout.FULL, listOf(settings()))
        }

        assertEquals("Track 'testing' has no release to promote.", e.problems.single().message)
        assertEquals(1, store.state.discards)
    }

    @Test
    fun `halt and resume change the status of the staged release`() {
        val store = FakeStoreProvider()
        val staged = Release(listOf(2), IN_PROGRESS, Rollout(0.1))
        val completed = Release(listOf(1), COMPLETED)
        store.state.tracks[packageName] = mutableMapOf(PRODUCTION to listOf(staged, completed))
        val storePilot = storePilot(store)

        storePilot.halt(PRODUCTION, listOf(settings()))
        assertEquals(listOf(staged.copy(status = HALTED), completed), store.state.releases(packageName, PRODUCTION))

        storePilot.resume(PRODUCTION, listOf(settings()))
        assertEquals(listOf(staged, completed), store.state.releases(packageName, PRODUCTION))
    }

    @Test
    fun `halt fails when no rollout is in progress`() {
        val store = FakeStoreProvider()
        store.state.tracks[packageName] = mutableMapOf(PRODUCTION to listOf(Release(listOf(1), COMPLETED)))

        val e = assertFailsWith<ValidationException> { storePilot(store).halt(PRODUCTION, listOf(settings())) }

        assertEquals("Track 'production' has no release with status inProgress.", e.problems.single().message)
    }

    private fun storePilot(store: FakeStoreProvider) =
        StorePilot(StoreRegistry(listOf(store)), secrets = { Secrets { null } })

    private fun bundle(): Artifact {
        val path = dir.resolve("app-release.aab")
        path.writeText("bundle")
        return Artifact(path, ArtifactType.BUNDLE)
    }

    private fun writeReleaseNotes(name: String, text: String) {
        val notes = dir.resolve("store/release-notes").createDirectories()
        notes.resolve(name).writeText(text)
    }

    private fun settings(
        track: Track = PRODUCTION,
        rollout: Rollout = Rollout.FULL,
        releaseStatus: ReleaseStatus? = null,
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
        options: Map<String, String> = emptyMap(),
    ) = StoreSettings(
        store = StoreId("fake"),
        packageName = packageName,
        metadataDir = dir.resolve("store"),
        track = track,
        rollout = rollout,
        releaseStatus = releaseStatus,
        onUnsupported = onUnsupported,
        options = options,
    )
}
