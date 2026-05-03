package kolt.resolve

import com.github.michaelbull.result.Result
import kolt.config.KoltConfig
import kolt.config.NATIVE_TARGETS
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error

// Per-repository attempt captured by `downloadFromRepositories`. The
// resolver keeps the URL it tried alongside the underlying error so
// `formatResolveError` can render a per-repo dump (#355). For 404s we
// continue to the next repo and append; any other error stops the loop
// and surfaces with the singleton attempts list.
data class RepositoryAttempt(val url: String, val error: DownloadError)

// Splits "no repositories were tried" from "all attempts failed" — the
// two have different remediations (config fix vs. network/auth) so the
// formatter renders distinct hints.
sealed class RepositoryDownloadFailure {
  data object NoRepositoriesConfigured : RepositoryDownloadFailure()

  data class AllAttemptsFailed(val attempts: List<RepositoryAttempt>) : RepositoryDownloadFailure()
}

sealed class ResolveError {
  data class InvalidDependency(val input: String) : ResolveError()

  data class Sha256Mismatch(val groupArtifact: String, val expected: String, val actual: String) :
    ResolveError()

  data class DownloadFailed(val groupArtifact: String, val failure: RepositoryDownloadFailure) :
    ResolveError()

  data class MetadataDownloadFailed(
    val groupArtifact: String,
    val failure: RepositoryDownloadFailure,
  ) : ResolveError()

  data class HashComputeFailed(val groupArtifact: String, val error: Sha256Error) : ResolveError()

  data class DirectoryCreateFailed(val path: String) : ResolveError()

  data class NoNativeVariant(val groupArtifact: String, val nativeTarget: String) : ResolveError()

  data class MetadataParseFailed(val groupArtifact: String) : ResolveError()

  data class MetadataFetchFailed(val groupArtifact: String) : ResolveError()

  data class StrictVersionConflict(
    val groupArtifact: String,
    val strictVersion: String,
    val otherVersion: String,
    val otherIsStrict: Boolean = false,
  ) : ResolveError()

  data class RejectedVersionResolved(
    val groupArtifact: String,
    val version: String,
    val rejectPattern: String,
  ) : ResolveError()
}

fun formatResolveError(error: ResolveError): String =
  when (error) {
    is ResolveError.InvalidDependency -> "error: invalid dependency '${error.input}'"
    is ResolveError.Sha256Mismatch ->
      buildString {
        appendLine("error: sha256 mismatch for ${error.groupArtifact}")
        appendLine("  expected: ${error.expected}")
        append("  got:      ${error.actual}")
      }
    is ResolveError.DownloadFailed ->
      buildString {
        append("error: failed to download ${error.groupArtifact}")
        appendRepositoryDownloadFailure(error.failure)
      }
    is ResolveError.MetadataDownloadFailed ->
      buildString {
        append("error: could not fetch metadata for ${error.groupArtifact}")
        appendRepositoryDownloadFailure(error.failure)
      }
    is ResolveError.HashComputeFailed -> "error: failed to compute hash for ${error.groupArtifact}"
    is ResolveError.DirectoryCreateFailed -> "error: could not create directory ${error.path}"
    is ResolveError.NoNativeVariant ->
      "error: ${error.groupArtifact} has no Kotlin/Native variant for target '${error.nativeTarget}'"
    is ResolveError.MetadataParseFailed ->
      "error: failed to parse Gradle module metadata for ${error.groupArtifact}"
    is ResolveError.MetadataFetchFailed ->
      "error: failed to read Gradle module metadata for ${error.groupArtifact}"
    is ResolveError.StrictVersionConflict ->
      if (error.otherIsStrict)
        "error: conflicting strict versions on ${error.groupArtifact}: " +
          "${error.strictVersion} and ${error.otherVersion}"
      else
        "error: strict version conflict on ${error.groupArtifact}: " +
          "${error.strictVersion} required strictly, but ${error.otherVersion} also requested"
    is ResolveError.RejectedVersionResolved ->
      "error: resolved ${error.groupArtifact}:${error.version} is rejected by constraint '${error.rejectPattern}'"
  }

