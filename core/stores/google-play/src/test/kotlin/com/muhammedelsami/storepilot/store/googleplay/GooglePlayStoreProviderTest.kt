package com.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.http.HttpRequestInitializer
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.gson.JsonParser
import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Logger
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.Secret
import com.muhammedelsami.storepilot.api.Secrets
import com.muhammedelsami.storepilot.api.StoreContext
import com.muhammedelsami.storepilot.api.StoreException
import com.muhammedelsami.storepilot.api.StoreProvider
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.store.googleplay.PlayServer.Companion.EDIT_ID
import com.muhammedelsami.storepilot.store.googleplay.PlayServer.Companion.PACKAGE_NAME
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.ServiceLoader
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GooglePlayStoreProviderTest {
    @TempDir
    lateinit var dir: Path

    private val server = PlayServer()
    private val provider = GooglePlayStoreProvider(server.transport) { HttpRequestInitializer {} }

    @Test
    fun `publishes a staged bundle release and keeps the completed release`() {
        server.onEditStart()
        server.on("GET", "/edits/$EDIT_ID/tracks/production", """{"track": "production", "releases": [{"versionCodes": ["41"], "status": "completed"}]}""")
        server.onUpload("bundles", 42)
        server.on("PUT", "/edits/$EDIT_ID/tracks/production")
        server.on("POST", "/edits/$EDIT_ID:commit", """{"id": "$EDIT_ID"}""")
        val bundle = dir.resolve("app-release.aab").also { it.writeText("bundle") }

        val edit = provider.openEdit(context(mapOf("inAppUpdatePriority" to "3", "changesNotSentForReview" to "true")))
        assertEquals(listOf(Release(listOf(41), ReleaseStatus.COMPLETED)), edit.releases(Track.PRODUCTION))
        val versionCode = edit.upload(Artifact(bundle, ArtifactType.BUNDLE))
        edit.setRelease(
            Track.PRODUCTION,
            Release(listOf(versionCode), ReleaseStatus.IN_PROGRESS, Rollout(0.1), mapOf(LocaleTag("en-US") to "Bug fixes.")),
        )
        edit.commit()

        assertEquals(42, versionCode)
        assertJson(
            """
            {"track": "production", "releases": [
              {"versionCodes": ["42"], "status": "inProgress", "userFraction": 0.1, "inAppUpdatePriority": 3,
               "releaseNotes": [{"language": "en-US", "text": "Bug fixes."}]},
              {"versionCodes": ["41"], "status": "completed"}
            ]}
            """,
            server.requests("PUT", "/edits/$EDIT_ID/tracks/production").single().body,
        )
        assertEquals("changesNotSentForReview=true", server.requests("POST", "/edits/$EDIT_ID:commit").single().query)
    }

    @Test
    fun `halting keeps what Play set on the release`() {
        server.onEditStart()
        server.on(
            "GET",
            "/edits/$EDIT_ID/tracks/production",
            """
            {"track": "production", "releases": [
              {"versionCodes": ["2"], "status": "inProgress", "userFraction": 0.1, "inAppUpdatePriority": 4,
               "countryTargeting": {"countries": ["TR"]}, "releaseNotes": [{"language": "iw-IL", "text": "Notes"}]},
              {"versionCodes": ["1"], "status": "completed"}
            ]}
            """,
        )
        server.on("PUT", "/edits/$EDIT_ID/tracks/production")

        val edit = provider.openEdit(context(mapOf("inAppUpdatePriority" to "3")))
        val staged = edit.releases(Track.PRODUCTION).first()
        edit.setRelease(Track.PRODUCTION, staged.copy(status = ReleaseStatus.HALTED))

        assertEquals(mapOf(LocaleTag("he-IL") to "Notes"), staged.releaseNotes)
        assertJson(
            """
            {"track": "production", "releases": [
              {"versionCodes": ["2"], "status": "halted", "userFraction": 0.1, "inAppUpdatePriority": 4,
               "countryTargeting": {"countries": ["TR"]}, "releaseNotes": [{"language": "iw-IL", "text": "Notes"}]},
              {"versionCodes": ["1"], "status": "completed"}
            ]}
            """,
            server.requests("PUT", "/edits/$EDIT_ID/tracks/production").single().body,
        )
    }

    @Test
    fun `the portable testing track is closed testing`() {
        server.onEditStart()
        server.on("GET", "/edits/$EDIT_ID/tracks/alpha", """{"track": "alpha"}""")
        server.on("GET", "/edits/$EDIT_ID/tracks/qa", """{"track": "qa"}""")

        val edit = provider.openEdit(context())

        assertEquals(emptyList(), edit.releases(Track.TESTING))
        assertEquals(emptyList(), edit.releases(Track("qa")))
    }

    @Test
    fun `Play errors become store errors with Play's message`() {
        server.onEditStart()
        server.on(
            "GET",
            "/edits/$EDIT_ID/tracks/beta",
            """{"error": {"code": 404, "message": "Track not found."}}""",
            status = 404,
        )

        val edit = provider.openEdit(context())
        val e = assertFailsWith<StoreException> { edit.releases(Track("beta")) }

        assertEquals("Google Play could not read track 'beta' for $PACKAGE_NAME: 404 Track not found.", e.message)
    }

    @Test
    fun `discard deletes the edit`() {
        server.onEditStart()
        server.on("DELETE", "/edits/$EDIT_ID", "", status = 204)

        provider.openEdit(context()).discard()

        assertEquals(1, server.requests("DELETE", "/edits/$EDIT_ID").size)
    }

    @Test
    fun `validates the Play options`() {
        val problems = provider.validateOptions(
            mapOf("inAppUpdatePriority" to "6", "changesNotSentForReview" to "yes", "track" to "x", "color" to "red"),
        )

        assertEquals(
            listOf(
                "stores.google-play.inAppUpdatePriority: error: inAppUpdatePriority must be a whole number from 0 to 5, got '6'.",
                "stores.google-play.changesNotSentForReview: error: changesNotSentForReview must be true or false, got 'yes'.",
                "stores.google-play.track: error: Google Play has no option 'track'.",
                "stores.google-play.color: error: Google Play has no option 'color'.",
            ),
            problems.map { it.toString() },
        )
        assertEquals(emptyList(), provider.validateOptions(mapOf("inAppUpdatePriority" to "5", "changesNotSentForReview" to "false")))
    }

    @Test
    fun `reads a service account key from the secret`() {
        val credentials = GooglePlayStoreProvider.credentials(secrets("service-account-json" to serviceAccountJson()))

        assertIs<ServiceAccountCredentials>(credentials)
        assertEquals("storepilot@example.iam.gserviceaccount.com", credentials.clientEmail)
        assertEquals(listOf("https://www.googleapis.com/auth/androidpublisher"), credentials.scopes.toList())
    }

    @Test
    fun `reads a service account key from a file`() {
        val file = dir.resolve("key.json").also { it.writeText(serviceAccountJson()) }

        val credentials = GooglePlayStoreProvider.credentials(secrets("service-account-file" to file.toString()))

        assertIs<ServiceAccountCredentials>(credentials)
    }

    @Test
    fun `a broken key is a validation error that does not show the key`() {
        val e = assertFailsWith<ValidationException> {
            GooglePlayStoreProvider.credentials(secrets("service-account-json" to """{"type": "service_account", "private_key": "TOPSECRET"}"""))
        }

        assertFalse("TOPSECRET" in e.message.orEmpty())
        assertEquals("service-account-json", e.problems.single().source)
    }

    @Test
    fun `is found through ServiceLoader`() {
        assertTrue(ServiceLoader.load(StoreProvider::class.java).any { it is GooglePlayStoreProvider })
    }

    private fun context(options: Map<String, String> = emptyMap()) =
        StoreContext(PACKAGE_NAME, options, Secrets { null }, Logger.NONE)

    private fun secrets(vararg values: Pair<String, String>) = Secrets { field -> values.toMap()[field]?.let(::Secret) }

    private fun assertJson(expected: String, actual: String) =
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))

    private fun serviceAccountJson(): String {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return """
            {"type": "service_account", "project_id": "example", "private_key_id": "1",
             "private_key": "${pem.replace("\n", "\\n")}", "client_email": "storepilot@example.iam.gserviceaccount.com",
             "client_id": "1", "token_uri": "https://oauth2.googleapis.com/token"}
        """.trimIndent()
    }
}
