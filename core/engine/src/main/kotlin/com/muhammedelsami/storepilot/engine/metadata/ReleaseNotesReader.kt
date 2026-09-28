package com.muhammedelsami.storepilot.engine.metadata

import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.StoreId
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Reads `release-notes/` in the metadata directory (docs/design.md §3.1): `<locale>.txt` files for
 * every store, and `<store-id>/<locale>.txt` files that win for that store.
 */
class ReleaseNotesReader(private val knownStores: Set<StoreId>) {

    class Result(val notes: Map<LocaleTag, String>, val problems: List<Problem>)

    fun read(metadataDir: Path, store: StoreId): Result {
        val dir = metadataDir.resolve(DIR_NAME)
        if (!dir.isDirectory()) return Result(emptyMap(), emptyList())

        val problems = mutableListOf<Problem>()
        val notes = readLocaleFiles(dir, problems).toMutableMap()
        for (subDir in visibleEntries(dir).filter { it.isDirectory() }) {
            val id = try {
                StoreId(subDir.name)
            } catch (e: IllegalArgumentException) {
                null
            }
            when {
                id == store -> notes += readLocaleFiles(subDir, problems)
                id == null || id !in knownStores ->
                    problems += Problem.warning("Not a known store ID, so this directory is ignored.", subDir.toString())
            }
        }
        return Result(notes, problems)
    }

    private fun readLocaleFiles(dir: Path, problems: MutableList<Problem>): Map<LocaleTag, String> {
        val notes = mutableMapOf<LocaleTag, String>()
        for (file in visibleEntries(dir).filter { !it.isDirectory() }) {
            if (file.extension != "txt") {
                problems += Problem.error("Release notes must be <locale>.txt files, for example en-US.txt.", file.toString())
                continue
            }
            val locale = try {
                LocaleTag(file.nameWithoutExtension)
            } catch (e: IllegalArgumentException) {
                problems += Problem.error(e.message.orEmpty(), file.toString())
                continue
            }
            val text = file.readText().replace("\r\n", "\n").trim()
            if (text.isEmpty()) {
                problems += Problem.warning("The file is empty, so it is ignored.", file.toString())
            } else {
                notes[locale] = text
            }
        }
        return notes
    }

    /** Entries sorted by name, without hidden files such as `.DS_Store`. */
    private fun visibleEntries(dir: Path): List<Path> =
        dir.listDirectoryEntries().filter { !it.name.startsWith(".") }.sortedBy { it.name }

    private companion object {
        const val DIR_NAME = "release-notes"
    }
}
