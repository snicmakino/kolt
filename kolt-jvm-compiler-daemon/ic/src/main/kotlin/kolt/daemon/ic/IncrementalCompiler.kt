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
// IcRequest carries no BTA-shaped fields per ADR 0019 §9; plugin jars
// arrive via the daemon-level `--plugin-jars` map and translate inside
// the adapter.
interface IncrementalCompiler {
  fun compile(request: IcRequest): Result<IcResponse, IcError>
}

data class IcRequest(
  val projectId: String,
  // Source for `LanguageVersionTranslator` reading `[kotlin]` per ADR 0019 §9.
  val projectRoot: Path,
  val sources: List<Path>,
  val classpath: List<Path>,
  val outputDir: Path,
  val workingDir: Path,
  // #376: the test compile passes the main classes directory here so
  // BTA emits `-Xfriend-paths=<dir>` and grants `internal` access from
  // test sources to main classes. Empty for the main compile.
  val friendPaths: List<Path> = emptyList(),
)

// Success-only payload. Before B-2b the type carried a `status: Status`
// field with a `SUCCESS / ERROR` enum, but the ERROR path was dead code:
// the only producer, `BtaIncrementalCompiler`, converts every non-success
// `CompilationResult` into an `IcError` instead of an `IcResponse(status =
// ERROR)`. Collapsing to an `Ok(IcResponse)` / `Err(IcError)` split at the
// Result level retires B-2a review carryover #3 and removes the "dead
// `if (status == SUCCESS) 0 else 1`" branch from daemon core.
data class IcResponse(val wallMillis: Long, val compiledFileCount: Int?)

sealed interface IcError {
  data class CompilationFailed(val messages: List<String>) : IcError

  data class InternalError(val cause: Throwable) : IcError
}