internal fun formatAttemptStatus(error: DownloadError): String =
  when (error) {
    is DownloadError.HttpFailed -> error.statusCode.toString()
    is DownloadError.NetworkError -> error.message
    // The download itself reached the server and got a 200 — the *local*
    // write of the tempfile is what failed. Rendering on the same per-repo
    // line keeps the dump uniform; the wording flags it as local so the
    // URL doesn't read as the offender.
    is DownloadError.WriteFailed -> "local write failed (${error.path})"
  }

private fun StringBuilder.appendRepositoryDownloadFailure(failure: RepositoryDownloadFailure) {
  when (failure) {
    is RepositoryDownloadFailure.NoRepositoriesConfigured ->
      append("\n  no repositories configured (add a `[repositories]` entry to kolt.toml)")
    is RepositoryDownloadFailure.AllAttemptsFailed ->
      for (attempt in failure.attempts) {
        append("\n  ${attempt.url} -> ${formatAttemptStatus(attempt.error)}")
      }
  }
}

enum class Origin {
  MAIN,
  TEST,
}

data class ResolvedDep(
  val groupArtifact: String,
  val version: String,
  val sha256: String,
  val cachePath: String,
  val transitive: Boolean = false,
  val origin: Origin = Origin.MAIN,
  val sourcesPath: String? = null,
)

data class ResolveResult(val deps: List<ResolvedDep>, val lockChanged: Boolean)

interface ResolverDeps {
  fun fileExists(path: String): Boolean

  fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>

  fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError>

  fun computeSha256(filePath: String): Result<String, Sha256Error>

  fun readFileContent(path: String): Result<String, OpenFailed>
}

// Real-IO ResolverDeps wired to kolt.infra. Lives here (rather than in
// kolt.cli) so non-CLI callers — currently kolt.build.daemon.BtaImplFetcher
// — can use it without an upward import.
internal fun defaultResolverDeps(): ResolverDeps =
  object : ResolverDeps {
    override fun fileExists(path: String): Boolean = kolt.infra.fileExists(path)

    override fun ensureDirectoryRecursive(path: String) = kolt.infra.ensureDirectoryRecursive(path)

    override fun downloadFile(url: String, destPath: String) =
      kolt.infra.downloadFile(url, destPath)

    override fun computeSha256(filePath: String) = kolt.infra.computeSha256(filePath)

    override fun readFileContent(path: String) = kolt.infra.readFileAsString(path)
  }

fun resolve(
  config: KoltConfig,
  existingLock: Lockfile?,
  cacheBase: String,
  deps: ResolverDeps,
  mainSeeds: Map<String, String> = config.dependencies,
  testSeeds: Map<String, String> = emptyMap(),
): Result<ResolveResult, ResolveError> =
  if (config.build.target in NATIVE_TARGETS) resolveNative(config, cacheBase, deps)
  else
    resolveTransitive(
      config,
      existingLock,
      cacheBase,
      deps,
      mainSeeds = mainSeeds,
      testSeeds = testSeeds,
    )

fun buildLockfileFromResolved(
  config: KoltConfig,
  deps: List<ResolvedDep>,
  bundleDeps: Map<String, List<ResolvedDep>> = emptyMap(),
): Lockfile {
  return Lockfile(
    version = LOCKFILE_VERSION,
    kotlin = config.kotlin.version,
    jvmTarget = config.build.jvmTarget,
    dependencies =
      deps.associate {
        it.groupArtifact to
          LockEntry(
            version = it.version,
            sha256 = it.sha256,
            transitive = it.transitive,
            test = it.origin == Origin.TEST,
          )
      },
    classpathBundles =
      bundleDeps.mapValues { (_, bundle) ->
        bundle.associate {
          it.groupArtifact to
            LockEntry(
              version = it.version,
              sha256 = it.sha256,
              transitive = it.transitive,
              // Bundle entries are scope-distinct from test deps; the test bit
              // applies only to [test-dependencies] origin within `dependencies`.
              test = false,
            )
        }
      },
  )
}
