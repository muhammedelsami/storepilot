package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.AppDetails
import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.GraphicType.ICON
import com.muhammedelsami.storepilot.api.GraphicType.PHONE_SCREENSHOTS
import com.muhammedelsami.storepilot.api.ListingField.SHORT_DESCRIPTION
import com.muhammedelsami.storepilot.api.ListingField.TITLE
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.Secrets
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.api.fake.FakeStoreProvider
import com.muhammedelsami.storepilot.engine.StorePilot
import com.muhammedelsami.storepilot.engine.StoreRegistry
import com.muhammedelsami.storepilot.engine.config.ListingSettings
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import com.muhammedelsami.storepilot.engine.config.StoreSettings
import com.muhammedelsami.storepilot.engine.report.TextReport
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingOperationsTest {
    @TempDir
    lateinit var dir: Path

    private val store = FakeStoreProvider()
    private val storePilot = StorePilot(StoreRegistry(listOf(store)), secrets = { Secrets { null } })
    private val packageName = "com.example.app"
    private val en = LocaleTag("en-US")
    private val de = LocaleTag("de-DE")
    private val shot1 = TestImages.png(1080, 1920, seed = 1)
    private val shot2 = TestImages.png(1080, 1920, seed = 2)
    private val shot3 = TestImages.png(1080, 1920, seed = 3)

    @Test
    fun `push sets details, text, and graphics in one commit`() {
        write("details.yml", "contact-email: dev@example.com\n")
        write("listing/en-US/title.txt", "Pilot\n")
        image("listing/en-US/graphics/phone-screenshots/01.png", shot1)
        image("listing/en-US/graphics/phone-screenshots/02.png", shot2)

        val result = storePilot.pushListing(listOf(settings())).single()

        assertTrue(result.committed)
        assertEquals(1, store.state.commits)
        assertEquals("dev@example.com", store.state.details[packageName]?.contactEmail)
        assertEquals(mapOf(TITLE to "Pilot"), store.state.listings[packageName]?.get(en))
        assertEquals(listOf(shot1, shot2).map(ImageInspector::sha256), remoteHashes(en))
        assertEquals(
            listOf(
                "details contact-email: \"dev@example.com\" (new)",
                "listing en-US title: \"Pilot\" (new)",
                "graphics en-US phone-screenshots: replace 0 images with 2 images",
            ),
            result.changes.map { TextReport.describe(it) },
        )
    }

    @Test
    fun `diff compares every item and commits nothing`() {
        store.state.details[packageName] = AppDetails(contactEmail = "old@example.com", contactWebsite = "https://example.com")
        store.state.listings[packageName] = mutableMapOf(
            en to mapOf(TITLE to "Pilot", SHORT_DESCRIPTION to "Old short\n"),
            de to mapOf(TITLE to "Pilot DE"),
        )
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot1)
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot3)
        write("details.yml", "contact-email: new@example.com\n")
        write("listing/en-US/title.txt", "Pilot\n")
        write("listing/en-US/short-description.txt", "New short\n")
        image("listing/en-US/graphics/phone-screenshots/01.png", shot1)
        image("listing/en-US/graphics/phone-screenshots/02.png", shot2)

        val diff = storePilot.diffListing(listOf(settings())).single().listingDiff!!

        assertTrue(diff.hasChanges)
        assertEquals(0, store.state.commits)
        assertEquals(
            listOf("contact-email" to DiffStatus.CHANGED, "contact-website" to DiffStatus.REMOTE_ONLY),
            diff.details.map { it.field to it.status },
        )
        assertEquals(
            listOf(
                Triple(en, TITLE, DiffStatus.UNCHANGED),
                Triple(en, SHORT_DESCRIPTION, DiffStatus.CHANGED),
                Triple(de, TITLE, DiffStatus.REMOTE_ONLY),
            ),
            diff.text.map { Triple(it.locale, it.field, it.status) },
        )
        assertEquals(
            listOf(DiffStatus.UNCHANGED, DiffStatus.ADDED, DiffStatus.REMOVED),
            diff.images.map { it.status },
        )
    }

    @Test
    fun `without replaceScreenshots only new images are added`() {
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot3)
        image("listing/en-US/graphics/phone-screenshots/01.png", shot1)

        storePilot.pushListing(listOf(settings(ListingSettings(replaceScreenshots = false))))

        assertEquals(listOf(shot3, shot1).map(ImageInspector::sha256), remoteHashes(en))
    }

    @Test
    fun `a new order replaces the images`() {
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot2)
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot1)
        image("listing/en-US/graphics/phone-screenshots/01.png", shot1)
        image("listing/en-US/graphics/phone-screenshots/02.png", shot2)

        val result = storePilot.pushListing(listOf(settings())).single()

        assertEquals(listOf(shot1, shot2).map(ImageInspector::sha256), remoteHashes(en))
        assertEquals(1, result.changes.size)
    }

    @Test
    fun `an unchanged listing makes no changes`() {
        store.state.listings[packageName] = mutableMapOf(en to mapOf(TITLE to "Pilot"))
        store.state.addImage(packageName, en, ICON, TestImages.png(512, 512))
        write("listing/en-US/title.txt", "Pilot\n")
        image("listing/en-US/graphics/icon.png", TestImages.png(512, 512))

        val result = storePilot.pushListing(listOf(settings())).single()

        assertEquals(emptyList(), result.changes)
        assertFalse(result.listingDiff!!.hasChanges)
    }

    @Test
    fun `text only leaves graphics alone`() {
        write("listing/en-US/title.txt", "Pilot\n")
        image("listing/en-US/graphics/icon.png", TestImages.png(512, 512))

        storePilot.pushListing(listOf(settings()), textOnly = true)

        assertEquals(emptyList(), store.state.images(packageName, en, ICON))
        assertEquals(mapOf(TITLE to "Pilot"), store.state.listings[packageName]?.get(en))
    }

    @Test
    fun `validation errors stop the push before any store call`() {
        write("listing/en-US/title.txt", "x".repeat(31))

        val e = assertFailsWith<ValidationException> { storePilot.pushListing(listOf(settings())) }

        assertEquals("title has 31 characters; Fake Store allows 30.", e.problems.single().message)
        assertNull(store.lastContext)
    }

    @Test
    fun `publish with listing commits the release and the listing together`() {
        write("listing/en-US/title.txt", "Pilot\n")
        val bundle = dir.resolve("app.aab").also { it.writeText("bundle") }

        val result = storePilot.publish(Artifact.of(bundle), listOf(settings()), withListing = true).single()

        assertEquals(1, store.state.commits)
        assertEquals(1, store.state.releases(packageName, Track.INTERNAL).size)
        assertEquals(mapOf(TITLE to "Pilot"), store.state.listings[packageName]?.get(en))
        assertEquals(3, result.changes.size)
    }

    @Test
    fun `publish checks the release notes limit`() {
        write("release-notes/en-US.txt", "x".repeat(501))
        val bundle = dir.resolve("app.aab").also { it.writeText("bundle") }

        val e = assertFailsWith<ValidationException> { storePilot.publish(Artifact.of(bundle), listOf(settings())) }

        assertEquals("The en-US release notes have 501 characters; Fake Store allows 500.", e.problems.single().message)
    }

    @Test
    fun `pull writes the store listing`() {
        store.state.details[packageName] = AppDetails(defaultLanguage = en, contactEmail = "dev@example.com")
        store.state.listings[packageName] = mutableMapOf(en to mapOf(TITLE to "Pilot"))
        store.state.addImage(packageName, en, ICON, TestImages.png(512, 512))
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot1)
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, TestImages.jpeg(1080, 1920))

        val result = storePilot.pullListing(settings())

        assertEquals(
            listOf(
                "details.yml",
                "listing/en-US/title.txt",
                "listing/en-US/graphics/icon.png",
                "listing/en-US/graphics/phone-screenshots/01.png",
                "listing/en-US/graphics/phone-screenshots/02.jpg",
            ),
            result.files.map { dir.relativize(it).invariantSeparatorsPathString },
        )
        assertEquals("default-language: \"en-US\"\ncontact-email: \"dev@example.com\"\n", dir.resolve("details.yml").readText())
        assertEquals("Pilot\n", dir.resolve("listing/en-US/title.txt").readText())
        assertEquals(1, store.state.discards)
        assertEquals(emptyList(), storePilot.diffListing(listOf(settings())).single().changes)
    }

    @Test
    fun `pull needs overwrite for existing files and then removes stale screenshots`() {
        store.state.listings[packageName] = mutableMapOf(en to mapOf(TITLE to "Pilot"))
        store.state.addImage(packageName, en, PHONE_SCREENSHOTS, shot1)
        write("listing/en-US/title.txt", "Old\n")
        image("listing/en-US/graphics/phone-screenshots/01.png", shot2)
        image("listing/en-US/graphics/phone-screenshots/02.png", shot3)

        val e = assertFailsWith<ValidationException> { storePilot.pullListing(settings()) }
        assertEquals(2, e.problems.size)

        storePilot.pullListing(settings(), overwrite = true)

        assertEquals("Pilot\n", dir.resolve("listing/en-US/title.txt").readText())
        assertTrue(dir.resolve("listing/en-US/graphics/phone-screenshots/01.png").exists())
        assertFalse(dir.resolve("listing/en-US/graphics/phone-screenshots/02.png").exists())
    }

    private fun remoteHashes(locale: LocaleTag) = store.state.images(packageName, locale, PHONE_SCREENSHOTS).map { it.sha256 }

    private fun settings(listing: ListingSettings = ListingSettings()) = StoreSettings(
        StoreId("fake"), packageName, dir, Track.INTERNAL, Rollout.FULL, null, OnUnsupported.FAIL, emptyMap(), listing,
    )

    private fun write(path: String, text: String) {
        dir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun image(path: String, bytes: ByteArray) {
        dir.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
    }
}
