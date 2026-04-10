package keel.build

import keel.config.KeelConfig

data class TestBuildCommand(
    val args: List<String>,
    val outputPath: String
)

fun testJarPath(config: KeelConfig): String = "$BUILD_DIR/${config.name}-test.jar"

fun testBuildCommand(
    config: KeelConfig,
    mainJarPath: String,
    classpath: String? = null,
    pluginArgs: List<String> = emptyList()
): TestBuildCommand {
    val outputPath = testJarPath(config)
    val cp = buildList {
        add(mainJarPath)
        if (!classpath.isNullOrEmpty()) add(classpath)
    }.joinToString(":")
    val args = buildList {
        add("kotlinc")
        add("-cp")
        add(cp)
        addAll(config.testSources)
        add("-jvm-target")
        add(config.jvmTarget)
        addAll(pluginArgs)
        add("-d")
        add(outputPath)
    }
    return TestBuildCommand(args = args, outputPath = outputPath)
}
