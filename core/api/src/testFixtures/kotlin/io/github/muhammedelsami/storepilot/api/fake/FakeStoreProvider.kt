package io.github.muhammedelsami.storepilot.api.fake

import io.github.muhammedelsami.storepilot.api.AppDetails
import io.github.muhammedelsami.storepilot.api.Artifact
import io.github.muhammedelsami.storepilot.api.ArtifactType
import io.github.muhammedelsami.storepilot.api.Capability
import io.github.muhammedelsami.storepilot.api.GraphicRule
import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ImageFormat
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.ListingRules
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.Problem
import io.github.muhammedelsami.storepilot.api.Release
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.RemoteImage
import io.github.muhammedelsami.storepilot.api.StoreContext
import io.github.muhammedelsami.storepilot.api.StoreEdit
import io.github.muhammedelsami.storepilot.api.StoreException
import io.github.muhammedelsami.storepilot.api.StoreId
import io.github.muhammedelsami.storepilot.api.StoreProvider
import io.github.muhammedelsami.storepilot.api.Track
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.readBytes

/**
 * In-memory store for tests. Track rules follow Google Play: a completed release replaces the track,
 * a staged release keeps the last completed release, and a draft replaces only the previous draft.
 */
class FakeStoreProvider(
    override val id: StoreId = StoreId("fake"),
    override val capabilities: Set<Capability> = ALL_CAPABILITIES,
    /** Option keys that [validateOptions] accepts. */
    private val knownOptions: Set<String> = emptySet(),
    override val listingRules: ListingRules = DEFAULT_RULES,
    val state: FakeStoreState = FakeStoreState(),
) : StoreProvider {
    /** For [java.util.ServiceLoader]. Kotlin generates no such constructor when a parameter is a value class. */
    constructor() : this(id = StoreId("fake"))

    override val displayName: String = "Fake Store"

    /** The context of the last [openEdit] call. */
    var lastContext: StoreContext? = null
        private set

    override fun validateOptions(options: Map<String, String>): List<Problem> =
        options.keys.filter { it !in knownOptions }.map { Problem.error("$displayName has no option '$it'.") }

    override fun openEdit(context: StoreContext): StoreEdit {
        lastContext = context
        return FakeEdit(state, context.packageName)
    }

    companion object {
        val ALL_CAPABILITIES: Set<Capability> = setOf(
            Capability.Upload(ArtifactType.BUNDLE),
            Capability.Upload(ArtifactType.APK),
            Capability.StagedRollout,
            Capability.HaltAndResume,
            Capability.Promote,
            Capability.ReleaseNotes,
            Capability.Listing,
        )

        /** Play's text limits; every graphic type as PNG or JPEG, up to 8 images, sizes not checked. */
        val DEFAULT_RULES = ListingRules(
            textLimits = mapOf(
                ListingField.TITLE to 30,
                ListingField.SHORT_DESCRIPTION to 80,
                ListingField.FULL_DESCRIPTION to 4000,
                ListingField.VIDEO_URL to 1000,
            ),
            releaseNotesLimit = 500,
            graphics = GraphicType.entries.associateWith { type ->
                GraphicRule(formats = ImageFormat.entries.toSet(), maxCount = if (type.multiple) 8 else 1)
            },
        )
    }
}

class FakeStoreState {
    /** Committed releases by package name and track, newest first. */
    val tracks: MutableMap<String, MutableMap<Track, List<Release>>> = mutableMapOf()

    /** Committed uploads by package name. */
    val uploads: MutableMap<String, MutableList<Artifact>> = mutableMapOf()

    var nextVersionCode: Long = 1
    var commits: Int = 0
    var discards: Int = 0

    /** When set, [StoreEdit.commit] throws a [StoreException] with this message. */
    var commitFailure: String? = null

    val details: MutableMap<String, AppDetails> = mutableMapOf()
    val listings: MutableMap<String, MutableMap<LocaleTag, Map<ListingField, String>>> = mutableMapOf()
    val images: MutableMap<String, MutableMap<Pair<LocaleTag, GraphicType>, List<RemoteImage>>> = mutableMapOf()

