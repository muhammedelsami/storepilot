package com.muhammedelsami.storepilot.cli

import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.Release
import com.muhammedelsami.storepilot.engine.Change
import com.muhammedelsami.storepilot.engine.ListingCheck
import com.muhammedelsami.storepilot.engine.StoreResult
import com.muhammedelsami.storepilot.engine.config.configName
import com.muhammedelsami.storepilot.engine.listing.DiffStatus
import com.muhammedelsami.storepilot.engine.listing.PullResult

/** The `--output json` result, also used by the GitHub Action (docs/design.md §7.3). */
internal object JsonReport {
    fun render(results: List<StoreResult>, dryRun: Boolean): String =
        Json.write(mapOf("dryRun" to dryRun, "stores" to results.map(::store)))

    /** `listing diff`. [DiffStatus] values are written in camel case, for example `remoteOnly`. */
    fun renderDiff(results: List<StoreResult>): String = Json.write(
        mapOf(
            "listingChanged" to results.any { it.listingDiff?.hasChanges == true },
            "stores" to results.map { result ->
                val diff = result.listingDiff
                linkedMapOf(
                    "store" to result.store.value,
                    "packageName" to result.packageName,
                    "changed" to (diff?.hasChanges == true),
                    "details" to diff?.details.orEmpty().map { linkedMapOf("field" to it.field, "status" to status(it.status)) },
                    "text" to diff?.text.orEmpty().map {
                        linkedMapOf("locale" to it.locale.value, "field" to field(it.field), "status" to status(it.status))
                    },
                    "images" to diff?.images.orEmpty().map {
                        linkedMapOf(
                            "locale" to it.locale.value,
                            "type" to it.type.fileName,
                            "status" to status(it.status),
                            "sha256" to it.sha256,
                            "file" to it.local?.toString(),
                        )
                    },
                    "warnings" to result.warnings.map { it.toString() },
                )
            },
        ),
    )

    /** `listing validate`. */
    fun renderValidation(checks: List<ListingCheck>): String = Json.write(
        mapOf(
            "stores" to checks.map { check ->
                val problems = check.validation.problems
                linkedMapOf(
                    "store" to check.store.value,
                    "errors" to problems.count { it.isError },
                    "warnings" to problems.count { !it.isError },
                    "problems" to problems.map(::problem),
                    "coverage" to check.validation.listing.coverage.map { row ->
                        linkedMapOf(
                            "locale" to row.locale.value,
                            "text" to row.text.entries.associate { (field, coverage) -> field(field) to coverage.name.lowercase() },
                            "graphics" to row.graphics.entries.associate { (type, count) -> type.fileName to count },
                        )
                    },
                )
            },
        ),
    )

    /** `listing pull`. */
    fun renderPull(result: PullResult): String = Json.write(
        linkedMapOf(
            "store" to result.store.value,
            "files" to result.files.map { it.toString() },
            "warnings" to result.warnings.map { it.toString() },
        ),
    )

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
        is Change.DetailsUpdate -> linkedMapOf(
            "type" to "details",
            "field" to change.field,
            "before" to change.before,
            "after" to change.after,
        )
        is Change.ListingTextUpdate -> linkedMapOf(
            "type" to "listing",
            "locale" to change.locale.value,
            "field" to field(change.field),
            "before" to change.before,
            "after" to change.after,
        )
        is Change.GraphicsUpdate -> linkedMapOf(
            "type" to "graphics",
            "locale" to change.locale.value,
            "graphicType" to change.type.fileName,
            "replaced" to change.replaced,
            "before" to change.before.map { it.sha256 },
            "after" to change.after.map { it.toString() },
        )
    }

    private fun problem(problem: Problem): Map<String, Any?> = linkedMapOf(
        "severity" to problem.severity.name.lowercase(),
        "message" to problem.message,
        "source" to problem.source,
        "rule" to problem.rule,
    )

    private fun status(status: DiffStatus) = status.configName

    private fun field(field: ListingField) = field.fileName.removeSuffix(".txt")

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
