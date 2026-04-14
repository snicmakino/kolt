plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

// Classpath used to load the Build Tools API implementation at runtime.
// The spike uses SharedApiClassesClassLoader to separate API classes (on the app
// classpath) from impl classes (loaded from this configuration), matching the
// isolation policy used by Gradle's Kotlin plugin.
val buildToolsImpl: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val fixtureClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // API only — the impl is loaded reflectively at runtime from buildToolsImpl.
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20")
    buildToolsImpl("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20")
    fixtureClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("spike.ic.MainKt")
}

tasks.named<JavaExec>("run") {
    systemProperty("bta.impl.classpath", buildToolsImpl.asPath)
    systemProperty("fixture.classpath", fixtureClasspath.asPath)
}
