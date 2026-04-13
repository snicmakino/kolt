package kolt.build

import kolt.config.CinteropConfig
import kolt.config.KoltConfig

internal const val BUILD_DIR = "build"
internal const val CLASSES_DIR = "$BUILD_DIR/classes"

internal fun outputJarPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.jar"

internal fun outputKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}.kexe"

internal fun outputNativeTestKexePath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test.kexe"

// Stage 1 output: the unpacked klib directory produced by `konanc -p library -nopack`.
// Using a `-klib` suffix keeps it distinct from the sibling `<name>.kexe` file.
internal fun outputNativeKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-klib"

internal fun outputNativeTestKlibPath(config: KoltConfig): String = "$BUILD_DIR/${config.name}-test-klib"

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
// We intentionally omit -e: -Xinclude brings the compiled entry point with it,
// and passing -e risks conflicting with the linked-in main().
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
        add("-p")
        add("program")
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
        add("-def")
        add(entry.def)
        add("-o")
        add(outputBase)
        if (entry.packageName != null) {
            add("-pkg")
            add(entry.packageName)
        }
        for (opt in entry.compilerOptions) {
            add("-compiler-option")
            add(opt)
        }
        for (opt in entry.linkerOptions) {
            add("-linker-option")
            add(opt)
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
//   - compiler_options / linker_options — editing these must invalidate even
//     though the .def file is untouched (this is the subtle failure mode
//     called out in #68: a naive mtime-only check silently reuses a stale
//     klib after a kolt.toml edit)
//   - the .def file's mtime — the usual "contents changed" signal
//   - the Kotlin/Native version — bumping `kotlin = "..."` in kolt.toml
//     switches the cinterop/konanc toolchain, and the klib format is not
//     guaranteed to be compatible across Kotlin versions
//
// Order of compilerOption / linkerOption lines follows declaration order
// so the stamp is sensitive to reordering.
fun cinteropStamp(entry: CinteropConfig, defMtime: Long, kotlinVersion: String): String = buildString {
    append("kotlinVersion=").append(kotlinVersion).append('\n')
    append("name=").append(entry.name).append('\n')
    append("def=").append(entry.def).append('\n')
    append("defMtime=").append(defMtime).append('\n')
    append("package=").append(entry.packageName ?: "").append('\n')
    for (opt in entry.compilerOptions) append("compilerOption=").append(opt).append('\n')
    for (opt in entry.linkerOptions) append("linkerOption=").append(opt).append('\n')
}

fun jarCommand(config: KoltConfig, jarPath: String? = null): BuildCommand {
    val outputPath = outputJarPath(config)
    return BuildCommand(
        args = listOf(jarPath ?: "jar", "cf", outputPath, "-C", CLASSES_DIR, "."),
        outputPath = outputPath
    )
}
