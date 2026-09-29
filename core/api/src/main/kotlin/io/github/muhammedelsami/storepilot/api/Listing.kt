package io.github.muhammedelsami.storepilot.api

/** A text field of a store listing, and its file in `store/listing/<locale>/`. */
enum class ListingField(val fileName: String) {
    TITLE("title.txt"),
    SHORT_DESCRIPTION("short-description.txt"),
    FULL_DESCRIPTION("full-description.txt"),
    VIDEO_URL("video-url.txt"),
}

/** App-level details from `store/details.yml`. A null field is not managed by StorePilot. */
data class AppDetails(
    val defaultLanguage: LocaleTag? = null,
    val contactEmail: String? = null,
    val contactWebsite: String? = null,
    val contactPhone: String? = null,
)

/**
 * A portable graphic type (docs/design.md §3.2). A single image is the file `<name>.png` (or `.jpg`)
 * in `graphics/`; a type with several images is the directory `<name>/`.
 */
enum class GraphicType(val fileName: String, val multiple: Boolean) {
    ICON("icon", false),
    FEATURE_GRAPHIC("feature-graphic", false),
    PHONE_SCREENSHOTS("phone-screenshots", true),
    TABLET_7_SCREENSHOTS("tablet-7-screenshots", true),
    TABLET_10_SCREENSHOTS("tablet-10-screenshots", true),
    TV_SCREENSHOTS("tv-screenshots", true),
    TV_BANNER("tv-banner", false),
    WEAR_SCREENSHOTS("wear-screenshots", true),
}

enum class ImageFormat(val extensions: Set<String>) {
    PNG(setOf("png")),
    JPEG(setOf("jpg", "jpeg")),
}

/** An image in the store. [sha256] is lowercase hex. */
data class RemoteImage(val id: String, val sha256: String, val url: String? = null)

/**
 * What a store accepts in a listing. Graphic types missing from [graphics] and fields missing from
 * [textLimits] are not supported by the store.
 */
data class ListingRules(
    /** Maximum length in Unicode code points; null for a supported field without a known limit. */
    val textLimits: Map<ListingField, Int?>,
    val releaseNotesLimit: Int?,
    val graphics: Map<GraphicType, GraphicRule>,
    /** A video URL must match this, when set. [videoUrlHint] explains the rule. */
    val videoUrlPattern: Regex? = null,
    val videoUrlHint: String? = null,
)

/** Rules for the images of one graphic type. Null values are not checked. */
data class GraphicRule(
    val formats: Set<ImageFormat>,
    /** Fewest images when the type has any. */
    val minCount: Int = 1,
    val maxCount: Int = 1,
    val width: Int? = null,
    val height: Int? = null,
    val minSide: Int? = null,
    val maxSide: Int? = null,
    /** Longest side divided by shortest side. 1.0 means square. */
    val maxAspectRatio: Double? = null,
    val maxBytes: Long? = null,
    /** False when the store asks for images without an alpha channel. */
    val alphaAllowed: Boolean = true,
)
