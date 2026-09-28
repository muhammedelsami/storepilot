package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.AppDetails
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ImageFormat
import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.engine.config.YamlReader
import org.snakeyaml.engine.v2.nodes.Node
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** The listing in the metadata directory (docs/design.md §3.1). */
data class LocalListing(
    /** Null when there is no `details.yml`. */
    val details: AppDetails?,
    val locales: Map<LocaleTag, LocaleListing>,
)

/** One `listing/<locale>/` directory. */
data class LocaleListing(
    val dir: Path,
    val text: Map<ListingField, String>,
    /** Images in display order. */
    val graphics: Map<GraphicType, List<LocalImage>>,
) {
    fun textFile(field: ListingField): Path = dir.resolve(field.fileName)
}

data class LocalImage(val path: Path, val info: ImageInfo)

/** Reads `details.yml` and `listing/`. Problems are collected, not thrown. */
object ListingReader {
    const val DETAILS_FILE = "details.yml"
    const val LISTING_DIR = "listing"
    const val GRAPHICS_DIR = "graphics"

    class Result(val listing: LocalListing, val problems: List<Problem>)

    fun read(metadataDir: Path): Result {
        val problems = mutableListOf<Problem>()
        val detailsFile = metadataDir.resolve(DETAILS_FILE)
        val details = if (detailsFile.isRegularFile()) readDetails(detailsFile, problems) else null

        val locales = sortedMapOf<LocaleTag, LocaleListing>(compareBy { it.value })
        val listingDir = metadataDir.resolve(LISTING_DIR)
        if (listingDir.isDirectory()) {
            for (entry in visibleEntries(listingDir)) {
                val locale = try {
                    LocaleTag(entry.name)
                } catch (e: IllegalArgumentException) {
                    problems += Problem.error(e.message.orEmpty(), entry.toString())
                    continue
                }
                if (!entry.isDirectory()) {
                    problems += Problem.error("Expected a directory for locale $locale.", entry.toString())
                    continue
                }
                locales[locale] = readLocale(entry, problems)
            }
        }
        return Result(LocalListing(details, locales), problems)
    }

    /** Line endings become `\n` and line breaks at the end are removed. Other whitespace stays. */
    fun normalizeText(text: String): String = text.replace("\r\n", "\n").trimEnd('\n', '\r')

    private fun readLocale(dir: Path, problems: MutableList<Problem>): LocaleListing {
        val text = mutableMapOf<ListingField, String>()
        var graphics = emptyMap<GraphicType, List<LocalImage>>()
        for (entry in visibleEntries(dir)) {
            val field = ListingField.entries.firstOrNull { it.fileName == entry.name }
            when {
                field != null && entry.isRegularFile() -> {
                    val value = normalizeText(entry.readText())
                    if (value.isBlank()) {
                        problems += Problem.warning("The file is empty, so the field is not changed.", entry.toString())
                    } else {
                        text[field] = value
                    }
                }
                entry.name == GRAPHICS_DIR && entry.isDirectory() -> graphics = readGraphics(entry, problems)
                else -> problems += Problem.error(
                    "Unknown entry. A locale directory holds ${ListingField.entries.joinToString { it.fileName }} " +
                        "and $GRAPHICS_DIR/.",
                    entry.toString(),
                )
            }
        }
        return LocaleListing(dir, text, graphics)
    }

    private fun readGraphics(dir: Path, problems: MutableList<Problem>): Map<GraphicType, List<LocalImage>> {
        val graphics = mutableMapOf<GraphicType, List<LocalImage>>()
        for (entry in visibleEntries(dir)) {
            val multiple = GraphicType.entries.firstOrNull { it.multiple && it.fileName == entry.name && entry.isDirectory() }
            val single = GraphicType.entries.firstOrNull {
                !it.multiple && it.fileName == entry.nameWithoutExtension && entry.isRegularFile() && isImageName(entry)
            }
            val type = multiple ?: single
            when {
                type == null -> problems += Problem.error(
                    "Unknown graphic. Expected " + GraphicType.entries.joinToString {
                        if (it.multiple) "${it.fileName}/" else "${it.fileName}.png"
                    } + ".",
                    entry.toString(),
                )
                type in graphics -> problems += Problem.error("More than one file for ${type.fileName}.", entry.toString())
                multiple != null -> graphics[type] = visibleEntries(entry).mapNotNull { file ->
                    if (file.isRegularFile() && isImageName(file)) {
                        inspect(file, problems)
                    } else {
                        problems += Problem.error("Screenshots must be .png, .jpg, or .jpeg files.", file.toString())
                        null
                    }
                }
                else -> graphics[type] = listOfNotNull(inspect(entry, problems))
            }
        }
        return graphics
    }

    private fun inspect(file: Path, problems: MutableList<Problem>): LocalImage? =
        try {
            LocalImage(file, ImageInspector.inspect(file))
        } catch (e: IllegalArgumentException) {
            problems += Problem.error(e.message.orEmpty(), file.toString())
            null
        }

    private fun isImageName(file: Path) = ImageFormat.entries.any { file.extension.lowercase() in it.extensions }

    private fun readDetails(file: Path, problems: MutableList<Problem>): AppDetails {
        val reader = DetailsReader(file.toString())
        val details = reader.read(YamlReader.compose(file.readText(), file.toString()))
        problems += reader.problems
        return details
    }

    /** Entries sorted by name, without hidden files such as `.DS_Store`. */
    private fun visibleEntries(dir: Path): List<Path> =
        dir.listDirectoryEntries().filter { !it.name.startsWith(".") }.sortedBy { it.name }
}

private class DetailsReader(source: String) : YamlReader(source) {
    fun read(root: Node?): AppDetails {
        var details = AppDetails()
        for (entry in root?.let { entries(it, "details.yml") }.orEmpty()) {
            val value = entry.value
            when (entry.key) {
                "default-language" -> details = details.copy(defaultLanguage = convert(value, entry.key, ::LocaleTag))
                "contact-email" -> details = details.copy(contactEmail = scalar(value, entry.key))
                "contact-website" -> details = details.copy(contactWebsite = scalar(value, entry.key))
                "contact-phone" -> details = details.copy(contactPhone = scalar(value, entry.key))
                else -> error(
                    entry.keyNode,
                    "Unknown key '${entry.key}'. Keys: default-language, contact-email, contact-website, contact-phone.",
                )
            }
        }
        return details
    }
}
