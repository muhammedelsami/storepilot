plugins {
    id("storepilot.embedded-kotlin")
    `java-gradle-plugin`
}

// Lowest Gradle version the plugin supports. The functional tests run against it and the current one.
val minimumGradleVersion = "8.10"

dependencies {
    implementation(project(":core:engine"))
    runtimeOnly(project(":core:stores:google-play"))
}

gradlePlugin {
    plugins {
        create("storepilot") {
            id = "com.muhammedelsami.storepilot"
            implementationClass = "com.muhammedelsami.storepilot.gradle.StorePilotPlugin"
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest")

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    systemProperty("storepilot.minimumGradleVersion", minimumGradleVersion)
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.check {
    dependsOn(functionalTest)
}
