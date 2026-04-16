package kolt.build

import kolt.config.KoltConfig

fun autoInjectedTestDeps(config: KoltConfig): Map<String, String> {
    if (config.target != "jvm") return emptyMap()
    return mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to config.kotlin)
}

// Priority (right wins): auto-injected < main deps < user test deps.
fun mergeAllDeps(config: KoltConfig): Map<String, String> =
    autoInjectedTestDeps(config) + config.dependencies + config.testDependencies
