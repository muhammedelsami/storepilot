package com.muhammedelsami.storepilot.store.googleplay

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
    )

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
        return GooglePlayEdit(publisher, context.packageName, PlayOptions.parse(context.options), context.logger)
    }

    companion object {
        val ID = StoreId("google-play")

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
