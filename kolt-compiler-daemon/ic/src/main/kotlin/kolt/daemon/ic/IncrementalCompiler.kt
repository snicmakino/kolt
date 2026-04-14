package kolt.daemon.ic

import com.github.michaelbull.result.Result
import java.nio.file.Path

// Daemon-core-facing entry point for incremental JVM compilation, defined by
// ADR 0019 §3. This file intentionally imports nothing from `kotlin.build.tools.*`:
// the entire @ExperimentalBuildToolsApi surface is isolated inside the
// BtaIncrementalCompiler implementation (and the child URLClassLoader it owns).
// Import of a BTA type anywhere outside the ic subproject is an ADR 0019 violation
// and is enforced by human review per issue #112 acceptance criterion 2.
//
// IcRequest deliberately does not carry compilerArguments / pluginClasspaths /
// pluginOptions — those are translated inside the adapter from kolt.toml [plugins]
// (ADR 0019 §9). Keeping the translation inside the adapter preserves the
// "daemon core carries no BTA-shaped fields" invariant.
interface IncrementalCompiler {
    fun compile(request: IcRequest): Result<IcResponse, IcError>
}

data class IcRequest(
    val projectId: String,
    // The project root directory (containing `kolt.toml`). The adapter reads the
    // TOML file directly to translate plugin settings per ADR 0019 §9; this is
    // the only way the @ExperimentalBuildToolsApi shape of plugin arguments
    // stays out of daemon core. ADR §3's original data class did not list
    // projectRoot, but §9 implicitly requires it — this is a minor inter-
    // section fix, not a scope widening.
    val projectRoot: Path,
    val sources: List<Path>,
    val classpath: List<Path>,
    val outputDir: Path,
    val workingDir: Path,
)

data class IcResponse(
    val wallMillis: Long,
    val compiledFileCount: Int?,
    val status: Status,
)

enum class Status { SUCCESS, ERROR }

sealed interface IcError {
    data class CompilationFailed(val messages: List<String>) : IcError
    data class InternalError(val cause: Throwable) : IcError
}
