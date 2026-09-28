package com.muhammedelsami.storepilot.engine.report

import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.engine.Change
import com.muhammedelsami.storepilot.engine.StoreResult
import com.muhammedelsami.storepilot.engine.config.configName

/** A plain-text summary of operation results, for terminals and build logs. */
object TextReport {
    fun render(results: List<StoreResult>): String = buildString {
        for (result in results) {
            val state = if (result.committed) "committed" else "dry run, nothing committed"
            appendLine("${result.store} (${result.packageName}): $state")
            for (change in result.changes) {
                when (change) {
                    is Change.ArtifactUpload -> {
                        val versionCode = change.versionCode?.let { ", version code $it" }.orEmpty()
                        appendLine("  upload ${change.artifact.path.fileName}$versionCode")
                    }
                    is Change.ReleaseUpdate -> {
                        appendLine("  track ${change.track}: ${describe(change.after)}")
                        val before = change.before.joinToString("; ") { describe(it) }.ifEmpty { "no releases" }
                        appendLine("    before: $before")
                    }
                }
            }
            for (warning in result.warnings) appendLine("  $warning")
        }
    }

    /** For example `version code 42, inProgress 10%, release notes: en-US, tr-TR`. */
    fun describe(release: Release): String = buildString {
        val codes = release.versionCodes
        append(
            when (codes.size) {
                0 -> "new release"
                1 -> "version code ${codes.single()}"
                else -> "version codes ${codes.joinToString(", ")}"
            },
        )
        append(", ").append(release.status.configName)
        if (!release.rollout.isFull) append(" ").append(release.rollout)
        release.name?.let { append(", name '").append(it).append("'") }
        if (release.releaseNotes.isNotEmpty()) {
            append(", release notes: ").append(release.releaseNotes.keys.joinToString(", "))
        }
    }
}
