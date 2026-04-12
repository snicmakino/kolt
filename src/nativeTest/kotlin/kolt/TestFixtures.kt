package kolt

import kolt.config.KoltConfig
import kolt.config.MAVEN_CENTRAL_BASE

fun testConfig(
    name: String = "my-app",
    sources: List<String> = listOf("src"),
    jvmTarget: String = "17",
    dependencies: Map<String, String> = emptyMap(),
    testSources: List<String> = listOf("test"),
    testDependencies: Map<String, String> = emptyMap(),
    plugins: Map<String, Boolean> = emptyMap(),
    repositories: Map<String, String> = mapOf("central" to MAVEN_CENTRAL_BASE),
    jdk: String? = null,
    target: String = "jvm"
) = KoltConfig(
    name = name,
    version = "0.1.0",
    kotlin = "2.1.0",
    target = target,
    jvmTarget = jvmTarget,
    main = "com.example.MainKt",
    sources = sources,
    dependencies = dependencies,
    testSources = testSources,
    testDependencies = testDependencies,
    plugins = plugins,
    repositories = repositories,
    jdk = jdk
)
