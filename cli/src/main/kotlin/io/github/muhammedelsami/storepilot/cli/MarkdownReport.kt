package io.github.muhammedelsami.storepilot.cli

import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.engine.Change
import io.github.muhammedelsami.storepilot.engine.ListingCheck
import io.github.muhammedelsami.storepilot.engine.StoreResult
import io.github.muhammedelsami.storepilot.engine.listing.Coverage
import io.github.muhammedelsami.storepilot.engine.listing.DiffStatus
import io.github.muhammedelsami.storepilot.engine.listing.PullResult
import io.github.muhammedelsami.storepilot.engine.report.TextReport

/** GitHub-flavored Markdown for `--summary-file`, which the action points at the job summary. */
internal object MarkdownReport {
    fun render(results: List<StoreResult>, dryRun: Boolean): String = buildString {
        appendLine("### StorePilot" + if (dryRun) " (dry run, nothing committed)" else "")
        for (result in results) {
            appendLine()
            appendLine("**${result.store}** · `${result.packageName}` · " + if (result.committed) "committed" else "not committed")
            appendLine()
            if (result.changes.isEmpty()) appendLine("No changes.")
            result.changes.forEach { appendLine("- " + escape(describe(it))) }
            warnings(result.warnings.map { it.toString() })
        }
    }

    fun renderDiff(results: List<StoreResult>): String = buildString {
        appendLine("### StorePilot listing diff")
        for (result in results) {
            val diff = result.listingDiff ?: continue
            appendLine()
            appendLine("**${result.store}** · `${result.packageName}` · " + if (diff.hasChanges) "changes" else "no changes")
            val rows = diff.details.map { listOf("details", it.field, status(it.status)) } +
                diff.text.map { listOf(it.locale.value, label(it.field), status(it.status)) } +
                diff.images.groupBy { it.locale to it.type }.map { (key, images) ->
                    val counts = images.groupingBy { it.status }.eachCount().entries.sortedBy { it.key }
                        .joinToString(", ") { (status, count) -> "$count ${status(status)}" }
                    listOf(key.first.value, key.second.fileName, counts)
                }
            if (rows.isNotEmpty()) {
                appendLine()
                table(listOf("Locale", "Item", "Status"), rows)
            }
            warnings(result.warnings.map { it.toString() })
        }
    }

    fun renderValidation(checks: List<ListingCheck>): String = buildString {
        appendLine("### StorePilot listing check")
        for (check in checks) {
            val problems = check.validation.problems
            val errors = problems.count { it.isError }
            appendLine()
            appendLine("**${check.store}** · $errors error(s), ${problems.size - errors} warning(s)")
            if (problems.isNotEmpty()) {
                appendLine()
                problems.sortedBy { !it.isError }.forEach {
                    appendLine("- " + (if (it.isError) "❌ " else "⚠️ ") + escape(it.toString()))
                }
            }
            val coverage = check.validation.listing.coverage
            if (coverage.isNotEmpty()) {
                val types = GraphicType.entries.filter { type -> coverage.any { (it.graphics[type] ?: 0) > 0 } }
                appendLine()
                table(
                    listOf("Locale") + ListingField.entries.map(::label) + types.map { it.fileName },
                    coverage.map { row ->
                        listOf(row.locale.value) +
                            ListingField.entries.map { symbol(row.text.getValue(it)) } +
                            types.map { type -> row.graphics[type]?.takeIf { it > 0 }?.toString() ?: "–" }
                    },
                )
            }
        }
    }

    fun renderPull(result: PullResult): String = buildString {
        appendLine("### StorePilot listing pull")
        appendLine()
        appendLine("**${result.store}** · wrote ${result.files.size} file(s)")
        warnings(result.warnings.map { it.toString() })
    }

    // A release update has a second line ("before: ..."); a list item needs one line.
    private fun describe(change: Change): String =
        TextReport.describe(change).lines().joinToString("; ") { it.trim() }

    private fun StringBuilder.table(header: List<String>, rows: List<List<String>>) {
        appendLine("| " + header.joinToString(" | ") + " |")
        appendLine("|" + header.joinToString("|") { "---" } + "|")
        rows.forEach { row -> appendLine("| " + row.joinToString(" | ") { escape(it) } + " |") }
    }

    private fun StringBuilder.warnings(warnings: List<String>) {
        if (warnings.isEmpty()) return
        appendLine()
        warnings.forEach { appendLine("- ⚠️ " + escape(it)) }
    }

    private fun label(field: ListingField) = field.fileName.removeSuffix(".txt")

    private fun status(status: DiffStatus) = status.name.lowercase().replace('_', ' ')

    private fun symbol(coverage: Coverage) = when (coverage) {
        Coverage.PRESENT -> "✅"
        Coverage.FALLBACK -> "fallback"
        Coverage.MISSING -> "–"
    }

    private fun escape(text: String) = text.replace("|", "\\|").replace("\n", " ")
}
