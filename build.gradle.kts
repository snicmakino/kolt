plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "com.github.snicmakino"

// Canonical kotlinc version pin for the whole project. Sister constants live in
// `src/nativeMain/kotlin/kolt/cli/BundledKotlinVersion.kt` (native client),
// `kolt-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt` (daemon), and four
// kotlinc/BTA artifact coordinates in kolt-compiler-daemon's build scripts.
// ADR 0019 §1 requires all six to move in lockstep with this value. They are
// hand-pinned rather than generated so the self-host path (`kolt.kexe build`
// driven by `kolt.toml`) compiles without a prior Gradle step populating
// `build/generated/`; `verifyDaemonKotlinVersion` asserts the sync at build
// time. #138 will collapse the manual-sync requirement.
val daemonKotlinVersion = "2.3.20"

repositories {
    mavenCentral()
}

val verifyDaemonKotlinVersion = tasks.register("verifyDaemonKotlinVersion") {
    group = "verification"
    description = "Fails the build if any daemon-side kotlin version pin drifts from root daemonKotlinVersion."
    val nativeBundled = layout.projectDirectory.file(
        "src/nativeMain/kotlin/kolt/cli/BundledKotlinVersion.kt",
    )
    val daemonMain = layout.projectDirectory.file(
        "kolt-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt",
    )
    val daemonBuildScript = layout.projectDirectory.file(
        "kolt-compiler-daemon/build.gradle.kts",
    )
    val icBuildScript = layout.projectDirectory.file(
        "kolt-compiler-daemon/ic/build.gradle.kts",
    )
    val expected = daemonKotlinVersion
    inputs.file(nativeBundled)
    inputs.file(daemonMain)
    inputs.file(daemonBuildScript)
    inputs.file(icBuildScript)
    inputs.property("expected", expected)
    doLast {
        data class Pin(val label: String, val file: java.io.File, val regex: Regex, val fixHint: String)

        val checks = listOf(
            Pin(
                label = "src/nativeMain/.../BundledKotlinVersion.kt `BUNDLED_DAEMON_KOTLIN_VERSION`",
                file = nativeBundled.asFile,
                regex = Regex(
                    """const\s+val\s+BUNDLED_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)"""",
                ),
                fixHint = "keep the declaration as `internal const val BUNDLED_DAEMON_KOTLIN_VERSION: String = \"<version>\"`",
            ),
            Pin(
                label = "kolt-compiler-daemon Main.kt `KOLT_DAEMON_KOTLIN_VERSION`",
                file = daemonMain.asFile,
                regex = Regex(
                    """const\s+val\s+KOLT_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)"""",
                ),
                fixHint = "keep the declaration as `internal const val KOLT_DAEMON_KOTLIN_VERSION: String = \"<version>\"`",
            ),
            // Anchor on the closing `"` of the Gradle string literal so
            // the version in a comment line like
            // `// kotlin-build-tools-impl:2.3.20 for the daemon` does not
            // satisfy the match (the regex's `\"` eats the close-quote of
            // the coordinate literal, which the comment never has).
            Pin(
                label = "kolt-compiler-daemon/build.gradle.kts `kotlin-compiler-embeddable`",
                file = daemonBuildScript.asFile,
                regex = Regex("kotlin-compiler-embeddable:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-compiler-embeddable:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-compiler-daemon/build.gradle.kts `kotlin-build-tools-impl`",
                file = daemonBuildScript.asFile,
                regex = Regex("kotlin-build-tools-impl:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-build-tools-impl:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-compiler-daemon/ic/build.gradle.kts `kotlin-build-tools-api`",
                file = icBuildScript.asFile,
                regex = Regex("kotlin-build-tools-api:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-build-tools-api:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-compiler-daemon/ic/build.gradle.kts `kotlin-build-tools-impl`",
                file = icBuildScript.asFile,
                regex = Regex("kotlin-build-tools-impl:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-build-tools-impl:<version>` with a literal version",
            ),
        )

        val drift = mutableListOf<String>()
        for (check in checks) {
            val text = check.file.readText()
            val match = check.regex.find(text)
                ?: throw GradleException(
                    "Could not locate ${check.label} in ${check.file}. " +
                        "Drift guard requires: ${check.fixHint}.",
                )
            val actual = match.groupValues[1]
            if (actual != expected) {
                drift += "  - ${check.label}: \"$actual\" (expected \"$expected\")"
            }
        }

        if (drift.isNotEmpty()) {
            throw GradleException(
                "daemon kotlin version drift from root `daemonKotlinVersion` = \"$expected\":\n" +
                    drift.joinToString("\n") +
                    "\nUpdate all pins together. #138 will remove this manual-sync requirement.",
            )
        }
    }
}

kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val libcurl by creating
            }
        }
        binaries {
            executable {
                entryPoint = "kolt.cli.main"
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.michael-bull.kotlin-result:kotlin-result:2.3.1")
                implementation("org.kotlincrypto.hash:sha2-256:0.2.7")
                implementation("com.akuleshov7:ktoml-core:0.7.1")
            }
        }
        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "8.12"
    distributionType = Wrapper.DistributionType.BIN
}

// Keep the existing "./gradlew build rebuilds the daemon fat jar too" DX after
// the kolt-compiler-daemon split into an independent Gradle build. Without this
// wiring, the dev-fallback path in DaemonJarResolver.kt could pick up a stale
// jar produced before a local protocol change.
tasks.named("build") {
    dependsOn(gradle.includedBuild("kolt-compiler-daemon").task(":build"))
}

// Propagate `check` to the included build as well, so that ADR 0016's
// `verifyShadowJar` regression guard (which rejects kotlin-compiler-embeddable
// being baked into the fat jar) stays on the root `./gradlew check` path.
// Without this, the silent-fallback footgun that ADR 0018 flags loses its
// first line of defense.
tasks.named("check") {
    dependsOn(gradle.includedBuild("kolt-compiler-daemon").task(":check"))
    dependsOn(verifyDaemonKotlinVersion)
}

// Same rationale for `clean`: without this wiring, `./gradlew clean` at the
// root leaves the daemon fat jar behind, and the dev-fallback path in
// DaemonJarResolver.kt can keep resolving it across local protocol changes
// until someone manually wipes kolt-compiler-daemon/build/.
tasks.named("clean") {
    dependsOn(gradle.includedBuild("kolt-compiler-daemon").task(":clean"))
}

// Force `stageBtaImplJars` before any `linuxX64Test` run. Without this
// wiring, `./gradlew linuxX64Test` in isolation leaves
// `kolt-compiler-daemon/build/bta-impl-jars/` un-populated, which means
// `BtaImplJarResolver`'s dev-fallback path silently warns `BtaImplJarsMissing`
// on the first dogfood `kolt build --daemon`. `./gradlew build` covers it
// via the daemon `:build` dependency above, but test-only runs do not. This
// closes B-2a review carryover #7.
tasks.named("linuxX64Test") {
    dependsOn(gradle.includedBuild("kolt-compiler-daemon").task(":stageBtaImplJars"))
}
