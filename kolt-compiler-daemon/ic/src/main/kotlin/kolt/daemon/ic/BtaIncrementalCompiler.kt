@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
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
// The entire BTA call sequence is fenced by a single `try { ... } catch (Throwable)`
// at the adapter boundary so that a thrown `KotlinBuildToolsException` (or any other
// failure mode on the experimental surface) becomes an `IcError.InternalError`
// instead of propagating into daemon core. Per ADR 0019 §7, `InternalError` is the
// signal B-2b's self-heal path keys off — it must cover both (a) thrown exceptions
// and (b) non-COMPILATION_ERROR `CompilationResult` values (`COMPILER_INTERNAL_ERROR`,
// `COMPILATION_OOM_ERROR`), because those are compiler-infrastructure failures, not
// user-code type errors. Only `COMPILATION_ERROR` represents "the user's source
// does not type-check" and is the one variant that must reach daemon core as
// `CompilationFailed`.
class BtaIncrementalCompiler private constructor(
    private val toolchain: KotlinToolchains,
    // Maps a kolt.toml plugin alias (e.g. "serialization") to the plugin jar
    // classpath on disk. Injected at construction time so daemon startup owns
    // the policy (walk a plugin-jars directory, read from --plugin-jars CLI,
    // etc.) and :ic stays a pure translator. B-2a defaults this to an empty-
    // result resolver — the translation path is still exercised end to end,
    // and real plugin jar delivery is wired in daemon core later (B-2b/B-2c).
    private val pluginJarResolver: (alias: String) -> List<Path>,
) : IncrementalCompiler {

    override fun compile(request: IcRequest): Result<IcResponse, IcError> =
        try {
            executeCompile(request)
        } catch (t: Throwable) {
            Err(IcError.InternalError(t))
        }

    private fun executeCompile(request: IcRequest): Result<IcResponse, IcError> {
        val builder = toolchain.jvm.jvmCompilationOperationBuilder(request.sources, request.outputDir)
        if (request.classpath.isNotEmpty()) {
            builder.compilerArguments[JvmCompilerArguments.CLASSPATH] =
                request.classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = moduleNameFor(request)

        // ADR 0019 §9: kolt.toml [plugins] → List<CompilerPlugin> translation
        // happens inside the adapter, not in daemon core. An empty list here is
        // a "no plugins requested" signal and is the common case; emitting it
        // unconditionally keeps the wiring obvious to a reader.
        val translatedPlugins = PluginTranslator.translate(request.projectRoot, pluginJarResolver)
        builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS] = translatedPlugins

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
            // User source does not type-check. Daemon core reports this to the
            // client verbatim; B-2b's self-heal retry must NOT fire on this path
            // because a wipe+retry would just reproduce the same type error.
            CompilationResult.COMPILATION_ERROR -> Err(
                IcError.CompilationFailed(
                    messages = listOf("kotlinc reported $compilationResult"),
                ),
            )
            // Compiler-infrastructure failures, not user-code failures. Routed as
            // `InternalError` so B-2b's `wipe workingDir + full recompile retry`
            // path (ADR 0019 §7) can fire: OOM often clears on retry with a fresh
            // heap state, and `COMPILER_INTERNAL_ERROR` is indistinguishable from
            // a thrown `KotlinBuildToolsException` in terms of the right recovery.
            CompilationResult.COMPILATION_OOM_ERROR,
            CompilationResult.COMPILER_INTERNAL_ERROR,
            -> Err(
                IcError.InternalError(
                    RuntimeException("kotlinc reported $compilationResult"),
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
        fun create(
            btaImplJars: List<Path>,
            // Defaults to an empty-result resolver: every plugin alias maps to
            // an empty classpath. Production daemon startup overrides this with
            // a resolver that walks a known plugin-jars directory. B-2a's
            // acceptance criterion 4 requires the translation path to be
            // exercised, not for plugins to actually compile — so even this
            // default still attaches `COMPILER_PLUGINS` when kolt.toml lists
            // enabled entries.
            pluginJarResolver: (alias: String) -> List<Path> = { _ -> emptyList() },
        ): Result<BtaIncrementalCompiler, IcError.InternalError> =
            try {
                require(btaImplJars.isNotEmpty()) {
                    "btaImplJars is empty — daemon must receive kotlin-build-tools-impl classpath via --bta-impl-jars"
                }
                val urls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
                val implLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
                Ok(
                    BtaIncrementalCompiler(
                        toolchain = KotlinToolchains.loadImplementation(implLoader),
                        pluginJarResolver = pluginJarResolver,
                    ),
                )
            } catch (t: Throwable) {
                Err(IcError.InternalError(t))
            }
    }
}
