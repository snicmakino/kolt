plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "com.github.snicmakino"

repositories {
    mavenCentral()
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
