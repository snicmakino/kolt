package keel.build

import keel.config.KeelConfig

internal const val TEST_CLASSES_DIR = "$BUILD_DIR/test-classes"

data class TestBuildCommand(
    val args: List<String>,
    val outputPath: String
)

fun testBuildCommand(
    config: KeelConfig,
    classesDir: String,
    classpath: String? = null,
    pluginArgs: List<String> = emptyList(),
    kotlincPath: String? = null
): TestBuildCommand {
    val cp = buildList {
        add(classesDir)
        if (!classpath.isNullOrEmpty()) add(classpath)
    }.joinToString(":")
    val args = buildList {
        add(kotlincPath ?: "kotlinc")
        add("-cp")
        add(cp)
        addAll(config.testSources)
        add("-jvm-target")
        add(config.jvmTarget)
        addAll(pluginArgs)
        add("-d")
        add(TEST_CLASSES_DIR)
    }
    return TestBuildCommand(args = args, outputPath = TEST_CLASSES_DIR)
}
