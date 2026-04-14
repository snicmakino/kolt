@file:OptIn(ExperimentalBuildToolsApi::class)

package spike.ic

import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.system.exitProcess
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

fun main(args: Array<String>) {
    if (args.size == 1 && args[0] == "--version") {
        val toolchain = loadToolchain()
        println("kotlin-build-tools-api loaded. compiler version: ${toolchain.getCompilerVersion()}")
        return
    }
    if (args.size != 3) {
        System.err.println("usage: <fixture-dir> <work-dir> <touched-file>")
        exitProcess(2)
    }

    val fixtureDir = Path.of(args[0]).toAbsolutePath()
    val workDir = Path.of(args[1]).toAbsolutePath()
    val touchedFileName = args[2]

    require(Files.isDirectory(fixtureDir)) { "fixture dir not found: $fixtureDir" }

    val sourcesDir = workDir.resolve("sources")
    val classesDir = workDir.resolve("classes")
    val icDir = workDir.resolve("ic")
    val snapshotDir = workDir.resolve("snapshots")

    resetDir(workDir)
    copyFixture(fixtureDir, sourcesDir)
    listOf(classesDir, icDir, snapshotDir).forEach { it.createDirectories() }

    val touched = sourcesDir.resolve(touchedFileName)
    require(Files.isRegularFile(touched)) { "touched source not found in fixture: $touched" }

    val toolchain = loadToolchain()
    println("=== spike incremental-ic ===")
    println("compiler: ${toolchain.getCompilerVersion()}")
    println("fixture:  $fixtureDir")
    println("sources:  $sourcesDir")
    println("classes:  $classesDir")
    println("ic dir:   $icDir")
    println("touch:    $touchedFileName")
    println()

    val shrunkClasspathSnapshot = snapshotDir.resolve("shrunk-classpath-snapshot.bin")

    // -------- Cold run --------
    println("--- cold run ---")
    val coldResult = runCompilation(
        toolchain = toolchain,
        sourcesDir = sourcesDir,
        classesDir = classesDir,
        icDir = icDir,
        dependenciesSnapshotFiles = emptyList(),
        shrunkClasspathSnapshot = shrunkClasspathSnapshot,
        sourcesChanges = SourcesChanges.ToBeCalculated,
    )
    val coldClassHashes = hashClassFiles(classesDir)
    printRun("cold", coldResult, coldClassHashes.size)
    println("   class files: ${coldClassHashes.size}")

    // -------- Touch --------
    println()
    println("--- touch ---")
    touchSourceFile(touched)
    println("touched $touchedFileName (appended whitespace + unique body marker)")

    // -------- Incremental run --------
    println()
    println("--- incremental run ---")
    val incResult = runCompilation(
        toolchain = toolchain,
        sourcesDir = sourcesDir,
        classesDir = classesDir,
        icDir = icDir,
        dependenciesSnapshotFiles = emptyList(),
        shrunkClasspathSnapshot = shrunkClasspathSnapshot,
        sourcesChanges = SourcesChanges.ToBeCalculated,
    )
    val incClassHashes = hashClassFiles(classesDir)
    printRun("incremental", incResult, incClassHashes.size)

    // -------- Diff --------
    val changed = incClassHashes.filter { (path, hash) -> coldClassHashes[path] != hash }
    val added = incClassHashes.keys - coldClassHashes.keys
    val removed = coldClassHashes.keys - incClassHashes.keys
    println()
    println("--- recompile set (by .class hash diff) ---")
    println("changed: ${changed.size}")
    changed.keys.sorted().forEach { println("  ~ $it") }
    if (added.isNotEmpty()) {
        println("added:   ${added.size}")
        added.sorted().forEach { println("  + $it") }
    }
    if (removed.isNotEmpty()) {
        println("removed: ${removed.size}")
        removed.sorted().forEach { println("  - $it") }
    }
    println()
    println("spike done")
}

private class RunResult(
    val compilationResult: CompilationResult,
    val wallMs: Long,
    val metrics: Map<String, Long>,
)

