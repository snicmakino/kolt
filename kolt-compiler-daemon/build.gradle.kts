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

val compilerHostClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
// Resolvable configuration holding kotlin-build-tools-impl:2.3.20 for the daemon. Per
// ADR 0019 §3, -impl must be loaded through a child URLClassLoader parented by
// SharedApiClassesClassLoader so daemon-core classes never see @ExperimentalBuildToolsApi
// types transitively. Shipping -impl as a separate on-disk classpath (not bundled into
// the fat jar) parallels how kotlin-compiler-embeddable is passed to the daemon via
// --compiler-jars; verifyShadowJar below enforces the "not bundled" half.
val btaImplClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":ic"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    compilerHostClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    btaImplClasspath("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kolt.daemon.MainKt")
}

tasks.shadowJar {
    // Pin Main-Class explicitly rather than relying on Shadow's auto-detection
    // from the application plugin — if someone removes `application` later, a
    // shadowJar without a manifest entry would silently fail at `java -jar`
    // time instead of at build time.
    manifest {
        attributes("Main-Class" to "kolt.daemon.MainKt")
    }
    // Reproducible output: strip timestamps and sort entries so two builds of
    // the same source tree produce byte-identical jars. Cheap insurance for
    // future release reproducibility work (no current requirement).
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Regression guard for ADR 0016: the daemon must receive kotlin-compiler-embeddable
// via --compiler-jars at runtime, so it MUST NOT be baked into the fat jar. It is
// also load-bearing that kotlinx-serialization-json and kotlin-result ARE bundled,
// since the native client spawns the daemon with a bare `java -jar` and there is
// no other source of those classes. A direct `implementation("...compiler-embeddable")`
// by a future contributor would silently break the reflective-load contract — this
// task fails the build instead.
tasks.register("verifyShadowJar") {
    group = "verification"
    description = "Verifies the daemon fat jar contains required libs and excludes kotlin-compiler-embeddable."
    dependsOn(tasks.shadowJar)
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().map { entry -> entry.name }.toList()
        }
        val mustExclude = listOf(
            "org/jetbrains/kotlin/cli/common/", // kotlin-compiler-embeddable marker
            "org/jetbrains/kotlin/config/CompilerConfiguration",
            // ADR 0019 §3: kotlin-build-tools-impl must ship as a separate on-disk
            // classpath and load through a child URLClassLoader. Bundling it into the
            // fat jar would defeat the classloader isolation that keeps daemon-core
            // classes free of @ExperimentalBuildToolsApi types. We use sub-packages
            // that are unique to -impl as the marker: -api legitimately ships
            // org/jetbrains/kotlin/buildtools/internal/ClassLoaderUtilsKt (a helper
            // behind SharedApiClassesClassLoader), so we match on the -impl-only
            // subtrees instead of the shared parent.
            "org/jetbrains/kotlin/buildtools/internal/jvm/",
            "org/jetbrains/kotlin/buildtools/internal/scripting/",
        )
        val mustInclude = listOf(
            "kotlinx/serialization/json/Json",
            "com/github/michaelbull/result/Result",
            // ADR 0019 §3: the -api classes are the bridge between daemon core and the
            // BtaIncrementalCompiler adapter. They must sit on the daemon JVM's main
            // classpath so SharedApiClassesClassLoader can expose them to the child
            // URLClassLoader that loads -impl. Consumed only via the :ic subproject.
            "org/jetbrains/kotlin/buildtools/api/KotlinToolchains",
        )
        val forbidden = mustExclude.filter { prefix -> entries.any { name -> name.startsWith(prefix) } }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "shadowJar contains forbidden entries (ADR 0016 violation): $forbidden. " +
                    "kotlin-compiler-embeddable must stay on compilerHostClasspath and be loaded reflectively."
            )
        }
        val missing = mustInclude.filter { needle -> entries.none { name -> name.startsWith(needle) } }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "shadowJar is missing required entries: $missing. " +
                    "The native client spawns the daemon with `java -jar` and has no other source for these classes."
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
