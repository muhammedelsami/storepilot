package com.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.Capability
import com.muhammedelsami.storepilot.api.GraphicRule
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ImageFormat
import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.ListingRules
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Secrets
import com.muhammedelsami.storepilot.api.StoreContext
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.StoreProvider
import com.muhammedelsami.storepilot.api.ValidationException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Google Play, through the Google Play Developer API (androidpublisher v3).
 *
 * Credentials, in this order: the `service-account-json` secret (the key file's content), the
 * `service-account-file` secret (its path), then Application Default Credentials.
 */
class GooglePlayStoreProvider internal constructor(
    private val transport: HttpTransport,
    private val authorize: (Secrets) -> HttpRequestInitializer,
) : StoreProvider {
    /** For [java.util.ServiceLoader]. */
    constructor() : this(NetHttpTransport(), { secrets -> HttpCredentialsAdapter(credentials(secrets)) })

    override val id: StoreId = ID

    override val displayName: String = "Google Play"

    override val capabilities: Set<Capability> = setOf(
        Capability.Upload(ArtifactType.BUNDLE),
        Capability.Upload(ArtifactType.APK),
        Capability.StagedRollout,
        Capability.HaltAndResume,
        Capability.Promote,
        Capability.ReleaseNotes,
        Capability.Listing,
    )

    override val listingRules: ListingRules = LISTING_RULES

    override fun validateOptions(options: Map<String, String>): List<Problem> =
        options.mapNotNull { (key, value) ->
            try {
                PlayOptions.parseOption(key, value)
                null
            } catch (e: IllegalArgumentException) {
                Problem.error(e.message.orEmpty(), "stores.$ID.$key")
            }
        }

    override fun openEdit(context: StoreContext): StoreEdit {
        val credentials = authorize(context.secrets)
        val publisher = AndroidPublisher.Builder(transport, GsonFactory.getDefaultInstance()) { request ->
            credentials.initialize(request)
            request.connectTimeout = CONNECT_TIMEOUT_MILLIS
            request.readTimeout = READ_TIMEOUT_MILLIS
        }.setApplicationName(APPLICATION_NAME).build()
        return GooglePlayEdit(publisher, context.packageName, PlayOptions.parse(context.options), context.logger, ::download)
    }

    /**
     * Downloads a listing image without credentials. Play's image URL serves a preview; the `=h16383`
     * suffix asks for the full size. The engine compares the result with Play's SHA-256.
     */
    private fun download(url: String): ByteArray {
        val response = transport.createRequestFactory().buildGetRequest(GenericUrl("$url=h16383")).execute()
        return try {
            response.content.readBytes()
        } finally {
            response.disconnect()
        }
    }

    companion object {
        val ID = StoreId("google-play")

        private val PNG_OR_JPEG = setOf(ImageFormat.PNG, ImageFormat.JPEG)

        /**
         * From the Play Console Help pages "Add preview assets to showcase your app" and "Create and set
         * up your app" (checked 2026-09-28). Play documents no limit for the video URL.
         */
        val LISTING_RULES = ListingRules(
            textLimits = mapOf(
                ListingField.TITLE to 30,
                ListingField.SHORT_DESCRIPTION to 80,
                ListingField.FULL_DESCRIPTION to 4000,
                ListingField.VIDEO_URL to null,
            ),
            releaseNotesLimit = 500,
            graphics = mapOf(
                GraphicType.ICON to GraphicRule(setOf(ImageFormat.PNG), width = 512, height = 512, maxBytes = 1024 * 1024),
                GraphicType.FEATURE_GRAPHIC to GraphicRule(PNG_OR_JPEG, width = 1024, height = 500, alphaAllowed = false),
                GraphicType.TV_BANNER to GraphicRule(PNG_OR_JPEG, width = 1280, height = 720, alphaAllowed = false),
                GraphicType.PHONE_SCREENSHOTS to screenshots(minCount = 2),
                // Large-screen screenshots may be up to 7680 px.
                GraphicType.TABLET_7_SCREENSHOTS to screenshots(maxSide = 7680),
                GraphicType.TABLET_10_SCREENSHOTS to screenshots(maxSide = 7680),
                GraphicType.TV_SCREENSHOTS to screenshots(),
                GraphicType.WEAR_SCREENSHOTS to GraphicRule(
                    PNG_OR_JPEG,
                    maxCount = 8,
                    minSide = 384,
                    maxAspectRatio = 1.0,
                    alphaAllowed = false,
                ),
            ),
            videoUrlPattern = Regex("https?://(www\\.|m\\.)?(youtube\\.com/watch\\?v=[\\w-]+|youtu\\.be/[\\w-]+)"),
            videoUrlHint = "Google Play needs the URL of one YouTube video, without a playlist, a channel, or extra " +
                "parameters such as a start time.",
        )

        private fun screenshots(minCount: Int = 1, maxSide: Int = 3840) = GraphicRule(
            PNG_OR_JPEG,
            minCount = minCount,
            maxCount = 8,
            minSide = 320,
            maxSide = maxSide,
            maxAspectRatio = 2.0,
            alphaAllowed = false,
        )

        private const val CONNECT_TIMEOUT_MILLIS = 60_000

        // Large bundles take a while to be processed after the upload.
        private const val READ_TIMEOUT_MILLIS = 5 * 60_000

        private val APPLICATION_NAME =
            "StorePilot/" + (GooglePlayStoreProvider::class.java.`package`?.implementationVersion ?: "dev")

        internal fun credentials(secrets: Secrets): GoogleCredentials {
            val json = secrets["service-account-json"]
            val file = secrets["service-account-file"]
            val credentials = when {
                json != null -> serviceAccount("service-account-json") { json.reveal().byteInputStream() }
                file != null -> serviceAccount(file.reveal()) { Path.of(file.reveal()).inputStream() }
                else -> try {
                    GoogleCredentials.getApplicationDefault()
                } catch (e: IOException) {
                    throw ValidationException(
                        Problem.error(
                            "No Google Play credentials. Set ${ID.envPrefix}SERVICE_ACCOUNT_JSON or " +
                                "${ID.envPrefix}SERVICE_ACCOUNT_FILE, or provide Application Default Credentials.",
                        ),
                    )
                }
            }
            return credentials.createScoped(AndroidPublisherScopes.ANDROIDPUBLISHER)
        }

        // The message never contains the key itself.
        private fun serviceAccount(source: String, open: () -> InputStream): GoogleCredentials =
            try {
                open().use { ServiceAccountCredentials.fromStream(it) }
            } catch (e: IOException) {
                throw ValidationException(
                    Problem.error("Cannot read the Google Play service account key: ${e.javaClass.simpleName}.", source),
                )
            }
    }
}

/** The store-specific options under `stores.google-play` (docs/design.md §6). */
internal class PlayOptions(
    /** For new releases only: Play does not allow changing it after the rollout started. */
    val inAppUpdatePriority: Int?,
    val changesNotSentForReview: Boolean,
) {
    companion object {
        fun parse(options: Map<String, String>): PlayOptions {
            val values = options.mapValues { (key, value) -> parseOption(key, value) }
            return PlayOptions(
                inAppUpdatePriority = values["inAppUpdatePriority"] as Int?,
                changesNotSentForReview = values["changesNotSentForReview"] as Boolean? ?: false,
            )
        }

        fun parseOption(key: String, value: String): Any = when (key) {
            "inAppUpdatePriority" -> value.toIntOrNull()?.takeIf { it in 0..5 }
                ?: throw IllegalArgumentException("inAppUpdatePriority must be a whole number from 0 to 5, got '$value'.")
            "changesNotSentForReview" -> value.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("changesNotSentForReview must be true or false, got '$value'.")
            else -> throw IllegalArgumentException("Google Play has no option '$key'.")
        }
    }
}
