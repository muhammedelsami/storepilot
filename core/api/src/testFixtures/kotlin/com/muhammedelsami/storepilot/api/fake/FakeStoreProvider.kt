package com.muhammedelsami.storepilot.api.fake

import com.muhammedelsami.storepilot.api.Artifact
import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.Capability
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.StoreContext
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.api.StoreException
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.StoreProvider
import com.muhammedelsami.storepilot.api.Track

/**
 * In-memory store for tests. Track rules follow Google Play: a completed release replaces the track,
 * a staged release keeps the last completed release, and a draft replaces only the previous draft.
 */
class FakeStoreProvider(
    override val id: StoreId = StoreId("fake"),
    override val capabilities: Set<Capability> = ALL_CAPABILITIES,
    /** Option keys that [validateOptions] accepts. */
    private val knownOptions: Set<String> = emptySet(),
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

    fun releases(packageName: String, track: Track): List<Release> = tracks[packageName]?.get(track).orEmpty()
}

private class FakeEdit(private val state: FakeStoreState, private val packageName: String) : StoreEdit {
    private val tracks = state.tracks[packageName].orEmpty().toMutableMap()
    private val uploads = mutableListOf<Artifact>()
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

    override fun commit() {
        checkOpen()
        closed = true
        state.commitFailure?.let { throw StoreException(it) }
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
