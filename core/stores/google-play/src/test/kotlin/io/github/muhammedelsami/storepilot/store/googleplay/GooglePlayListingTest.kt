package io.github.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.http.HttpRequestInitializer
import com.google.gson.JsonParser
import io.github.muhammedelsami.storepilot.api.AppDetails
import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.Logger
import io.github.muhammedelsami.storepilot.api.RemoteImage
import io.github.muhammedelsami.storepilot.api.Secrets
import io.github.muhammedelsami.storepilot.api.StoreContext
import io.github.muhammedelsami.storepilot.store.googleplay.PlayServer.Companion.EDIT_ID
import io.github.muhammedelsami.storepilot.store.googleplay.PlayServer.Companion.PACKAGE_NAME
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GooglePlayListingTest {
    @TempDir
    lateinit var dir: Path

    private val server = PlayServer().apply { onEditStart() }
    private val provider = GooglePlayStoreProvider(server.transport) { HttpRequestInitializer {} }
    private val he = LocaleTag("he-IL")

    @Test
    fun `reads details and listings with Play's language codes`() {
        server.on("GET", "/edits/$EDIT_ID/details", """{"defaultLanguage": "iw-IL", "contactEmail": "dev@example.com", "contactPhone": ""}""")
        server.on(
            "GET",
            "/edits/$EDIT_ID/listings",
            """{"listings": [{"language": "iw-IL", "title": "Pilot", "shortDescription": "", "video": "https://youtu.be/abc"}]}""",
        )

        val edit = provider.openEdit(context())

        assertEquals(AppDetails(defaultLanguage = he, contactEmail = "dev@example.com"), edit.details())
        assertEquals(
            mapOf(he to mapOf(ListingField.TITLE to "Pilot", ListingField.VIDEO_URL to "https://youtu.be/abc")),
            edit.listings(),
        )
    }

    @Test
    fun `updates whole listings and details with Play's language codes`() {
        server.on("GET", "/edits/$EDIT_ID/listings", """{"listings": [{"language": "iw-IL", "title": "Pilot", "fullDescription": "Long"}]}""")
        server.on("GET", "/edits/$EDIT_ID/details", """{"defaultLanguage": "en-US", "contactEmail": "dev@example.com"}""")
        server.on("PUT", "/edits/$EDIT_ID/listings/iw-IL")
        server.on("PUT", "/edits/$EDIT_ID/listings/de-DE")
        server.on("PUT", "/edits/$EDIT_ID/details")

        val edit = provider.openEdit(context())
        edit.setListing(he, mapOf(ListingField.SHORT_DESCRIPTION to "Kurz"))
        edit.setListing(LocaleTag("de-DE"), mapOf(ListingField.TITLE to "Pilot DE"))
        edit.setDetails(AppDetails(defaultLanguage = he))

        assertJson(
            """{"language": "iw-IL", "title": "Pilot", "shortDescription": "Kurz", "fullDescription": "Long"}""",
            server.requests("PUT", "/edits/$EDIT_ID/listings/iw-IL").single().body,
        )
        assertJson("""{"language": "de-DE", "title": "Pilot DE"}""", server.requests("PUT", "/edits/$EDIT_ID/listings/de-DE").single().body)
        assertJson(
            """{"defaultLanguage": "iw-IL", "contactEmail": "dev@example.com"}""",
            server.requests("PUT", "/edits/$EDIT_ID/details").single().body,
        )
        assertTrue(server.requests.none { it.method == "PATCH" })
    }

    @Test
    fun `lists, deletes, and uploads images by Play image type`() {
        val path = "/edits/$EDIT_ID/listings/en-US/phoneScreenshots"
        server.on("GET", path, """{"images": [{"id": "1", "sha256": "ABC", "url": "https://play-lh.test/one"}]}""")
        server.on("DELETE", path, "", status = 204)
        server.onMediaUpload(path, """{"image": {"id": "2", "sha256": "def", "url": "https://play-lh.test/two"}}""")
        val file = dir.resolve("01.png").also { it.writeText("png") }
        val en = LocaleTag("en-US")

        val edit = provider.openEdit(context())
        val images = edit.images(en, GraphicType.PHONE_SCREENSHOTS)
        edit.deleteImages(en, GraphicType.PHONE_SCREENSHOTS)
        val uploaded = edit.uploadImage(en, GraphicType.PHONE_SCREENSHOTS, file)

        assertEquals(listOf(RemoteImage("1", "abc", "https://play-lh.test/one")), images)
        assertEquals(RemoteImage("2", "def", "https://play-lh.test/two"), uploaded)
        assertEquals(1, server.requests("DELETE", path).size)
        assertEquals("/upload/androidpublisher/v3/applications/$PACKAGE_NAME$path", server.uploads().single().path)
    }

    @Test
    fun `downloads the full-size image`() {
        server.onPath("GET", "/one=h16383", "image bytes")

        val bytes = provider.openEdit(context()).downloadImage(RemoteImage("1", "abc", "https://play-lh.test/one"))

        assertEquals("image bytes", String(bytes))
    }

    @Test
    fun `maps every graphic type to a Play image type`() {
        assertEquals(
            listOf(
                "icon", "featureGraphic", "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots",
                "tvScreenshots", "tvBanner", "wearScreenshots",
            ),
            GraphicType.entries.map { GooglePlayEdit.imageType(it) },
        )
        assertEquals(GraphicType.entries.toSet(), GooglePlayStoreProvider.LISTING_RULES.graphics.keys)
    }

    @Test
    fun `accepts only plain YouTube video URLs`() {
        val pattern = GooglePlayStoreProvider.LISTING_RULES.videoUrlPattern!!
        assertTrue(pattern.matches("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(pattern.matches("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(pattern.matches("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertFalse(pattern.matches("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(pattern.matches("https://vimeo.com/123"))
    }

    private fun context() = StoreContext(PACKAGE_NAME, emptyMap(), Secrets { null }, Logger.NONE)

    private fun assertJson(expected: String, actual: String) =
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))
}
