package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.api.ArtifactType
import com.muhammedelsami.storepilot.engine.config.OnUnsupported
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StorePilotPluginTest {
    @Test
    fun `registers the extension with defaults and the listing tasks`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.muhammedelsami.storepilot")

        val extension = project.extensions.getByType(StorePilotExtension::class.java)
        assertEquals(project.layout.projectDirectory.dir("store"), extension.metadataDir.get())
        assertEquals(ArtifactType.BUNDLE, extension.artifactType.get())
        assertEquals(OnUnsupported.FAIL, extension.onUnsupported.get())
        assertEquals(true, extension.listing.replaceScreenshots.get())
        for (name in listOf("publishListing", "publishListingText", "pullListing", "diffListing", "validateListing", "exportStorepilotConfig")) {
            assertNotNull(project.tasks.findByName(name), name)
        }
    }

    @Test
    fun `store blocks override the defaults`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.muhammedelsami.storepilot")
        val extension = project.extensions.getByType(StorePilotExtension::class.java)
        extension.track.set("testing")
        extension.rollout.set(0.5)
        extension.googlePlay.track.set("production")
        extension.googlePlay.inAppUpdatePriority.set(2)

        val task = project.tasks.getByName("publishListing") as StorePilotTask

        assertEquals("production", task.track.get())
        assertEquals(0.5, task.rollout.get())
        assertEquals(mapOf("inAppUpdatePriority" to "2"), task.storeOptions.get())
        assertEquals("google-play", task.store.get())
    }
}
