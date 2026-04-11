package keel.build

import keel.config.KeelConfig

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

internal fun outputJarPath(config: KeelConfig): String = "$BUILD_DIR/${config.name}.jar"

data class BuildCommand(
    val args: List<String>,
    val outputPath: String
)

fun checkCommand(
    config: KeelConfig,
    classpath: String? = null,
    pluginArgs: List<String> = emptyList(),
    kotlincPath: String? = null
): List<String> = buildList {
    add(kotlincPath ?: "kotlinc")
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
    pluginArgs: List<String> = emptyList(),
    kotlincPath: String? = null
): BuildCommand {
    val args = buildList {
        add(kotlincPath ?: "kotlinc")
        if (!classpath.isNullOrEmpty()) {
            add("-cp")
            add(classpath)
        }
        addAll(config.sources)
        add("-jvm-target")
        add(config.jvmTarget)
        addAll(pluginArgs)
        add("-d")
        add(CLASSES_DIR)
    }
    return BuildCommand(args = args, outputPath = CLASSES_DIR)
}

fun jarCommand(config: KeelConfig, jarPath: String? = null): BuildCommand {
    val outputPath = outputJarPath(config)
    return BuildCommand(
        args = listOf(jarPath ?: "jar", "cf", outputPath, "-C", CLASSES_DIR, "."),
        outputPath = outputPath
    )
}
