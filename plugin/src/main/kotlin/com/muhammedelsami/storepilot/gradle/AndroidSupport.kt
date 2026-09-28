package com.muhammedelsami.storepilot.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.muhammedelsami.storepilot.api.ArtifactType
import org.gradle.api.Project

/**
 * Release tasks for the variants of an Android application. Only loaded when the Android application
 * plugin is applied, so projects without it never load AGP classes. Uses the variant API that AGP 8.5
 * through 9.x share.
 */
internal object AndroidSupport {
    fun register(project: Project, extension: StorePilotExtension, plugin: StorePilotPlugin) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            val selected = extension.variants.get()
            val included = if (selected.isEmpty()) !variant.debuggable else variant.name in selected
            if (!included) return@onVariants

            val applicationId = variant.applicationId
            project.tasks.withType(StorePilotTask::class.java).configureEach { it.applicationIds.add(applicationId) }
            plugin.registerVariantReleaseTasks(project, variant.name) { tasks ->
                val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
                val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
                val packageName = extension.packageName.orElse(applicationId)
                tasks.bundle.configure {
                    it.artifact.set(bundle)
                    it.packageName.set(packageName)
                }
                tasks.apk.configure {
                    it.apkDirectory.set(apkDirectory)
                    it.packageName.set(packageName)
                }
                tasks.aggregate.configure {
                    if (extension.artifactType.get() == ArtifactType.APK) {
                        it.apkDirectory.set(apkDirectory)
                    } else {
                        it.artifact.set(bundle)
                    }
                    it.packageName.set(packageName)
                }
            }
        }
    }
}
