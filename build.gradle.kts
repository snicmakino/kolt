plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "com.github.snicmakino"

// Canonical kotlinc version pin for the whole project. Sister constants live in
// `src/nativeMain/kotlin/kolt/cli/BundledKotlinVersion.kt` (native client),
// `kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt` (JVM daemon),
// `kolt-native-compiler-daemon/src/main/kotlin/kolt/nativedaemon/Main.kt` (native
// daemon, ADR 0024), and four kotlinc/BTA artifact coordinates in
// kolt-jvm-compiler-daemon's build scripts. ADR 0019 §1 requires all seven to
// move in lockstep with this value. They are hand-pinned rather than
// generated so the self-host path (`kolt.kexe build` driven by `kolt.toml`)
// compiles without a prior Gradle step populating `build/generated/`;
// `verifyDaemonKotlinVersion` asserts the sync at build time. #138 will
// collapse the manual-sync requirement.
val daemonKotlinVersion = "2.3.20"

repositories {
    mavenCentral()
}

// JDK pin (BOOTSTRAP_JDK_VERSION in src/nativeMain/.../BootstrapJdk.kt, two
// daemon kolt.toml `jdk`/`jvm_target`, three daemon build.gradle.kts
// `jvmToolchain(N)`) is also hand-synced across 5 sites. Drift surfaces as
// `UnsupportedClassVersionError` on daemon spawn (when daemon `jvm_target` >
// BOOTSTRAP) or as a wasted JDK install in CI cache. No guard yet — see #268
// for the migration plan (move both verify* tasks below + a new JDK pin guard
// to native unit tests, so the checks survive removal of Gradle from the root
// build per the daemon self-host direction in #97/#228).
val verifyDaemonKotlinVersion = tasks.register("verifyDaemonKotlinVersion") {
    group = "verification"
    description = "Fails the build if any daemon-side kotlin version pin drifts from root daemonKotlinVersion."
    val nativeBundled = layout.projectDirectory.file(
        "src/nativeMain/kotlin/kolt/cli/BundledKotlinVersion.kt",
    )
    val daemonMain = layout.projectDirectory.file(
        "kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt",
    )
    val daemonBuildScript = layout.projectDirectory.file(
        "kolt-jvm-compiler-daemon/build.gradle.kts",
    )
    val icBuildScript = layout.projectDirectory.file(
        "kolt-jvm-compiler-daemon/ic/build.gradle.kts",
    )
    val nativeDaemonMain = layout.projectDirectory.file(
        "kolt-native-compiler-daemon/src/main/kotlin/kolt/nativedaemon/Main.kt",
    )
    val expected = daemonKotlinVersion
    inputs.file(nativeBundled)
    inputs.file(daemonMain)
    inputs.file(daemonBuildScript)
    inputs.file(icBuildScript)
    inputs.file(nativeDaemonMain)
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
                label = "kolt-jvm-compiler-daemon Main.kt `KOLT_DAEMON_KOTLIN_VERSION`",
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
                label = "kolt-jvm-compiler-daemon/build.gradle.kts `kotlin-compiler-embeddable`",
                file = daemonBuildScript.asFile,
                regex = Regex("kotlin-compiler-embeddable:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-compiler-embeddable:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-jvm-compiler-daemon/ic/build.gradle.kts `kotlin-build-tools-api`",
                file = icBuildScript.asFile,
                regex = Regex("kotlin-build-tools-api:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-build-tools-api:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-jvm-compiler-daemon/ic/build.gradle.kts `kotlin-build-tools-impl`",
                file = icBuildScript.asFile,
                regex = Regex("kotlin-build-tools-impl:([^\"\\s]+)\""),
                fixHint = "pin `org.jetbrains.kotlin:kotlin-build-tools-impl:<version>` with a literal version",
            ),
            Pin(
                label = "kolt-native-compiler-daemon Main.kt `KOLT_NATIVE_DAEMON_KOTLIN_VERSION`",
                file = nativeDaemonMain.asFile,
                regex = Regex(
                    """const\s+val\s+KOLT_NATIVE_DAEMON_KOTLIN_VERSION\s*:\s*String\s*=\s*"([^"]+)"""",
                ),
                fixHint = "keep the declaration as `internal const val KOLT_NATIVE_DAEMON_KOTLIN_VERSION: String = \"<version>\"`",
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

// Per #233: daemon main-class FQN is pinned in three uncoordinated places
// (resolver constant, `scripts/assemble-dist.sh` DAEMONS map, daemon
// `kolt.toml` `[build].main` under the Kotlin file-class convention). Pre-#231
// shadowJar's Main-Class manifest attribute surfaced drift at build time;
// post-#231 a drift silently produces `ClassNotFoundException` at daemon
// spawn, which ADR 0016 §5 then hides behind the subprocess fallback. This
// task fails the build when the three pins disagree.
val verifyDaemonMainClass = tasks.register("verifyDaemonMainClass") {
    group = "verification"
    description = "Fails the build if the daemon main-class FQN drifts across resolver constant, assemble-dist.sh, or kolt.toml."
    val daemonResolver = layout.projectDirectory.file(
        "src/nativeMain/kotlin/kolt/build/daemon/DaemonJarResolver.kt",
    )
    val nativeDaemonResolver = layout.projectDirectory.file(
        "src/nativeMain/kotlin/kolt/build/nativedaemon/NativeDaemonJarResolver.kt",
    )
    val daemonToml = layout.projectDirectory.file("kolt-jvm-compiler-daemon/kolt.toml")
    val nativeDaemonToml = layout.projectDirectory.file("kolt-native-compiler-daemon/kolt.toml")
    val distScript = layout.projectDirectory.file("scripts/assemble-dist.sh")
    inputs.file(daemonResolver)
    inputs.file(nativeDaemonResolver)
    inputs.file(daemonToml)
    inputs.file(nativeDaemonToml)
    inputs.file(distScript)
    doLast {
        data class DaemonPins(
            val daemonName: String,
            val resolverFile: java.io.File,
            val resolverConst: String,
            val tomlFile: java.io.File,
        )

        // Mirror of kolt.config.jvmMainClass — keep in sync with that helper.
        // Both here and there assume the Kotlin file is `Main.kt`; this is also
        // what `scripts/assemble-dist.sh`'s DAEMONS comment documents.
        fun jvmMainClass(main: String): String {
            val prefix = main.substringBeforeLast("main")
            return "${prefix}MainKt"
        }

        fun requireMatch(regex: Regex, text: String, where: String): String =
            regex.find(text)?.groupValues?.get(1)
                ?: throw GradleException("Could not locate $where. Drift guard cannot verify.")

        val pins = listOf(
            DaemonPins(
                daemonName = "kolt-jvm-compiler-daemon",
                resolverFile = daemonResolver.asFile,
                resolverConst = "DAEMON_MAIN_CLASS",
                tomlFile = daemonToml.asFile,
            ),
            DaemonPins(
                daemonName = "kolt-native-compiler-daemon",
                resolverFile = nativeDaemonResolver.asFile,
                resolverConst = "NATIVE_DAEMON_MAIN_CLASS",
                tomlFile = nativeDaemonToml.asFile,
            ),
        )

        val distText = distScript.asFile.readText()
        val drift = mutableListOf<String>()
        for (pin in pins) {
            val resolverRegex = Regex(
                """const\s+val\s+${pin.resolverConst}\s*=\s*"([^"]+)"""",
            )
            val resolverFqn = requireMatch(
                resolverRegex,
                pin.resolverFile.readText(),
                "${pin.resolverFile.name} `${pin.resolverConst}`",
            )

            val tomlText = pin.tomlFile.readText()
            val tomlMain = requireMatch(
                Regex("""(?m)^main\s*=\s*"([^"]+)""""),
                tomlText,
                "${pin.tomlFile.parentFile.name}/kolt.toml `[build].main`",
            )
            val tomlFqn = jvmMainClass(tomlMain)

            val distRegex = Regex(""""${Regex.escape(pin.daemonName)}:([^"]+)"""")
            val distFqn = requireMatch(
                distRegex,
                distText,
                "scripts/assemble-dist.sh DAEMONS entry for ${pin.daemonName}",
            )

            val pairs = mapOf(
                "${pin.resolverFile.name} `${pin.resolverConst}`" to resolverFqn,
                "${pin.tomlFile.parentFile.name}/kolt.toml `[build].main` -> $tomlFqn" to tomlFqn,
                "scripts/assemble-dist.sh DAEMONS[${pin.daemonName}]" to distFqn,
            )
            val distinct = pairs.values.toSet()
            if (distinct.size > 1) {
                drift += "  ${pin.daemonName}:"
                pairs.forEach { (label, value) -> drift += "    - $label: \"$value\"" }
            }
        }

        if (drift.isNotEmpty()) {
            throw GradleException(
                "daemon main-class FQN drift across pinned sources:\n" +
                    drift.joinToString("\n") +
                    "\nUpdate all three sources together (resolver constant, kolt.toml `[build].main`, " +
                    "assemble-dist.sh DAEMONS entry).",
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

// Keep `./gradlew build` rebuilding the two daemons via their own `includeBuild`
// `:build`. Without this wiring, a root build could ship against a stale daemon
// thin jar across a local protocol change.
tasks.named("build") {
    dependsOn(gradle.includedBuild("kolt-jvm-compiler-daemon").task(":build"))
    dependsOn(gradle.includedBuild("kolt-native-compiler-daemon").task(":build"))
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("kolt-jvm-compiler-daemon").task(":check"))
    dependsOn(gradle.includedBuild("kolt-native-compiler-daemon").task(":check"))
    dependsOn(verifyDaemonKotlinVersion)
    dependsOn(verifyDaemonMainClass)
}

tasks.named("clean") {
    dependsOn(gradle.includedBuild("kolt-jvm-compiler-daemon").task(":clean"))
    dependsOn(gradle.includedBuild("kolt-native-compiler-daemon").task(":clean"))
}
