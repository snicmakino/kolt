@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// Adapter over kotlin-build-tools-api 2.3.20 per ADR 0019 §3. B-2a drives a
// **full recompile** through BTA (no SourcesChanges / workingDirectory state,
// no IC configuration attached to the JvmCompilationOperation). The goal of
// this class in B-2a is to prove classloader topology + compile path work end
// to end; IC enablement + self-heal land in B-2b.
//
// Classloader topology (issue #112 acceptance criterion 2 relies on this):
//
//   daemon-core classloader
//     └─ SharedApiClassesClassLoader  (serves org.jetbrains.kotlin.buildtools.api.*
//         └─ URLClassLoader            only — no -impl classes reachable upward)
//             └─ kotlin-build-tools-impl-*.jar [+ transitive]
//
// Any BTA call is wrapped in runCatching so a thrown KotlinBuildToolsException
// (or any other Throwable — the BTA surface is experimental) becomes an
// IcError.InternalError instead of propagating into daemon core. The self-heal
// wipe-and-retry path referenced in ADR 0019 §7 is B-2b's concern.
class BtaIncrementalCompiler private constructor(
    private val toolchain: KotlinToolchains,
) : IncrementalCompiler {

    override fun compile(request: IcRequest): Result<IcResponse, IcError> = runCatching {
        executeCompile(request)
    }.fold(
        onSuccess = { it },
        onFailure = { Err(IcError.InternalError(it)) },
    )

    private fun executeCompile(request: IcRequest): Result<IcResponse, IcError> {
        val builder = toolchain.jvm.jvmCompilationOperationBuilder(request.sources, request.outputDir)
        if (request.classpath.isNotEmpty()) {
            builder.compilerArguments[JvmCompilerArguments.CLASSPATH] =
                request.classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = moduleNameFor(request)
        // B-2a does not attach snapshotBasedIcConfigurationBuilder — the operation
        // runs as a plain full recompile. IC state layout under `request.workingDir`
        // is deliberately not exercised until B-2b (per issue #112 "Out of scope").

        val policy = toolchain.createInProcessExecutionPolicy()
        val start = TimeSource.Monotonic.markNow()
        val op = builder.build()
        val compilationResult = toolchain.createBuildSession().use { session ->
            session.executeOperation(op, policy)
        }
        val wall = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)

        return when (compilationResult) {
            CompilationResult.COMPILATION_SUCCESS -> Ok(
                IcResponse(
                    wallMillis = wall,
                    compiledFileCount = null,
                    status = Status.SUCCESS,
                ),
            )
            CompilationResult.COMPILATION_ERROR,
            CompilationResult.COMPILATION_OOM_ERROR,
            CompilationResult.COMPILER_INTERNAL_ERROR,
            -> Err(
                IcError.CompilationFailed(
                    // BTA's METRICS_COLLECTOR can surface diagnostic text, but
                    // the cold-path smoke test does not need per-message detail
                    // yet. A single synthetic line is enough for daemon core to
                    // report failure; wiring a real diagnostic collector is a
                    // B-2b concern that pairs with ic.* metrics.
                    messages = listOf("kotlinc reported $compilationResult"),
                ),
            )
        }
    }

    private fun moduleNameFor(request: IcRequest): String =
        // projectId is a stable caller-derived hash per ADR 0019 §3; it is
        // already URL/FS-safe enough to serve as a kotlinc module name.
        "kolt-${request.projectId}"

    companion object {
        // Loads kotlin-build-tools-impl from [btaImplJars] through a URLClassLoader
        // whose parent is SharedApiClassesClassLoader. This matches the isolation
        // policy used by Gradle's Kotlin plugin and by spike #104. Failure here is
        // load-bearing: if -impl classes cannot be found, BTA cannot initialise, so
        // we surface the classloader failure as an IcError.InternalError in the
        // Result returned to daemon core rather than throwing.
        fun create(btaImplJars: List<Path>): Result<BtaIncrementalCompiler, IcError.InternalError> =
            runCatching {
                require(btaImplJars.isNotEmpty()) {
                    "btaImplJars is empty — daemon must receive kotlin-build-tools-impl classpath via --bta-impl-jars"
                }
                val urls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
                val implLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
                BtaIncrementalCompiler(KotlinToolchains.loadImplementation(implLoader))
            }.fold(
                onSuccess = { Ok(it) },
                onFailure = { Err(IcError.InternalError(it)) },
            )
    }
}
