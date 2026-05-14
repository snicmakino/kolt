package kolt.resolve

import com.github.michaelbull.result.Result
import kolt.config.KoltConfig
import kolt.config.NATIVE_TARGETS
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.infra.output.RenderedDiagnostic
import kolt.infra.output.Severity
import kolt.infra.redactUrlUserinfo

// Per-repository attempt captured by `downloadFromRepositories`. The
// resolver keeps the repository name + URL it tried alongside the underlying
// error so `formatResolveError` can render a per-repo dump (#355). For 404s
// we continue to the next repo and append; any other non-auth error stops
// the loop and surfaces with the singleton attempts list. 401/403 short-
// circuits to AuthFailed and is never appended here.
data class RepositoryAttempt(val repositoryName: String, val url: String, val error: DownloadError)

// Splits "no repositories were tried" from "all attempts failed" from
// "auth blocked the first credentialed try" — each has a distinct
// remediation (config fix vs. network/404 dump vs. token rotation) so the
// formatter renders distinct hints. AuthFailed carries an AuthStateProjection
// (no secrets) rather than the secret-bearing RepositoryAuth so credentials
// never reach the renderer.
sealed class RepositoryDownloadFailure {
  data object NoRepositoriesConfigured : RepositoryDownloadFailure()

  data class AllAttemptsFailed(val attempts: List<RepositoryAttempt>) : RepositoryDownloadFailure()

  data class AuthFailed(
    val repositoryName: String,
    val url: String,
    val statusCode: Int,
    val authState: AuthStateProjection,
  ) : RepositoryDownloadFailure()
}

sealed class ResolveError {
  data class InvalidDependency(val input: String) : ResolveError()

