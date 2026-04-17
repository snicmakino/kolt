// Throwaway spike for issue #138 — BTA-API binary compat across 2.x.
//
// Layout:
//   * `implementation("...-api:2.3.20")` freezes the adapter's compile-time
//     signatures, matching `kolt-compiler-daemon/ic/build.gradle.kts:56`.
//   * For each target impl version we resolve kotlin-build-tools-impl and a
//     matching kotlin-stdlib into separate resolvable configurations, then
//     expose them to the harness as system properties. The harness loads each
//     impl via `URLClassLoader(parent = SharedApiClassesClassLoader())` —
//     the same topology the daemon uses, so a LinkageError here means the
//     daemon would crash the same way in production.

plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
}

val btaApiVersion = "2.3.20"

// Covers: one representative from each pre-adapter minor line (2.1.0, 2.2.20)
// plus every publicly shipped 2.3.x patch — 2.3.x is the adapter's own line,
// so in-line patch stability is a support-policy input for ADR 0022.
val implMatrix = listOf("2.1.0", "2.2.20", "2.3.0", "2.3.10", "2.3.20")

implMatrix.forEach { version ->
    val implName = "btaImpl_${version.replace('.', '_')}"
    val fixtureName = "fixture_${version.replace('.', '_')}"
    configurations.create(implName) {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    configurations.create(fixtureName) {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    dependencies.add(implName, "org.jetbrains.kotlin:kotlin-build-tools-impl:$version")
    dependencies.add(fixtureName, "org.jetbrains.kotlin:kotlin-stdlib:$version")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:$btaApiVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("spike.bta.compat.MainKt")
}

tasks.named<JavaExec>("run") {
    implMatrix.forEach { version ->
        val implName = "btaImpl_${version.replace('.', '_')}"
        val fixtureName = "fixture_${version.replace('.', '_')}"
        systemProperty("bta.impl.classpath.$version", configurations.getByName(implName).asPath)
        systemProperty("fixture.classpath.$version", configurations.getByName(fixtureName).asPath)
    }
    systemProperty("bta.impl.matrix", implMatrix.joinToString(","))
}
