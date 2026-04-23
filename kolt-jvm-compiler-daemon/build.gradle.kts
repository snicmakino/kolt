plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

repositories {
    mavenCentral()
}

val compilerHostClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":ic"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    compilerHostClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Pass the daemon-core main source root as a system property so
    // `AdapterBoundaryInvariantTest` can walk it and enforce ADR 0019
    // §3 ("daemon core must not import kotlin.buildtools.*") at test
    // time, in addition to the human-review gate.
    systemProperty(
        "kolt.daemon.coreMainSourceRoot",
        layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
    )
}