  // `fileName` disambiguates which file mismatched when a single coordinate
  // owns several artifacts on disk (Kotlin/Native variants can publish a
  // platform klib plus cinterop sub-klibs). Null for JVM jars and bundle
  // resolution where the coordinate maps to exactly one file.
  data class Sha256Mismatch(
    val groupArtifact: String,
    val expected: String,
    val actual: String,
    val fileName: String? = null,
  ) : ResolveError()

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

fun formatResolveError(error: ResolveError): RenderedDiagnostic =
  when (error) {
    is ResolveError.InvalidDependency ->
      RenderedDiagnostic(Severity.Error, "invalid dependency '${error.input}'")
    is ResolveError.Sha256Mismatch ->
      RenderedDiagnostic(
        severity = Severity.Error,
        headline =
          buildString {
            append("sha256 mismatch for ")
            append(error.groupArtifact)
            error.fileName?.let { append(" (").append(it).append(')') }
          },
        context = listOf("expected: ${error.expected}", "got:      ${error.actual}"),
      )
    is ResolveError.DownloadFailed ->
      RenderedDiagnostic(
        severity = Severity.Error,
        headline = "failed to download ${error.groupArtifact}",
        context = repositoryDownloadFailureContext(error.failure),
      )
    is ResolveError.MetadataDownloadFailed ->
      RenderedDiagnostic(
        severity = Severity.Error,
        headline = "could not fetch metadata for ${error.groupArtifact}",
        context = repositoryDownloadFailureContext(error.failure),
      )
    is ResolveError.HashComputeFailed ->
      RenderedDiagnostic(Severity.Error, "failed to compute hash for ${error.groupArtifact}")
    is ResolveError.DirectoryCreateFailed ->
      RenderedDiagnostic(Severity.Error, "could not create directory ${error.path}")
    is ResolveError.NoNativeVariant ->
      RenderedDiagnostic(
        Severity.Error,
        "${error.groupArtifact} has no Kotlin/Native variant for target " +
          "'${error.nativeTarget}'",
      )
    is ResolveError.MetadataParseFailed ->
      RenderedDiagnostic(
        Severity.Error,
        "failed to parse Gradle module metadata for ${error.groupArtifact}",
      )
    is ResolveError.MetadataFetchFailed ->
      RenderedDiagnostic(
        Severity.Error,
        "failed to read Gradle module metadata for ${error.groupArtifact}",
      )
    is ResolveError.StrictVersionConflict ->
      RenderedDiagnostic(
        Severity.Error,
        if (error.otherIsStrict)
          "conflicting strict versions on ${error.groupArtifact}: " +
            "${error.strictVersion} and ${error.otherVersion}"
        else
          "strict version conflict on ${error.groupArtifact}: " +
            "${error.strictVersion} required strictly, but ${error.otherVersion} also requested",
      )
    is ResolveError.RejectedVersionResolved ->
      RenderedDiagnostic(
        Severity.Error,
        "resolved ${error.groupArtifact}:${error.version} is rejected by " +
          "constraint '${error.rejectPattern}'",
      )
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

private fun repositoryDownloadFailureContext(failure: RepositoryDownloadFailure): List<String> =
  when (failure) {
    is RepositoryDownloadFailure.NoRepositoriesConfigured ->
      listOf(
        "no repositories configured (add a `[repositories.<name>] url = \"...\"` entry to kolt.toml)"
      )
    is RepositoryDownloadFailure.AllAttemptsFailed ->
      failure.attempts.map { "${it.url} -> ${formatAttemptStatus(it.error)}" }
    // Defensive double-redaction: `failure.url` is already redacted at
    // construction (TransitiveResolver passes it through redactUrlUserinfo),
    // but we re-apply here so any future caller path cannot leak userinfo
    // into the renderer. The operation is idempotent.
    is RepositoryDownloadFailure.AuthFailed -> {
      val phrase = reasonPhrase(failure.statusCode)
      val statusLine =
        if (phrase.isEmpty()) "status: ${failure.statusCode}"
        else "status: ${failure.statusCode} $phrase"
      listOf(
        "repository: ${failure.repositoryName}",
        "url: ${redactUrlUserinfo(failure.url)}",
        statusLine,
        "credentials: ${failure.authState.toDisplayString()}",
        "hint: ${formatAuthHint(failure.statusCode, failure.authState)}",
      )
    }
  }

private fun reasonPhrase(statusCode: Int): String =
  when (statusCode) {
    401 -> "Unauthorized"
    403 -> "Forbidden"
    else -> ""
  }

private fun formatAuthHint(statusCode: Int, authState: AuthStateProjection): String =
  when (authState) {
    AuthStateProjection.NotConfigured ->
      when (statusCode) {
        401 -> "the repository requires authentication; add credentials to kolt.local.toml"
        403 -> "authentication is required"
        else -> ""
      }
    AuthStateProjection.ConfiguredToken,
    AuthStateProjection.ConfiguredBasic ->
      when (statusCode) {
        401 -> "the credentials may be invalid or expired"
        403 -> "the credentials are valid but lack permission for this repository"
        else -> ""
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
  // "group:artifact" of the redirect target when this dep was resolved through
  // a Gradle Module Metadata `available-at` redirect (ADR 0005). Null when no
  // redirect occurred. Persisted into the lockfile so the bundle reuse path
  // can reconstruct cachePath without re-fetching .module.
  val redirectTarget: String? = null,
)

data class ResolveResult(val deps: List<ResolvedDep>, val lockChanged: Boolean)

interface ResolverDeps {
  fun fileExists(path: String): Boolean

  fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>

  fun downloadFile(
    url: String,
    destPath: String,
    headers: Map<String, String>? = null,
  ): Result<Unit, DownloadError>

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

    override fun downloadFile(url: String, destPath: String, headers: Map<String, String>?) =
      kolt.infra.downloadFile(url, destPath, headers)

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
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<ResolveResult, ResolveError> =
  if (config.build.target in NATIVE_TARGETS) resolveNative(config, cacheBase, deps, progress)
  else
    resolveTransitive(
      config,
      existingLock,
      cacheBase,
      deps,
      mainSeeds = mainSeeds,
      testSeeds = testSeeds,
      progress = progress,
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
            redirectTarget = it.redirectTarget,
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
              redirectTarget = it.redirectTarget,
            )
        }
      },
  )
}
