package kolt.build

import kolt.config.CinteropConfig
import kolt.config.KoltConfig

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

// Kotlin/Native target triple. Currently linux_x64 is the only supported
// target (see #61 non-goals). Passed explicitly on every konanc and cinterop
// invocation so a future cross-build (e.g. running kolt on macOS targeting
// linux_x64) cannot silently fall back to the host default and produce a
// wrong-architecture klib. Mirrors what the Kotlin Gradle plugin does.
internal const val NATIVE_TARGET = "linux_x64"

internal fun outputJarPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.jar"

internal fun outputKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.kexe"

internal fun outputNativeTestKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test.kexe"

// Stage 1 output: the unpacked klib directory produced by `konanc -p library -nopack`.
// Using a `-klib` suffix keeps it distinct from the sibling `<name>.kexe` file.
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

// Native builds run in two stages because konanc silently no-ops compiler
// plugins (e.g. kotlinx.serialization) on a single-step `-p program` invocation.
// Stage 1 compiles sources into an unpacked klib with the plugin applied; Stage
// 2 links that klib into a program binary. This matches what the Kotlin Gradle
// plugin does: compileKotlinLinuxX64 produces a klib, linkDebugExecutableLinuxX64
// produces the executable.

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

// Stage 2 consumes the klib produced by nativeLibraryCommand via -Xinclude,
// which pulls the klib's IR (including the main() function) into the final
// program. The plugin flag is not needed here — the IR is already transformed.
//
// -e points konanc at the entry function. config.main holds the Kotlin
// function FQN verbatim, so we forward it as-is. Without this, konanc looks
// for a function named `main` in the root package and fails when main lives
// in a named package (e.g. `kolt.cli.main`).
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

// Test Stage 1: compile main + test sources together into a klib. Kotlin/Native
// test discovery needs the @Test classes to live in a klib so the runner can
// synthesize a main() that iterates them in Stage 2.
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

// Test Stage 2: -generate-test-runner asks the compiler to synthesize a main()
// that discovers and runs @Test functions pulled in via -Xinclude from the klib.
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

// cinterop appends .klib to the -o path, so outputPath is without the extension.
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

// Path of the sidecar stamp file written next to a cinterop klib. The stamp
// records a canonical serialization of every CinteropConfig field plus the
// .def file's mtime. runCinterop reads it to decide whether a previously
// generated klib can be reused without re-invoking the cinterop tool.
fun cinteropStampPath(entry: CinteropConfig, outputDir: String = BUILD_DIR): String =
    "$outputDir/${entry.name}.klib.stamp"

// Canonical freshness stamp for a cinterop entry. Two stamps compare equal
// iff a re-run of cinterop would produce an equivalent klib for cache purposes.
// We observe:
//   - name / def path / package — rename or relocation must invalidate
//   - the .def file's mtime — the usual "contents changed" signal; since
//     compilerOpts / linkerOpts live inside the .def file, editing them
//     bumps mtime and invalidates the cache for free
//   - the Kotlin/Native version — bumping `kotlin = "..."` in kolt.toml
//     switches the cinterop/konanc toolchain, and the klib format is not
//     guaranteed to be compatible across Kotlin versions
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
