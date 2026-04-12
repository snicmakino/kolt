package kolt.build

import kolt.config.KoltConfig

/**
 * Returns test dependencies that kolt auto-injects based on the target platform.
 * For JVM, injects kotlin-test-junit5 matching the project's Kotlin version.
 * Users can override the version via [test-dependencies] in kolt.toml.
 */
fun autoInjectedTestDeps(config: KoltConfig): Map<String, String> {
    if (config.target != "jvm") return emptyMap()
    return mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to config.kotlin)
}

/**
 * Merges auto-injected test deps, main deps, and user test deps.
 * Priority (right-hand side wins): auto-injected < main deps < user test deps.
 */
fun mergeAllDeps(config: KoltConfig): Map<String, String> =
    autoInjectedTestDeps(config) + config.dependencies + config.testDependencies