private fun printRun(label: String, r: RunResult, classFilesCount: Int) {
    println("$label: status=${r.compilationResult} wall=${r.wallMs}ms class-files=$classFilesCount")
    val interesting = r.metrics.filter { (k, _) ->
        k.contains("compiled", ignoreCase = true) || k.contains("Number of", ignoreCase = true)
    }
    if (interesting.isNotEmpty()) {
        println("  metrics:")
        interesting.entries.sortedBy { it.key }.forEach { (k, v) -> println("    $k = $v") }
    }
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun runCompilation(
    toolchain: KotlinToolchains,
    sourcesDir: Path,
    classesDir: Path,
    icDir: Path,
    dependenciesSnapshotFiles: List<Path>,
    shrunkClasspathSnapshot: Path,
    sourcesChanges: SourcesChanges,
): RunResult {
    val sources: List<Path> = sourcesDir.walk()
        .filter { it.extension == "kt" || it.extension == "java" }
        .sorted()
        .toList()
    val fixtureClasspath = System.getProperty("fixture.classpath")!!

    val collected = mutableMapOf<String, Long>()
    val collector = object : BuildMetricsCollector {
        override fun collectMetric(name: String, type: BuildMetricsCollector.ValueType, value: Long) {
            collected.merge(name, value) { a, b -> a + b }
        }
    }

    val builder = toolchain.jvm.jvmCompilationOperationBuilder(sources, classesDir)
    builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = fixtureClasspath
    builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "spike-fixture"
    builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
    builder.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true

    val icConfig = builder.snapshotBasedIcConfigurationBuilder(
        workingDirectory = icDir,
        sourcesChanges = sourcesChanges,
        dependenciesSnapshotFiles = dependenciesSnapshotFiles,
        shrunkClasspathSnapshot = shrunkClasspathSnapshot,
    ).build()
    builder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icConfig

    builder[BuildOperation.METRICS_COLLECTOR] = collector
    val op = builder.build()

    val policy = toolchain.createInProcessExecutionPolicy()
    val start = TimeSource.Monotonic.markNow()
    val result = toolchain.createBuildSession().use { session ->
        session.executeOperation(op, policy)
    }
    val wall = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)
    return RunResult(result, wall, collected.toMap())
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun hashClassFiles(classesDir: Path): Map<String, String> {
    if (!Files.isDirectory(classesDir)) return emptyMap()
    val md = MessageDigest.getInstance("SHA-256")
    return classesDir.walk()
        .filter { it.extension == "class" }
        .associate { p ->
            val rel = p.relativeTo(classesDir).toString()
            md.reset()
            md.update(Files.readAllBytes(p))
            rel to md.digest().joinToString("") { "%02x".format(it) }
        }
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun resetDir(dir: Path) {
    if (Files.exists(dir)) dir.deleteRecursively()
    dir.createDirectories()
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun copyFixture(src: Path, dst: Path) {
    dst.createDirectories()
    src.walk().filter { Files.isRegularFile(it) }.forEach { p ->
        val rel = p.relativeTo(src)
        val target = dst.resolve(rel.toString())
        target.parent?.createDirectories()
        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun touchSourceFile(path: Path) {
    // Append a new top-level function with a unique name so that the touched
    // source actually produces different bytecode (cold vs incremental .class
    // hash comparison is the spike's primary observation for the recompile
    // set). A comment-only change would leave the .class file byte-identical
    // and defeat the hash diff.
    val uniq = System.nanoTime()
    val marker = "\nfun spikeTouch$uniq(): Long = $uniq\n"
    Files.writeString(
        path,
        marker,
        java.nio.file.StandardOpenOption.APPEND,
    )
}

private fun loadToolchain(): KotlinToolchains {
    val implClasspath = System.getProperty("bta.impl.classpath")
        ?: error("bta.impl.classpath system property not set")
    val urls = implClasspath.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it).toURI().toURL() }
        .toTypedArray()
    val loader = URLClassLoader(urls, SharedApiClassesClassLoader())
    return KotlinToolchains.loadImplementation(loader)
}
