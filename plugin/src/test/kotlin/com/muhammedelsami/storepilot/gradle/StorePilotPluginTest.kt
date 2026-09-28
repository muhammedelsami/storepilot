package com.muhammedelsami.storepilot.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class StorePilotPluginTest {
    @Test
    fun `plugin registers the storepilot extension`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.muhammedelsami.storepilot")

        assertNotNull(project.extensions.findByType(StorePilotExtension::class.java))
    }
}
