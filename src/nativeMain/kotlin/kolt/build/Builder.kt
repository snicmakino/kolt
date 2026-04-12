package kolt.build

import kolt.config.KoltConfig

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

internal fun outputJarPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.jar"

internal fun outputKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.kexe"

internal fun outputNativeTestKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test.kexe"

// Derives the Kotlin/Native entry point FQN from config.main.
//
// config.main is a JVM-style class name (e.g. "com.example.MainKt"). For
// Kotlin/Native, konanc needs the fully-qualified function name instead. We
// take the package portion (everything before the last dot) and append
// ".main". The class-name suffix itself is discarded.
//
// Limitations (Phase A): we assume the entry function is a top-level `fun main`
// in the same package as the JVM facade class. Non-standard entry points
// (custom function name, `@JvmStatic` on a companion, etc.) are not supported
// and will produce a konanc "entry point not found" error. Callers should
// prefer top-level `fun main()` and a `*Kt` facade class name. See
// needsNativeEntryPointWarning for the heuristic used to flag likely issues.
internal fun nativeEntryPoint(config: KoltConfig): String {
    val lastDot = config.main.lastIndexOf('.')
    val pkg = if (lastDot >= 0) config.main.substring(0, lastDot) else ""
    return if (pkg.isEmpty()) "main" else "$pkg.main"
}

// Returns true when config.main's class-name segment does not end with "Kt",
// which is the conventional suffix kotlinc generates for top-level functions
// in file Foo.kt. A non-Kt class name suggests the user may have pointed main
// at a non-top-level entry, and nativeEntryPoint's derivation will likely not
// match their intent.
internal fun needsNativeEntryPointWarning(config: KoltConfig): Boolean {
    val lastSegment = config.main.substringAfterLast('.')
    return lastSegment.isNotEmpty() && !lastSegment.endsWith("Kt")
}

data class BuildCommand(
    val args: List<String>,
    val outputPath: String
)

fun checkCommand(
    config: KoltConfig,
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
    config: KoltConfig,
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

fun nativeBuildCommand(
    config: KoltConfig,
    pluginArgs: List<String> = emptyList(),
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputPath = outputKexePath(config)
    // konanc -o takes the path without the .kexe extension
    val outputBase = "$BUILD_DIR/${config.name}"
    val args = buildList {
        add(konancPath ?: "konanc")
        addAll(config.sources)
        add("-p")
        add("program")
        add("-e")
        add(nativeEntryPoint(config))
        // -library / -l takes one library per flag; do not join with colons.
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(pluginArgs)
    }
    return BuildCommand(args = args, outputPath = outputPath)
}

// Builds a konanc command that compiles main + test sources together and
// asks the compiler to synthesize a test runner main() via -generate-test-runner.
// The resulting kexe exits non-zero on any test failure. Unlike nativeBuildCommand
// we intentionally omit -e: the synthesized runner provides the entry point, and
// passing -e in addition would conflict with it.
fun nativeTestBuildCommand(
    config: KoltConfig,
    pluginArgs: List<String> = emptyList(),
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputPath = outputNativeTestKexePath(config)
    val outputBase = "$BUILD_DIR/${config.name}-test"
    val args = buildList {
        add(konancPath ?: "konanc")
        addAll(config.sources)
        addAll(config.testSources)
        add("-p")
        add("program")
        add("-generate-test-runner")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(pluginArgs)
    }
    return BuildCommand(args = args, outputPath = outputPath)
}

fun jarCommand(config: KoltConfig, jarPath: String? = null): BuildCommand {
    val outputPath = outputJarPath(config)
    return BuildCommand(
        args = listOf(jarPath ?: "jar", "cf", outputPath, "-C", CLASSES_DIR, "."),
        outputPath = outputPath
    )
}
