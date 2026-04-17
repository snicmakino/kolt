@file:OptIn(ExperimentalBuildToolsApi::class)

package spike.bta.compat

// Throwaway harness for #138 spike. Walks each impl version in `bta.impl.matrix`,
// loads it via the daemon's SharedApiClassesClassLoader topology, and runs a
// hello-world cold + incremental cycle. Every phase is wrapped so a thrown
// LinkageError / NoSuchMethodError does not abort the matrix — we need to see
// *which* versions break and how.

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
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private enum class Stage { LOAD_TOOLCHAIN, COMPILER_VERSION, COLD, TOUCH, INCREMENTAL }

private enum class Verdict { GREEN, RED_LINKAGE, RED_METHOD, RED_OTHER, COMPILE_ERROR }

private data class RunOutcome(
    val implVersion: String,
    val verdict: Verdict,
    val failedStage: Stage?,
    val compilerVersion: String?,
    val coldStatus: String?,
    val coldWallMs: Long?,
    val incStatus: String?,
    val incWallMs: Long?,
    val error: Throwable?,
)

fun main(args: Array<String>) {
    val fixtureDir = Path.of(args.getOrElse(0) { "fixtures/linear-10" }).toAbsolutePath()
    val workRoot = Path.of(args.getOrElse(1) { "/tmp/bta-compat-work" }).toAbsolutePath()
    val matrix = System.getProperty("bta.impl.matrix")!!.split(",")

    println("=== BTA compat spike (#138) ===")
    println("adapter compile-time API: 2.3.20")
    println("fixture: $fixtureDir")
    println("workdir: $workRoot")
    println("matrix:  ${matrix.joinToString(", ")}")
    println()

    val outcomes = matrix.map { version ->
        println("--- impl $version ---")
        val outcome = runOne(version, fixtureDir, workRoot.resolve(version))
        printOutcome(outcome)
        println()
        outcome
    }

    println("=== summary ===")
    outcomes.forEach { o ->
        val tail = o.error?.let { " :: ${it.javaClass.name}: ${it.message?.take(160)}" } ?: ""
        val stageTag = o.failedStage?.let { " [$it]" } ?: ""
        println("${o.implVersion.padEnd(8)}  ${o.verdict}$stageTag$tail")
    }
}

private fun runOne(implVersion: String, fixtureDir: Path, workDir: Path): RunOutcome {
    resetDir(workDir)
    val sourcesDir = workDir.resolve("sources")
    copyFixture(fixtureDir, sourcesDir)
    val classesDir = workDir.resolve("classes").also { it.createDirectories() }
    val icDir = workDir.resolve("ic").also { it.createDirectories() }
    val snapshotDir = workDir.resolve("snapshots").also { it.createDirectories() }
    val shrunk = snapshotDir.resolve("shrunk-classpath-snapshot.bin")

    val fixtureClasspath = System.getProperty("fixture.classpath.$implVersion")
        ?: return outcomeForError(implVersion, Stage.LOAD_TOOLCHAIN,
            IllegalStateException("fixture.classpath.$implVersion not set"))

    val toolchain = try {
        loadToolchain(implVersion)
    } catch (t: Throwable) {
        return outcomeForError(implVersion, Stage.LOAD_TOOLCHAIN, t)
    }

    val compilerVersion = try {
        toolchain.getCompilerVersion()
    } catch (t: Throwable) {
        return outcomeForError(implVersion, Stage.COMPILER_VERSION, t)
            .copy(compilerVersion = null)
    }

    val cold = try {
        runCompilation(toolchain, sourcesDir, classesDir, icDir, shrunk, fixtureClasspath)
    } catch (t: Throwable) {
        return outcomeForError(implVersion, Stage.COLD, t).copy(compilerVersion = compilerVersion)
    }

    val touched = sourcesDir.resolve("F2.kt")
    try {
        touchSourceFile(touched)
    } catch (t: Throwable) {
        return outcomeForError(implVersion, Stage.TOUCH, t).copy(
            compilerVersion = compilerVersion,
            coldStatus = cold.compilationResult.name,
            coldWallMs = cold.wallMs,
        )
    }

    val inc = try {
        runCompilation(toolchain, sourcesDir, classesDir, icDir, shrunk, fixtureClasspath)
    } catch (t: Throwable) {
        return outcomeForError(implVersion, Stage.INCREMENTAL, t).copy(
            compilerVersion = compilerVersion,
            coldStatus = cold.compilationResult.name,
            coldWallMs = cold.wallMs,
        )
    }

    val verdict = when {
        cold.compilationResult != CompilationResult.COMPILATION_SUCCESS -> Verdict.COMPILE_ERROR
        inc.compilationResult != CompilationResult.COMPILATION_SUCCESS -> Verdict.COMPILE_ERROR
        else -> Verdict.GREEN
    }

    return RunOutcome(
        implVersion = implVersion,
        verdict = verdict,
        failedStage = null,
        compilerVersion = compilerVersion,
        coldStatus = cold.compilationResult.name,
        coldWallMs = cold.wallMs,
        incStatus = inc.compilationResult.name,
        incWallMs = inc.wallMs,
        error = null,
    )
}

