package kolt.build

import kolt.config.CinteropConfig
import kolt.config.KoltConfig

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

// Passed explicitly so a cross-build cannot silently fall back to host default.
internal const val NATIVE_TARGET = "linux_x64"

internal fun outputJarPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.jar"

internal fun outputKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.kexe"

internal fun outputNativeTestKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test.kexe"

internal fun outputNativeKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-klib"

internal fun outputNativeTestKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test-klib"

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

// Two-stage build: konanc silently no-ops compiler plugins on single-step
// `-p program`, so Stage 1 compiles into a klib (plugin applied), Stage 2 links.
fun nativeLibraryCommand(
    config: KoltConfig,
    pluginArgs: List<String> = emptyList(),
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputBase = outputNativeKlibPath(config)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(NATIVE_TARGET)
        addAll(config.sources)
        add("-p")
        add("library")
        add("-nopack")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(pluginArgs)
    }
    return BuildCommand(args = args, outputPath = outputBase)
}

fun nativeLinkCommand(
    config: KoltConfig,
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputPath = outputKexePath(config)
    val outputBase = "$BUILD_DIR/${config.name}"
    val klibPath = outputNativeKlibPath(config)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(NATIVE_TARGET)
        add("-p")
        add("program")
        add("-e")
        add(config.main)
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-Xinclude=$klibPath")
        add("-o")
        add(outputBase)
    }
    return BuildCommand(args = args, outputPath = outputPath)
}

fun nativeTestLibraryCommand(
    config: KoltConfig,
    pluginArgs: List<String> = emptyList(),
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputBase = outputNativeTestKlibPath(config)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(NATIVE_TARGET)
        addAll(config.sources)
        addAll(config.testSources)
        add("-p")
        add("library")
        add("-nopack")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(pluginArgs)
    }
    return BuildCommand(args = args, outputPath = outputBase)
}

fun nativeTestLinkCommand(
    config: KoltConfig,
    konancPath: String? = null,
    klibs: List<String> = emptyList()
): BuildCommand {
    val outputPath = outputNativeTestKexePath(config)
    val outputBase = "$BUILD_DIR/${config.name}-test"
    val klibPath = outputNativeTestKlibPath(config)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(NATIVE_TARGET)
        add("-p")
        add("program")
        add("-generate-test-runner")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-Xinclude=$klibPath")
        add("-o")
        add(outputBase)
    }
    return BuildCommand(args = args, outputPath = outputPath)
}

// cinterop appends .klib to the -o path, so outputPath omits the extension.
fun cinteropCommand(
    entry: CinteropConfig,
    cinteropPath: String? = null,
    outputDir: String = BUILD_DIR
): BuildCommand {
    val outputBase = "$outputDir/${entry.name}"
    val args = buildList {
        add(cinteropPath ?: "cinterop")
        add("-target")
        add(NATIVE_TARGET)
        add("-def")
        add(entry.def)
        add("-o")
        add(outputBase)
        if (entry.packageName != null) {
            add("-pkg")
            add(entry.packageName)
        }
    }
    return BuildCommand(args = args, outputPath = outputBase)
}

fun cinteropOutputKlibPath(entry: CinteropConfig, outputDir: String = BUILD_DIR): String =
    "$outputDir/${entry.name}.klib"

fun cinteropStampPath(entry: CinteropConfig, outputDir: String = BUILD_DIR): String =
    "$outputDir/${entry.name}.klib.stamp"

// Includes kotlinVersion because klib format is not guaranteed compatible across versions.
fun cinteropStamp(entry: CinteropConfig, defMtime: Long, kotlinVersion: String): String = buildString {
    append("kotlinVersion=").append(kotlinVersion).append('\n')
    append("name=").append(entry.name).append('\n')
    append("def=").append(entry.def).append('\n')
    append("defMtime=").append(defMtime).append('\n')
    append("package=").append(entry.packageName ?: "").append('\n')
}

fun jarCommand(config: KoltConfig, jarPath: String? = null): BuildCommand {
    val outputPath = outputJarPath(config)
    return BuildCommand(
        args = listOf(jarPath ?: "jar", "cf", outputPath, "-C", CLASSES_DIR, "."),
        outputPath = outputPath
    )
}
