package io.github.muhammedelsami.storepilot.engine.listing

import io.github.muhammedelsami.storepilot.api.AppDetails
import io.github.muhammedelsami.storepilot.api.GraphicRule
import io.github.muhammedelsami.storepilot.api.GraphicType
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.ListingRules
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.Problem
import io.github.muhammedelsami.storepilot.engine.config.OnUnsupported
import io.github.muhammedelsami.storepilot.engine.config.StoreSettings
import java.nio.file.Path
import java.util.Locale

/** Lint rules (docs/design.md §4). Each one only warns and can be turned off with `aso.disable`. */
enum class LintRule(val id: String) {
    TITLE_IN_SHORT_DESCRIPTION("title-in-short-description"),
    TRAILING_WHITESPACE("trailing-whitespace"),
    EMPTY_LOCALE("empty-locale"),
    MIXED_ORIENTATION("mixed-orientation"),
    IMAGE_ALPHA("image-alpha"),
}

/** The text a store gets for one field. [fallbackFrom] is set when it comes from the default language. */
data class EffectiveText(val value: String, val file: Path, val fallbackFrom: LocaleTag? = null)

enum class Coverage { PRESENT, FALLBACK, MISSING }

/** One row of the locale coverage table. */
data class CoverageRow(
    val locale: LocaleTag,
    val text: Map<ListingField, Coverage>,
    /** Number of images per type. */
    val graphics: Map<GraphicType, Int>,
)

/** The listing as a store gets it: fallbacks applied, unsupported and unreadable parts left out. */
data class PreparedListing(
    val details: AppDetails?,
    val text: Map<LocaleTag, Map<ListingField, EffectiveText>>,
    /** Empty when graphics are turned off. */
    val graphics: Map<LocaleTag, Map<GraphicType, List<LocalImage>>>,
    val coverage: List<CoverageRow>,
)

class ListingValidation(val listing: PreparedListing, val problems: List<Problem>) {
    val hasErrors: Boolean
        get() = problems.any { it.isError }
}

