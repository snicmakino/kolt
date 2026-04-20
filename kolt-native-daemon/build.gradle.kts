import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
}

dependencies {
    // kotlin-native-compiler-embeddable.jar is NOT declared here. ADR 0024 §8:
    // the native daemon receives its path on the CLI via `--konanc-jar <path>`
    // and loads it through a dedicated URLClassLoader at request-handling time.
    // Bundling it would defeat the reflective-load contract and drag a ~60MB
    // artifact into the fat jar. `verifyShadowJar` below enforces the exclusion.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kolt.nativedaemon.MainKt")
}

tasks.shadowJar {
    // Pin Main-Class explicitly — mirrors the rationale in
    // kolt-compiler-daemon/build.gradle.kts: a future removal of the
    // `application` plugin would otherwise produce a jar that silently
    // fails at `java -jar` time instead of at build time.
    manifest {
        attributes("Main-Class" to "kolt.nativedaemon.MainKt")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Regression guard parallel to the one in kolt-compiler-daemon/build.gradle.kts:
// ADR 0024 §8 requires kotlin-native-compiler-embeddable.jar to be passed via
// --konanc-jar and loaded reflectively — it MUST NOT be baked into the fat jar.
// kotlinx-serialization-json and kotlin-result must be bundled since the native
// client spawns the daemon with a bare `java -jar` and there is no other source
// for those classes.
tasks.register("verifyShadowJar") {
    group = "verification"
    description = "Verifies the native daemon fat jar bundles required libs and excludes kotlin-native-compiler-embeddable."
    dependsOn(tasks.shadowJar)
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().map { entry -> entry.name }.toList()
        }
        // Markers unique to kotlin-native-compiler-embeddable: K2Native and the
        // `cli/bc` package it sits in are native-specific; `cli/common` is shared
        // with the JVM compiler, so we anchor on the native-only subtree.
        val mustExclude = listOf(
            "org/jetbrains/kotlin/cli/bc/",
            "org/jetbrains/kotlin/cli/utilities/MainKt",
        )
        val mustInclude = listOf(
            "kotlinx/serialization/json/Json",
            "com/github/michaelbull/result/Result",
        )
        val forbidden = mustExclude.filter { prefix -> entries.any { name -> name.startsWith(prefix) } }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "shadowJar contains forbidden entries (ADR 0024 §8 violation): $forbidden. " +
                    "kotlin-native-compiler-embeddable must be passed via --konanc-jar and loaded reflectively.",
            )
        }
        val missing = mustInclude.filter { needle -> entries.none { name -> name.startsWith(needle) } }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "shadowJar is missing required entries: $missing. " +
                    "The native client spawns the daemon with `java -jar` and has no other source for these classes.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn("verifyShadowJar")
}

tasks.test {
    useJUnitPlatform()
}
