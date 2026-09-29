// Loads the build's plugins once, in the root project. Without this, projects that add their own
// plugins (shadow in :cli, plugin-publish in :plugin) each get a separate copy of the Kotlin Gradle
// plugin, which Kotlin reports as "loaded multiple times".
plugins {
    id("storepilot.kotlin-jvm") apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.plugin.publish) apply false
    alias(libs.plugins.plugin.compatibility) apply false
}
