// build.gradle.kts
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.20"
}

repositories {
    // Specify the source to download the main bundle
    // Maven Central is used by default
    mavenCentral()
}

kotlin {
    // macosArm64()    // on macOS
    linuxX64()        // on Linux (x86_64)
    // mingwX64()     // on Windows

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            executable()
        }
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "9.3.0"
    distributionType = Wrapper.DistributionType.BIN
}

