package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.GraphicRule
import com.muhammedelsami.storepilot.api.GraphicType
import com.muhammedelsami.storepilot.api.ImageFormat
import com.muhammedelsami.storepilot.api.ListingField
import com.muhammedelsami.storepilot.api.ListingRules
import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.config.AsoSettings
import com.muhammedelsami.storepilot.engine.config.ListingSettings
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import com.muhammedelsami.storepilot.engine.config.StoreSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ListingValidatorTest {
    @TempDir
    lateinit var dir: Path

    private val rules = ListingRules(
        textLimits = mapOf(ListingField.TITLE to 30, ListingField.SHORT_DESCRIPTION to 80, ListingField.FULL_DESCRIPTION to 4000),
        releaseNotesLimit = 500,
        graphics = mapOf(
            GraphicType.ICON to GraphicRule(setOf(ImageFormat.PNG), width = 512, height = 512, maxBytes = 1024),
            GraphicType.PHONE_SCREENSHOTS to GraphicRule(
                setOf(ImageFormat.PNG, ImageFormat.JPEG),
                minCount = 2,
                maxCount = 3,
                minSide = 320,
                maxSide = 3840,
                maxAspectRatio = 2.0,
                alphaAllowed = false,
            ),
        ),
        videoUrlPattern = Regex("https://www\\.youtube\\.com/watch\\?v=[\\w-]+"),
        videoUrlHint = "Use a YouTube video URL without extra parameters.",
    )

    @Test
    fun `reads the metadata directory`() {
        write("details.yml", "default-language: en-US\ncontact-phone: 0555\n")
        write("listing/en-US/title.txt", "My App\r\n\n")
        write("listing/en-US/full-description.txt", "Line one\nLine two\n")
        image("listing/en-US/graphics/phone-screenshots/02.png", TestImages.png(1080, 1920, seed = 2))
        image("listing/en-US/graphics/phone-screenshots/01.jpg", TestImages.jpeg(1080, 1920))
        image("listing/en-US/graphics/icon.png", TestImages.png(512, 512))

        val result = ListingReader.read(dir)

        assertEquals(emptyList(), result.problems)
        assertEquals("0555", result.listing.details?.contactPhone)
        val enUs = result.listing.locales.getValue(LocaleTag("en-US"))
        assertEquals(mapOf(ListingField.TITLE to "My App", ListingField.FULL_DESCRIPTION to "Line one\nLine two"), enUs.text)
        assertEquals(listOf("01.jpg", "02.png"), enUs.graphics.getValue(GraphicType.PHONE_SCREENSHOTS).map { it.path.fileName.toString() })
    }

    @Test
    fun `reports entries it does not know`() {
        write("listing/en_US/title.txt", "x")
        write("listing/de-DE/name.txt", "x")
        image("listing/de-DE/graphics/icon.png", TestImages.png(512, 512))
        image("listing/de-DE/graphics/icon.jpg", TestImages.jpeg(512, 512))
        write("listing/de-DE/graphics/phone-screenshots/01.gif", "x")
        write("listing/de-DE/graphics/banner.png", "x")
        write("listing/de-DE/graphics/tv-banner.png", "not an image")

        val problems = ListingReader.read(dir).problems.map { relative(it.source) to it.message.substringBefore('.') }

        assertEquals(
            listOf(
                "listing/de-DE/graphics/banner.png" to "Unknown graphic",
                "listing/de-DE/graphics/icon.png" to "More than one file for icon",
                "listing/de-DE/graphics/phone-screenshots/01.gif" to "Screenshots must be ",
                "listing/de-DE/graphics/tv-banner.png" to "Not a PNG or JPEG image",
                "listing/de-DE/name.txt" to "Unknown entry",
                "listing/en_US" to "'en_US' is not a BCP-47 language tag, for example 'en-US'",
            ),
            problems.sortedBy { it.first },
        )
    }

    @Test
    fun `checks text limits in code points and the video URL`() {
        write("listing/en-US/title.txt", "🚀".repeat(30))
        write("listing/en-US/short-description.txt", "x".repeat(81))

        val problems = validate().problems.map { it.toString().replace("$dir/", "") }

        assertEquals(
            listOf("listing/en-US/short-description.txt: error: short-description has 81 characters; Fake Store allows 80."),
            problems,
        )
    }

    @Test
    fun `checks images against the store rules`() {
        image("listing/en-US/graphics/icon.png", TestImages.png(500, 500))
        image("listing/en-US/graphics/phone-screenshots/01.png", TestImages.png(300, 1000, alpha = true))

        val problems = validate().problems.map { relative(it.source) to it.message }

        assertEquals(
            listOf(
                "listing/en-US/graphics/icon.png" to "icon must be 512 × 512 px, got 500 × 500 px.",
                "listing/en-US/graphics/phone-screenshots" to "Fake Store needs at least 2 phone-screenshots images, got 1.",
                "listing/en-US/graphics/phone-screenshots/01.png" to "Sides must be at least 320 px, got 300 × 1000 px.",
                "listing/en-US/graphics/phone-screenshots/01.png" to "The long side can be at most 2 times the short side, got 300 × 1000 px.",
                "listing/en-US/graphics/phone-screenshots/01.png" to
                    "The image has an alpha channel; Fake Store asks for phone-screenshots without one.",
            ),
            problems,
        )
    }

    @Test
    fun `lint rules warn and can be turned off or made errors`() {
        write("listing/en-US/title.txt", "Pilot")
        write("listing/en-US/short-description.txt", "Pilot for stores \nand more")
        write("listing/de-DE/.keep", "")
        image("listing/en-US/graphics/phone-screenshots/01.png", TestImages.png(1080, 1920))
        image("listing/en-US/graphics/phone-screenshots/02.png", TestImages.png(1920, 1080))

        val rules = validate().problems.map { it.rule to it.severity.name }
        assertEquals(
            listOf(
                "trailing-whitespace" to "WARNING",
                "title-in-short-description" to "WARNING",
                "mixed-orientation" to "WARNING",
                "empty-locale" to "WARNING",
            ),
            rules,
        )

        val tuned = validate(aso = AsoSettings(setOf("empty-locale", "mixed-orientation"), warningsAsErrors = true)).problems
        assertEquals(
            listOf("trailing-whitespace" to "ERROR", "title-in-short-description" to "ERROR"),
            tuned.map { it.rule to it.severity.name },
        )
    }

    @Test
    fun `fallback fills missing fields from the default language`() {
        write("details.yml", "default-language: en-US\n")
        write("listing/en-US/title.txt", "Pilot")
        write("listing/en-US/short-description.txt", "Short")
        write("listing/tr-TR/title.txt", "Pilot TR")

        val validation = validate(listing = ListingSettings(fallbackToDefaultLanguage = true))

        assertEquals(emptyList(), validation.problems)
        val trText = validation.listing.text.getValue(LocaleTag("tr-TR"))
        assertEquals("Pilot TR", trText.getValue(ListingField.TITLE).value)
        assertEquals("Short", trText.getValue(ListingField.SHORT_DESCRIPTION).value)
        assertEquals(LocaleTag("en-US"), trText.getValue(ListingField.SHORT_DESCRIPTION).fallbackFrom)
        val coverage = validation.listing.coverage.single { it.locale == LocaleTag("tr-TR") }.text
        assertEquals(Coverage.PRESENT, coverage[ListingField.TITLE])
        assertEquals(Coverage.FALLBACK, coverage[ListingField.SHORT_DESCRIPTION])
        assertEquals(Coverage.MISSING, coverage[ListingField.FULL_DESCRIPTION])
    }

    @Test
    fun `fallback needs a default language`() {
        write("listing/en-US/title.txt", "Pilot")

        val problems = validate(listing = ListingSettings(fallbackToDefaultLanguage = true)).problems

        assertEquals(listOf("fallbackToDefaultLanguage needs default-language in details.yml."), problems.map { it.message })
    }

    @Test
    fun `unsupported parts fail or are skipped`() {
        write("listing/en-US/video-url.txt", "https://youtu.be/x")
        image("listing/en-US/graphics/tv-banner.png", TestImages.png(1280, 720))

        val failed = validate().problems.map { it.severity.name to it.message }
        assertEquals(
            listOf(
                "ERROR" to "Fake Store does not support the video-url field. Set onUnsupported to warn to skip it for this store.",
                "ERROR" to "Fake Store does not support tv-banner images. Set onUnsupported to warn to skip it for this store.",
            ),
            failed,
        )

        val skipped = validate(onUnsupported = OnUnsupported.WARN)
        assertEquals(listOf("WARNING", "WARNING"), skipped.problems.map { it.severity.name })
        assertEquals(emptyMap(), skipped.listing.text.getValue(LocaleTag("en-US")))
        assertEquals(emptyMap(), skipped.listing.graphics.getValue(LocaleTag("en-US")))
    }

    private fun validate(
        listing: ListingSettings = ListingSettings(),
        aso: AsoSettings = AsoSettings(),
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
    ): ListingValidation {
        val settings = StoreSettings(
            StoreId("fake"), "com.example.app", dir, Track.INTERNAL, Rollout.FULL, null, onUnsupported, emptyMap(), listing, aso,
        )
        return ListingValidator(rules, "Fake Store", settings).validate(ListingReader.read(dir))
    }

    private fun write(path: String, text: String) {
        dir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun image(path: String, bytes: ByteArray) {
        dir.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
    }

    private fun relative(source: String?) = dir.relativize(Path.of(source!!)).invariantSeparatorsPathString
}
