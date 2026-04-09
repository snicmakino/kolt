package keel

fun testConfig(
    name: String = "my-app",
    sources: List<String> = listOf("src"),
    jvmTarget: String = "17",
    testSources: List<String> = listOf("test"),
    testDependencies: Map<String, String> = emptyMap()
) = KeelConfig(
    name = name,
    version = "0.1.0",
    kotlin = "2.1.0",
    target = "jvm",
    jvmTarget = jvmTarget,
    main = "com.example.MainKt",
    sources = sources,
    testSources = testSources,
    testDependencies = testDependencies
)
