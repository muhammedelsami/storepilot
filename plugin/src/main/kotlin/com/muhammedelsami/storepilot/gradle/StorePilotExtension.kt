package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/**
 * The `storepilot { }` block (docs/design.md §5). Values set here are defaults for every store block;
 * a store block such as [googlePlay] can override them.
 */
abstract class StorePilotExtension @Inject constructor(objects: ObjectFactory) {
    /** Default: `store` in the project directory. */
    abstract val metadataDir: DirectoryProperty

    /** Default: `internal`. */
    abstract val track: Property<String>

    /** Fraction of users, above 0.0 and at most 1.0. Default: 1.0. */
    abstract val rollout: Property<Double>

    /** What `publish<Variant>` uploads. Default: [ArtifactType.BUNDLE]. */
    abstract val artifactType: Property<ArtifactType>

    /** Default: [OnUnsupported.FAIL]. */
    abstract val onUnsupported: Property<OnUnsupported>

    /** Missing listing text takes the default language's text. Default: false. */
    abstract val fallbackToDefaultLanguage: Property<Boolean>

    /** The app's package name. Android projects take it from the variant's application ID. */
    abstract val packageName: Property<String>

    /** The file to upload in a project without the Android plugin. */
    abstract val artifact: RegularFileProperty

    /** Android variants that get tasks. Default: every non-debuggable variant. */
    abstract val variants: SetProperty<String>

    fun variants(vararg names: String) {
        variants.addAll(*names)
    }

    val listing: ListingExtension = objects.newInstance(ListingExtension::class.java)

    fun listing(action: Action<ListingExtension>) = action.execute(listing)

    val aso: AsoExtension = objects.newInstance(AsoExtension::class.java)

    fun aso(action: Action<AsoExtension>) = action.execute(aso)

    val googlePlay: GooglePlayExtension = objects.newInstance(GooglePlayExtension::class.java)

    fun googlePlay(action: Action<GooglePlayExtension>) = action.execute(googlePlay)
}

abstract class ListingExtension {
    /** False pushes text only. Default: true. */
    abstract val graphics: Property<Boolean>

    /** True: the store's images of a type become the local ones. False: only new images are added. Default: true. */
    abstract val replaceScreenshots: Property<Boolean>
}

abstract class AsoExtension {
    /** Lint rule IDs to turn off (docs/design.md §4). */
    abstract val disabledRules: SetProperty<String>

    abstract val warningsAsErrors: Property<Boolean>

    fun disable(vararg ruleIds: String) {
        disabledRules.addAll(*ruleIds)
    }
}

/**
 * The `googlePlay { }` block. Credentials only take a [Provider] or a file, so that a key cannot be
 * written into the build script: `serviceAccountJson = providers.environmentVariable("PLAY_KEY")`.
 * Without either, `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` and Application Default Credentials
 * are used.
 */
abstract class GooglePlayExtension @Inject constructor(objects: ObjectFactory) {
    private val serviceAccountJsonValue = objects.property(String::class.java)

    /** The service account key's content. */
    var serviceAccountJson: Provider<String>
        get() = serviceAccountJsonValue
        set(value) {
            serviceAccountJsonValue.set(value)
        }

    abstract val serviceAccountFile: RegularFileProperty

    abstract val track: Property<String>

    abstract val rollout: Property<Double>

    /** Default: from the rollout; below 1.0 is IN_PROGRESS, 1.0 is COMPLETED. */
    abstract val releaseStatus: Property<ReleaseStatus>

    /** 0 to 5, for new releases. */
    abstract val inAppUpdatePriority: Property<Int>

    abstract val changesNotSentForReview: Property<Boolean>
}
