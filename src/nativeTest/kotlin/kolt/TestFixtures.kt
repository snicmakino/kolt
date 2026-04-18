package kolt

import kolt.config.BuildSection
import kolt.config.CinteropConfig
import kolt.config.KoltConfig
import kolt.config.KotlinSection
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
    target: String = "jvm",
    cinterop: List<CinteropConfig> = emptyList()
) = KoltConfig(
    name = name,
    version = "0.1.0",
    kotlin = KotlinSection(version = "2.1.0", plugins = plugins),
    build = BuildSection(
        target = target,
        jvmTarget = jvmTarget,
        jdk = jdk,
        main = "com.example.main",
        sources = sources,
        testSources = testSources,
    ),
    dependencies = dependencies,
    testDependencies = testDependencies,
    repositories = repositories,
    cinterop = cinterop,
)
