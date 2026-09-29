package io.github.muhammedelsami.storepilot.engine.report

import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.Release
import io.github.muhammedelsami.storepilot.engine.Change
import io.github.muhammedelsami.storepilot.engine.ListingCheck
import io.github.muhammedelsami.storepilot.engine.StoreResult
import io.github.muhammedelsami.storepilot.engine.config.configName
import io.github.muhammedelsami.storepilot.engine.listing.Coverage
import io.github.muhammedelsami.storepilot.engine.listing.DiffStatus
import io.github.muhammedelsami.storepilot.engine.listing.PullResult

/** Plain-text reports for terminals and build logs. */
object TextReport {
    /** What an operation changed, or would change in a dry run. */
    fun render(results: List<StoreResult>): String = buildString {
        for (result in results) {
            val state = if (result.committed) "committed" else "dry run, nothing committed"
            appendLine("${result.store} (${result.packageName}): $state")
            if (result.changes.isEmpty()) appendLine("  no changes")
            for (change in result.changes) appendLine("  " + describe(change))
            for (warning in result.warnings) appendLine("  $warning")
        }
    }

    /** Every compared item of the listing, including unchanged ones (`storepilot listing diff`). */
    fun renderDiff(results: List<StoreResult>): String = buildString {
        for (result in results) {
            val diff = result.listingDiff ?: continue
            appendLine("${result.store} (${result.packageName}): " + if (diff.hasChanges) "changes" else "no changes")
            for (item in diff.details) appendLine("  details ${item.field}: ${status(item.status)}")
            for (item in diff.text) appendLine("  ${item.locale} ${label(item.field)}: ${status(item.status)}")
            for ((key, images) in diff.images.groupBy { it.locale to it.type }) {
                val counts = images.groupingBy { it.status }.eachCount().entries.sortedBy { it.key }
                    .joinToString(", ") { (status, count) -> "$count ${status(status)}" }
                val reordered = diff.graphicsActions.any {
                    it.locale == key.first && it.type == key.second && it.replace
                } && images.none { it.status.isChange }
                appendLine("  ${key.first} ${key.second.fileName}: $counts" + if (reordered) ", new order" else "")
            }
            for (warning in result.warnings) appendLine("  $warning")
        }
    }

    /** Problems and the locale coverage table (`storepilot listing validate`). */
    fun renderValidation(checks: List<ListingCheck>): String = buildString {
        for (check in checks) {
            val problems = check.validation.problems
            val errors = problems.count { it.isError }
            val warnings = problems.size - errors
            appendLine("${check.store}: ${plural(errors, "error")}, ${plural(warnings, "warning")}")
            problems.sortedBy { !it.isError }.forEach { appendLine("  $it") }
            val rows = check.validation.listing.coverage
            if (rows.isEmpty()) continue
            val graphicTypes = GraphicType.entries.filter { type -> rows.any { (it.graphics[type] ?: 0) > 0 } }
            val header = listOf("locale") + ListingField.entries.map(::label) + graphicTypes.map { it.fileName }
            val table = rows.map { row ->
                listOf(row.locale.value) +
                    ListingField.entries.map { coverage(row.text.getValue(it)) } +
                    graphicTypes.map { type -> row.graphics[type]?.takeIf { it > 0 }?.toString() ?: "-" }
            }
            appendLine("  Locale coverage:")
            appendTable(listOf(header) + table, indent = "    ")
        }
    }

    fun renderPull(result: PullResult): String = buildString {
        appendLine("${result.store}: wrote ${plural(result.files.size, "file")}")
        result.files.forEach { appendLine("  $it") }
        result.warnings.forEach { appendLine("  $it") }
    }

    fun describe(change: Change): String = when (change) {
        is Change.ArtifactUpload ->
            "upload ${change.artifact.path.fileName}" + change.versionCode?.let { ", version code $it" }.orEmpty()
        is Change.ReleaseUpdate -> {
            val before = change.before.joinToString("; ") { describe(it) }.ifEmpty { "no releases" }
            "track ${change.track}: ${describe(change.after)}\n    before: $before"
        }
        is Change.DetailsUpdate -> "details ${change.field}: ${quote(change.after)}" + was(change.before)
        is Change.ListingTextUpdate -> "listing ${change.locale} ${label(change.field)}: ${quote(change.after)}" + was(change.before)
        is Change.GraphicsUpdate -> {
            val images = plural(change.after.size, "image")
            "graphics ${change.locale} ${change.type.fileName}: " +
                if (change.replaced) "replace ${plural(change.before.size, "image")} with $images" else "add $images"
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

    private fun StringBuilder.appendTable(rows: List<List<String>>, indent: String) {
        val widths = rows.first().indices.map { column -> rows.maxOf { it[column].length } }
        for (row in rows) {
            appendLine(indent + row.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd())
        }
    }

    private fun label(field: ListingField) = field.fileName.removeSuffix(".txt")

    private fun status(status: DiffStatus) = status.name.lowercase().replace('_', ' ')

    private fun coverage(coverage: Coverage) = when (coverage) {
        Coverage.PRESENT -> "yes"
        Coverage.FALLBACK -> "fallback"
        Coverage.MISSING -> "-"
    }

    private fun plural(count: Int, noun: String) = "$count $noun" + if (count == 1) "" else "s"

    private fun was(before: String?) = if (before == null) " (new)" else " (was ${quote(before)})"

    /** One line, at most 60 characters. */
    private fun quote(text: String): String {
        val line = text.replace("\n", " ")
        return "\"" + (if (line.length > 60) line.take(59) + "…" else line) + "\""
    }
}
