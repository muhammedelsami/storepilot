package com.muhammedelsami.storepilot.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class StorePilotPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("storepilot", StorePilotExtension::class.java)
    }
}