/** Checks a listing against a store's rules and the lint rules. Needs no network. */
class ListingValidator(
    private val rules: ListingRules,
    private val storeName: String,
    private val settings: StoreSettings,
) {
    private val problems = mutableListOf<Problem>()

    fun validate(read: ListingReader.Result): ListingValidation {
        problems += read.problems
        val local = read.listing
        val fallbackSource = fallbackSource(local)

        val text = local.locales.mapValues { (locale, listing) ->
            val own = listing.text.mapValues { (field, value) -> EffectiveText(value, listing.textFile(field)) }
            val fallback = fallbackSource?.takeIf { it.first != locale }?.second.orEmpty()
                .filterKeys { it !in own }
                .mapValues { it.value.copy(fallbackFrom = fallbackSource!!.first) }
            checkText(locale, own)
            (own + fallback).toSortedMap().filterKeys { supportsField(it, listing.textFile(it)) }
        }
        val graphics = if (settings.listing.graphics) {
            local.locales.mapValues { (_, listing) ->
                listing.graphics.filter { (type, images) -> checkGraphics(type, images, listing.dir) }
            }
        } else {
            emptyMap()
        }
        for ((locale, listing) in local.locales) {
            if (listing.text.isEmpty() && listing.graphics.isEmpty()) {
                lint(LintRule.EMPTY_LOCALE, "The directory for $locale is empty.", listing.dir)
            }
        }
        local.details?.contactEmail?.let { email ->
            if (!EMAIL.matches(email)) problems += Problem.error("'$email' is not an email address.", "details.yml")
        }

        val coverage = local.locales.map { (locale, listing) ->
            CoverageRow(
                locale,
                ListingField.entries.associateWith { field ->
                    val value = text.getValue(locale)[field]
                    when {
                        value == null -> Coverage.MISSING
                        value.fallbackFrom != null -> Coverage.FALLBACK
                        else -> Coverage.PRESENT
                    }
                },
                GraphicType.entries.associateWith { listing.graphics[it]?.size ?: 0 },
            )
        }
        return ListingValidation(PreparedListing(local.details, text, graphics, coverage), applyAsoSettings())
    }

    /** The default language and its text, when fallback is on. */
    private fun fallbackSource(local: LocalListing): Pair<LocaleTag, Map<ListingField, EffectiveText>>? {
        if (!settings.listing.fallbackToDefaultLanguage) return null
        val default = local.details?.defaultLanguage
        if (default == null) {
            problems += Problem.error("fallbackToDefaultLanguage needs default-language in details.yml.")
            return null
        }
        val listing = local.locales[default]
        if (listing == null) {
            problems += Problem.error("fallbackToDefaultLanguage is on, but there is no listing directory for $default.")
            return null
        }
        return default to listing.text.mapValues { (field, value) -> EffectiveText(value, listing.textFile(field)) }
    }

    private fun checkText(locale: LocaleTag, text: Map<ListingField, EffectiveText>) {
        for ((field, value) in text) {
            if (field !in rules.textLimits) continue
            val limit = rules.textLimits[field]
            val length = value.value.codePointCount(0, value.value.length)
            if (limit != null && length > limit) {
                problems += Problem.error("${label(field)} has $length characters; $storeName allows $limit.", value.file.toString())
            }
            if (value.value.lines().any { it.endsWith(' ') || it.endsWith('\t') }) {
                lint(LintRule.TRAILING_WHITESPACE, "A line ends with whitespace.", value.file)
            }
        }
        val pattern = rules.videoUrlPattern
        val video = text[ListingField.VIDEO_URL]?.takeIf { ListingField.VIDEO_URL in rules.textLimits }
        if (pattern != null && video != null && !pattern.matches(video.value.trim())) {
            problems += Problem.error(rules.videoUrlHint ?: "The video URL is not accepted by $storeName.", video.file.toString())
        }
        val title = text[ListingField.TITLE]?.value?.trim()
        val short = text[ListingField.SHORT_DESCRIPTION]
        if (!title.isNullOrEmpty() && short != null && short.value.contains(title, ignoreCase = true)) {
            lint(LintRule.TITLE_IN_SHORT_DESCRIPTION, "The short description repeats the title of $locale.", short.file)
        }
    }

    private fun supportsField(field: ListingField, file: Path): Boolean =
        field in rules.textLimits || unsupported("the ${label(field)} field", file)

    /** Returns false when the images of [type] cannot be pushed. */
    private fun checkGraphics(type: GraphicType, images: List<LocalImage>, localeDir: Path): Boolean {
        val source = localeDir.resolve(ListingReader.GRAPHICS_DIR).resolve(type.fileName)
        val rule = rules.graphics[type] ?: return unsupported("${type.fileName} images", source)
        if (images.size > rule.maxCount) {
            problems += Problem.error("$storeName allows at most ${rule.maxCount} ${type.fileName} images, got ${images.size}.", source.toString())
        }
        if (images.isNotEmpty() && images.size < rule.minCount) {
            problems += Problem.error("$storeName needs at least ${rule.minCount} ${type.fileName} images, got ${images.size}.", source.toString())
        }
        images.forEach { checkImage(type, rule, it) }
        val landscape = images.count { it.info.width > it.info.height }
        val portrait = images.count { it.info.width < it.info.height }
        if (landscape > 0 && portrait > 0) {
            lint(LintRule.MIXED_ORIENTATION, "Portrait and landscape images are mixed.", source)
        }
        return true
    }

    private fun checkImage(type: GraphicType, rule: GraphicRule, image: LocalImage) {
        val info = image.info
        val source = image.path.toString()
        fun error(message: String) {
            problems += Problem.error(message, source)
        }
        val size = "${info.width} × ${info.height} px"
        val longSide = maxOf(info.width, info.height)
        val shortSide = minOf(info.width, info.height)
        if (info.format !in rule.formats) {
            error("$storeName accepts ${rule.formats.joinToString(" or ")} for ${type.fileName}, got ${info.format}.")
        }
        if (rule.width != null && rule.height != null && (info.width != rule.width || info.height != rule.height)) {
            error("${type.fileName} must be ${rule.width} × ${rule.height} px, got $size.")
        }
        val minSide = rule.minSide
        val maxSide = rule.maxSide
        if (minSide != null && shortSide < minSide) error("Sides must be at least $minSide px, got $size.")
        if (maxSide != null && longSide > maxSide) error("Sides must be at most $maxSide px, got $size.")
        val maxRatio = rule.maxAspectRatio
        if (maxRatio != null && shortSide > 0 && longSide.toDouble() / shortSide > maxRatio + 1e-9) {
            error(
                if (maxRatio == 1.0) {
                    "${type.fileName} must be square, got $size."
                } else {
                    "The long side can be at most ${format(maxRatio)} times the short side, got $size."
                },
            )
        }
        val maxBytes = rule.maxBytes
        if (maxBytes != null && info.size > maxBytes) {
            error("The file is ${kilobytes(info.size)}; $storeName allows at most ${kilobytes(maxBytes)}.")
        }
        if (info.hasAlpha && !rule.alphaAllowed) {
            lint(LintRule.IMAGE_ALPHA, "The image has an alpha channel; $storeName asks for ${type.fileName} without one.", image.path)
        }
    }

    /** Reports a gap in store support. Returns false, meaning: skip it. */
    private fun unsupported(what: String, source: Path): Boolean {
        problems += when (settings.onUnsupported) {
            OnUnsupported.FAIL -> Problem.error(
                "$storeName does not support $what. Set onUnsupported to warn to skip it for this store.",
                source.toString(),
            )
            OnUnsupported.WARN -> Problem.warning("$storeName does not support $what, so it is skipped.", source.toString())
        }
        return false
    }

    private fun lint(rule: LintRule, message: String, source: Path) {
        problems += Problem.warning(message, source.toString(), rule.id)
    }

    private fun applyAsoSettings(): List<Problem> =
        problems
            .filter { it.rule == null || it.rule !in settings.aso.disabledRules }
            .map { if (it.rule != null && settings.aso.warningsAsErrors) it.copy(severity = Problem.Severity.ERROR) else it }

    private companion object {
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun label(field: ListingField) = field.fileName.removeSuffix(".txt")

        fun format(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

        fun kilobytes(bytes: Long) = String.format(Locale.ROOT, "%d KB", (bytes + 1023) / 1024)
    }
}
