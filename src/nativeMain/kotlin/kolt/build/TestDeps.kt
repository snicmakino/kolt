package kolt.build

import kolt.config.KoltConfig

fun autoInjectedTestDeps(config: KoltConfig): Map<String, String> {
  if (config.build.target != "jvm") return emptyMap()
  if (config.build.testSources.isEmpty() && config.testDependencies.isEmpty()) return emptyMap()
  return mapOf("org.jetbrains.kotlin:kotlin-test-junit5" to config.kotlin.version)
}
