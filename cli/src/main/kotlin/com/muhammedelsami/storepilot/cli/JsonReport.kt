package com.muhammedelsami.storepilot.cli

import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.engine.Change
import com.muhammedelsami.storepilot.engine.StoreResult
import com.muhammedelsami.storepilot.engine.config.configName

/** The `--output json` result, also used by the GitHub Action (docs/design.md §7.3). */
internal object JsonReport {
    fun render(results: List<StoreResult>, dryRun: Boolean): String =
        Json.write(mapOf("dryRun" to dryRun, "stores" to results.map(::store)))

    private fun store(result: StoreResult): Map<String, Any?> {
        val release = result.changes.filterIsInstance<Change.ReleaseUpdate>().lastOrNull()
        return linkedMapOf(
            "store" to result.store.value,
            "packageName" to result.packageName,
            "status" to if (result.committed) "committed" else "dryRun",
            "track" to release?.track?.name,
            "versionCodes" to release?.after?.versionCodes.orEmpty(),
            "changes" to result.changes.map(::change),
            "warnings" to result.warnings.map { it.toString() },
        )
    }

    private fun change(change: Change): Map<String, Any?> = when (change) {
        is Change.ArtifactUpload -> linkedMapOf(
            "type" to "upload",
            "artifact" to change.artifact.path.toString(),
            "versionCode" to change.versionCode,
        )
        is Change.ReleaseUpdate -> linkedMapOf(
            "type" to "release",
            "track" to change.track.name,
            "release" to release(change.after),
            "before" to change.before.map(::release),
        )
    }

    private fun release(release: Release): Map<String, Any?> = linkedMapOf(
        "versionCodes" to release.versionCodes,
        "status" to release.status.configName,
        "rollout" to release.rollout.fraction,
        "name" to release.name,
        "releaseNotes" to release.releaseNotes.mapKeys { it.key.value },
    )
}

/** A small JSON writer for maps, lists, strings, numbers, booleans, and null. */
internal object Json {
    fun write(value: Any?): String = buildString { write(value, "") }

    private fun StringBuilder.write(value: Any?, indent: String) {
        when (value) {
            null -> append("null")
            is String -> string(value)
            is Boolean, is Int, is Long -> append(value)
            is Double -> {
                require(value.isFinite()) { "JSON has no $value" }
                append(value)
            }
            is Map<*, *> -> container(value.entries, "{", "}", indent) { (key, item), inner ->
                string(key as String)
                append(": ")
                write(item, inner)
            }
            is List<*> -> container(value, "[", "]", indent) { item, inner -> write(item, inner) }
            else -> throw IllegalArgumentException("Cannot write ${value.javaClass.name} as JSON")
        }
    }

    private fun <T> StringBuilder.container(
        items: Collection<T>,
        open: String,
        close: String,
        indent: String,
        writeItem: StringBuilder.(T, String) -> Unit,
    ) {
        if (items.isEmpty()) {
            append(open).append(close)
            return
        }
        val inner = "$indent  "
        append(open).append('\n')
        items.forEachIndexed { index, item ->
            append(inner)
            writeItem(item, inner)
            if (index < items.size - 1) append(',')
            append('\n')
        }
        append(indent).append(close)
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
