// A library for Maven Central: the core modules that the Gradle plugin depends on (docs/design.md §9).
// Each module sets its artifact ID, name, and description in mavenPublishing { }.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `java-library`
    id("storepilot.published")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    // A deployment waits in the Central Portal until it is released by hand (RELEASING.md).
    publishToMavenCentral(automaticRelease = false)
    // The release workflow has the key; other builds publish unsigned to the local test repository.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
