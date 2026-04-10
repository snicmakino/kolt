package keel.build

import keel.config.KeelConfig

internal const val BUILD_DIR = "build"

internal fun jarPath(config: KeelConfig): String = "$BUILD_DIR/${config.name}.jar"

data class BuildCommand(
    val args: List<String>,
    val outputPath: String
)

fun checkCommand(
    config: KeelConfig,
    classpath: String? = null,
    pluginArgs: List<String> = emptyList()
): List<String> = buildList {
    add("kotlinc")
    if (!classpath.isNullOrEmpty()) {
        add("-cp")
        add(classpath)
    }
    addAll(config.sources)
    add("-jvm-target")
    add(config.jvmTarget)
    addAll(pluginArgs)
}

fun buildCommand(
    config: KeelConfig,
    classpath: String? = null,
    pluginArgs: List<String> = emptyList()
): BuildCommand {
    val outputPath = jarPath(config)
    val args = buildList {
        add("kotlinc")
        if (!classpath.isNullOrEmpty()) {
            add("-cp")
            add(classpath)
        }
        addAll(config.sources)
        add("-jvm-target")
        add(config.jvmTarget)
        addAll(pluginArgs)
        add("-include-runtime")
        add("-d")
        add(outputPath)
    }
    return BuildCommand(args = args, outputPath = outputPath)
}