private fun outcomeForError(version: String, stage: Stage, t: Throwable): RunOutcome {
    val verdict = classify(t)
    return RunOutcome(
        implVersion = version,
        verdict = verdict,
        failedStage = stage,
        compilerVersion = null,
        coldStatus = null,
        coldWallMs = null,
        incStatus = null,
        incWallMs = null,
        error = t,
    )
}

private fun classify(t: Throwable): Verdict {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is NoSuchMethodError, is AbstractMethodError -> return Verdict.RED_METHOD
            is LinkageError, is NoClassDefFoundError -> return Verdict.RED_LINKAGE
        }
        cur = cur.cause
    }
    return Verdict.RED_OTHER
}

private fun printOutcome(o: RunOutcome) {
    println("verdict: ${o.verdict}${o.failedStage?.let { " at $it" } ?: ""}")
    o.compilerVersion?.let { println("compiler version: $it") }
    o.coldStatus?.let { println("cold: $it (${o.coldWallMs}ms)") }
    o.incStatus?.let { println("inc:  $it (${o.incWallMs}ms)") }
    o.error?.let { t ->
        println("error: ${t.javaClass.name}: ${t.message}")
        t.printStackTrace(System.out)
    }
}

private data class CompileResult(val compilationResult: CompilationResult, val wallMs: Long)

@OptIn(ExperimentalPathApi::class)
private fun runCompilation(
    toolchain: KotlinToolchains,
    sourcesDir: Path,
    classesDir: Path,
    icDir: Path,
    shrunkClasspathSnapshot: Path,
    fixtureClasspath: String,
): CompileResult {
    val sources: List<Path> = sourcesDir.walk()
        .filter { it.extension == "kt" || it.extension == "java" }
        .sorted()
        .toList()

    val builder = toolchain.jvm.jvmCompilationOperationBuilder(sources, classesDir)
    builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = fixtureClasspath
    builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "spike-bta-compat"
    builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
    builder.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true

    val icConfig = builder.snapshotBasedIcConfigurationBuilder(
        workingDirectory = icDir,
        sourcesChanges = SourcesChanges.ToBeCalculated,
        dependenciesSnapshotFiles = emptyList(),
        shrunkClasspathSnapshot = shrunkClasspathSnapshot,
    ).build()
    builder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icConfig

    builder[BuildOperation.METRICS_COLLECTOR] = object : BuildMetricsCollector {
        override fun collectMetric(name: String, type: BuildMetricsCollector.ValueType, value: Long) { /* ignore */ }
    }

    val op = builder.build()
    val policy = toolchain.createInProcessExecutionPolicy()
    val start = TimeSource.Monotonic.markNow()
    val result = toolchain.createBuildSession().use { session ->
        session.executeOperation(op, policy)
    }
    val wall = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)
    return CompileResult(result, wall)
}

private fun loadToolchain(implVersion: String): KotlinToolchains {
    val cp = System.getProperty("bta.impl.classpath.$implVersion")
        ?: error("bta.impl.classpath.$implVersion not set")
    val urls = cp.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it).toURI().toURL() }
        .toTypedArray()
    val loader = URLClassLoader(urls, SharedApiClassesClassLoader())
    return KotlinToolchains.loadImplementation(loader)
}

@OptIn(ExperimentalPathApi::class)
private fun resetDir(dir: Path) {
    if (Files.exists(dir)) dir.deleteRecursively()
    dir.createDirectories()
}

@OptIn(ExperimentalPathApi::class)
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
    val uniq = System.nanoTime()
    val marker = "\nfun spikeTouch$uniq(): Long = $uniq\n"
    Files.writeString(path, marker, java.nio.file.StandardOpenOption.APPEND)
}
