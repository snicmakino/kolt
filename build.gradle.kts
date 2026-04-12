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
