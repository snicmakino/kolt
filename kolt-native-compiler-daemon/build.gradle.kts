plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

repositories {
    mavenCentral()
}

dependencies {
    // kotlin-native-compiler-embeddable.jar is NOT declared here. ADR 0024 §8:
    // the native daemon receives its path on the CLI via `--konanc-jar <path>`
    // and loads it through a dedicated URLClassLoader at request-handling time.
    // Bundling it would defeat the reflective-load contract and drag a ~60MB
    // artifact into the jar.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
