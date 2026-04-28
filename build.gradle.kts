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
    gradleVersion = "9.4.1"
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
}

tasks.named("clean") {
    dependsOn(gradle.includedBuild("kolt-jvm-compiler-daemon").task(":clean"))
    dependsOn(gradle.includedBuild("kolt-native-compiler-daemon").task(":clean"))
}