    /** Content of every image ever added, by image ID. */
    val imageContent: MutableMap<String, ByteArray> = mutableMapOf()
    private var nextImageId = 1

    fun releases(packageName: String, track: Track): List<Release> = tracks[packageName]?.get(track).orEmpty()

    fun images(packageName: String, locale: LocaleTag, type: GraphicType): List<RemoteImage> =
        images[packageName]?.get(locale to type).orEmpty()

    /** Adds a committed image, for test setup. */
    fun addImage(packageName: String, locale: LocaleTag, type: GraphicType, content: ByteArray): RemoteImage {
        val image = newImage(content)
        val byType = images.getOrPut(packageName) { mutableMapOf() }
        byType[locale to type] = byType[locale to type].orEmpty() + image
        return image
    }

    internal fun newImage(content: ByteArray): RemoteImage {
        val id = "image-${nextImageId++}"
        imageContent[id] = content
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        return RemoteImage(id, sha256, "https://fake.test/$id")
    }
}

private class FakeEdit(private val state: FakeStoreState, private val packageName: String) : StoreEdit {
    private val tracks = state.tracks[packageName].orEmpty().toMutableMap()
    private val uploads = mutableListOf<Artifact>()
    private var details = state.details[packageName] ?: AppDetails()
    private val listings = state.listings[packageName].orEmpty().toMutableMap()
    private val images = state.images[packageName].orEmpty().toMutableMap()
    private var closed = false

    override fun releases(track: Track): List<Release> {
        checkOpen()
        return tracks[track].orEmpty()
    }

    override fun upload(artifact: Artifact): Long {
        checkOpen()
        uploads += artifact
        return state.nextVersionCode++
    }

    override fun setRelease(track: Track, release: Release) {
        checkOpen()
        val current = tracks[track].orEmpty()
        tracks[track] = when (release.status) {
            ReleaseStatus.COMPLETED -> listOf(release)
            ReleaseStatus.IN_PROGRESS, ReleaseStatus.HALTED ->
                listOf(release) + listOfNotNull(current.firstOrNull { it.status == ReleaseStatus.COMPLETED })
            ReleaseStatus.DRAFT -> listOf(release) + current.filter { it.status != ReleaseStatus.DRAFT }
        }
    }

    override fun details(): AppDetails {
        checkOpen()
        return details
    }

    override fun setDetails(details: AppDetails) {
        checkOpen()
        val current = this.details
        this.details = AppDetails(
            defaultLanguage = details.defaultLanguage ?: current.defaultLanguage,
            contactEmail = details.contactEmail ?: current.contactEmail,
            contactWebsite = details.contactWebsite ?: current.contactWebsite,
            contactPhone = details.contactPhone ?: current.contactPhone,
        )
    }

    override fun listings(): Map<LocaleTag, Map<ListingField, String>> {
        checkOpen()
        return listings.toMap()
    }

    override fun setListing(locale: LocaleTag, fields: Map<ListingField, String>) {
        checkOpen()
        listings[locale] = listings[locale].orEmpty() + fields
    }

    override fun images(locale: LocaleTag, type: GraphicType): List<RemoteImage> {
        checkOpen()
        return images[locale to type].orEmpty()
    }

    override fun deleteImages(locale: LocaleTag, type: GraphicType) {
        checkOpen()
        images.remove(locale to type)
    }

    override fun uploadImage(locale: LocaleTag, type: GraphicType, file: Path): RemoteImage {
        checkOpen()
        val image = state.newImage(file.readBytes())
        images[locale to type] = images[locale to type].orEmpty() + image
        return image
    }

    override fun downloadImage(image: RemoteImage): ByteArray {
        checkOpen()
        return state.imageContent.getValue(image.id)
    }

    override fun commit() {
        checkOpen()
        closed = true
        state.commitFailure?.let { throw StoreException(it) }
        state.details[packageName] = details
        state.listings[packageName] = listings
        state.images[packageName] = images
        state.tracks[packageName] = tracks
        state.uploads.getOrPut(packageName) { mutableListOf() } += uploads
        state.commits++
    }

    override fun discard() {
        closed = true
        state.discards++
    }

    private fun checkOpen() = check(!closed) { "Edit is already committed or discarded" }
}
