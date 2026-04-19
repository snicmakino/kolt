package kolt.build

import kolt.config.CinteropConfig
import kolt.config.KoltConfig
import kolt.config.konanTargetGradleName

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

// Native IC cache. Wiped by `kolt clean` (it lives under BUILD_DIR).
// Spike #160: touch/abi-neutral wall-time -30–39% at 25–50 files; cold
// builds also speed up from konanc's IC strategy. Issue #168.
internal const val NATIVE_IC_CACHE_DIR = "$BUILD_DIR/.ic-cache"

internal fun outputJarPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.jar"

internal fun outputKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.kexe"

internal fun outputNativeTestKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test.kexe"

internal fun outputNativeKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-klib"

internal fun outputNativeTestKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test-klib"

data class BuildCommand(
    val args: List<String>,
    val outputPath: String
)

// #162: when `[kotlin] compiler` outruns `version`, pin the language/API surface
// to `version` so a 2.3.x daemon can still compile 2.1-language projects.
// Returns an empty list on equal (or unset-compiler) to keep the common case
// flag-free — warning-free on every kotlinc release.
//
// `-language-version` / `-api-version` accept only `major.minor` (`2.1`), not
// `major.minor.patch` (`2.1.0`). kotlinc rejects the patch form with
// `Unknown -api-version value: 2.1.0`, so we truncate.
internal fun languageVersionArgs(config: KoltConfig): List<String> {
    val lang = config.kotlin.version
    if (config.kotlin.effectiveCompiler == lang) return emptyList()
    val surface = majorMinor(lang)
    return listOf("-language-version", surface, "-api-version", surface)
}

internal fun majorMinor(version: String): String =
    version.split('.').take(2).joinToString(".")

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
    addAll(config.build.sources)
    add("-jvm-target")
    add(config.build.jvmTarget)
    addAll(languageVersionArgs(config))
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
    val nativeTarget = konanTargetGradleName(config.build.target)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(nativeTarget)
        addAll(config.build.sources)
        add("-p")
        add("library")
        add("-nopack")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(languageVersionArgs(config))
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
    val nativeTarget = konanTargetGradleName(config.build.target)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(nativeTarget)
        add("-p")
        add("program")
        add("-e")
        add(config.build.main)
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-Xinclude=$klibPath")
        add("-Xenable-incremental-compilation")
        add("-Xic-cache-dir=$NATIVE_IC_CACHE_DIR")
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
    val nativeTarget = konanTargetGradleName(config.build.target)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(nativeTarget)
        addAll(config.build.sources)
        addAll(config.build.testSources)
        add("-p")
        add("library")
        add("-nopack")
        for (klib in klibs) {
            add("-l")
            add(klib)
        }
        add("-o")
        add(outputBase)
        addAll(languageVersionArgs(config))
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
    val nativeTarget = konanTargetGradleName(config.build.target)
    val args = buildList {
        add(konancPath ?: "konanc")
        add("-target")
        add(nativeTarget)
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
    target: String,
    cinteropPath: String? = null,
    outputDir: String = BUILD_DIR
): BuildCommand {
    val outputBase = "$outputDir/${entry.name}"
    val args = buildList {
        add(cinteropPath ?: "cinterop")
        add("-target")
        add(konanTargetGradleName(target))
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
