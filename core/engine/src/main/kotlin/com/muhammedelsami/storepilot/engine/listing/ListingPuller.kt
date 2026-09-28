package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.AppDetails
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ImageFormat
import com.muhammedelsami.storepilot.api.ListingRules
import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.RemoteImage
import com.muhammedelsami.storepilot.api.StoreEdit
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.ValidationException
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class PullResult(val store: StoreId, val files: List<Path>, val warnings: List<Problem>)

/** Writes the store's listing into the metadata directory (`storepilot listing pull`). */
internal class ListingPuller(private val edit: StoreEdit, private val rules: ListingRules) {
    private val warnings = mutableListOf<Problem>()

    fun pull(store: StoreId, metadataDir: Path, overwrite: Boolean): PullResult {
        val files = plan(metadataDir)
        if (!overwrite) {
            val existing = files.keys.mapNotNull(::conflict).distinct()
            if (existing.isNotEmpty()) {
                throw ValidationException(
                    existing.take(MAX_LISTED).map {
                        Problem.error("The file exists. Pull with --overwrite to replace it.", it.toString())
                    },
                )
            }
        }
        if (overwrite) clearOldImages(files.keys)
        for ((file, content) in files) {
            file.parent.createDirectories()
            when (content) {
                is Content.Text -> file.writeText(content.text)
                is Content.Image -> file.writeBytes(content.bytes)
            }
        }
        return PullResult(store, files.keys.toList(), warnings)
    }

    private sealed interface Content {
        class Text(val text: String) : Content

        class Image(val bytes: ByteArray) : Content
    }

    private fun plan(metadataDir: Path): Map<Path, Content> {
        val files = linkedMapOf<Path, Content>()
        val details = edit.details()
        if (details != AppDetails()) files[metadataDir.resolve(ListingReader.DETAILS_FILE)] = Content.Text(detailsYaml(details))

        val listings = edit.listings()
        for ((locale, fields) in listings.toSortedMap(compareBy { it.value })) {
            val dir = metadataDir.resolve(ListingReader.LISTING_DIR).resolve(locale.value)
            for ((field, text) in fields) {
                if (text.isNotBlank()) files[dir.resolve(field.fileName)] = Content.Text(ListingReader.normalizeText(text) + "\n")
            }
            val graphicsDir = dir.resolve(ListingReader.GRAPHICS_DIR)
            for (type in GraphicType.entries.filter { it in rules.graphics }) {
                edit.images(locale, type).forEachIndexed { index, image ->
                    val bytes = download(image)
                    val extension = runCatching { ImageInspector.inspect(bytes).format }.getOrDefault(ImageFormat.PNG).extensions.first()
                    val file = if (type.multiple) {
                        graphicsDir.resolve(type.fileName).resolve(String.format(Locale.ROOT, "%02d.%s", index + 1, extension))
                    } else {
                        graphicsDir.resolve("${type.fileName}.$extension")
                    }
                    files[file] = Content.Image(bytes)
                }
            }
        }
        return files
    }

    private fun download(image: RemoteImage): ByteArray {
        val bytes = edit.downloadImage(image)
        if (ImageInspector.sha256(bytes) != image.sha256) {
            warnings += Problem.warning(
                "The downloaded image ${image.id} differs from the store's original; it may be a smaller preview.",
                image.url,
            )
        }
        return bytes
    }

    /**
     * What already exists where [file] goes: for a screenshot, its directory when that holds images;
     * for a single image, the image of that type with any extension; otherwise the file itself.
     */
    private fun conflict(file: Path): Path? {
        val dir = file.parent
        if (!isImage(file)) return file.takeIf { it.exists() }
        if (!dir.isDirectory()) return null
        return if (isScreenshotDir(dir)) {
            dir.takeIf { it.listDirectoryEntries().any(::isImage) }
        } else {
            dir.listDirectoryEntries().firstOrNull { isImage(it) && it.nameWithoutExtension == file.nameWithoutExtension }
        }
    }

    private fun isScreenshotDir(dir: Path) = GraphicType.entries.any { it.multiple && dir.fileName?.toString() == it.fileName }

    /**
     * Removes the images that the pulled ones replace, so no stale screenshots stay behind: every image
     * in a screenshot directory, and other extensions of a single image (`icon.jpg` for `icon.png`).
     */
    private fun clearOldImages(files: Collection<Path>) {
        val stale = files.filter(::isImage).flatMap { file ->
            val dir = file.parent
            if (!dir.isDirectory()) return@flatMap emptyList()
            dir.listDirectoryEntries().filter {
                isImage(it) && (isScreenshotDir(dir) || it.nameWithoutExtension == file.nameWithoutExtension)
            }
        }
        stale.distinct().forEach { it.deleteIfExists() }
    }

    private fun isImage(file: Path) = ImageFormat.entries.any { file.extension.lowercase() in it.extensions }

    private fun detailsYaml(details: AppDetails): String = buildString {
        fun line(key: String, value: String?) {
            if (value != null) append(key).append(": ").append(quote(value)).append('\n')
        }
        line(ListingDiffer.DEFAULT_LANGUAGE, details.defaultLanguage?.value)
        line(ListingDiffer.CONTACT_EMAIL, details.contactEmail)
        line(ListingDiffer.CONTACT_WEBSITE, details.contactWebsite)
        line(ListingDiffer.CONTACT_PHONE, details.contactPhone)
    }

    // A YAML double-quoted scalar; the escapes are the JSON ones.
    private fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    private companion object {
        const val MAX_LISTED = 10
    }
}
