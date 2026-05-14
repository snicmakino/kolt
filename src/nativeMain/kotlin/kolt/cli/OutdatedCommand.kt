package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.Repository
import kolt.config.resolveKoltPaths
import kolt.infra.downloadFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.eprintln
import kolt.infra.output.eprintError
import kolt.infra.readFileAsString
import kolt.resolve.RepositoryDownloadFailure
import kolt.resolve.buildMetadataDownloadUrl
import kolt.resolve.downloadFromRepositories
import kolt.resolve.formatAttemptStatus
import kolt.resolve.parseMetadataXml

internal fun doOutdated(args: List<String>): Result<Unit, Int> {
  val opts =
    parseOutdatedArgs(args).getOrElse { error ->
      reportArgsError(error)
      return Err(EXIT_CONFIG_ERROR)
    }

  return withDependencyLock { doOutdatedInner(opts) }
}

private fun doOutdatedInner(opts: OutdatedOptions): Result<Unit, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintError("$it")
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  val repos = config.repositories.values.toList()
  val report =
    computeOutdated(
      mainDeps = config.dependencies,
      testDeps = config.testDependencies,
      fetchLatest = { group, artifact ->
        fetchLatestVersionString(group, artifact, repos, paths.cacheBase)
      },
    )

  val filtered = applyOutdatedFilter(report, opts.severities)
  val output =
    when (opts.format) {
      OutdatedFormat.Text -> formatOutdatedText(filtered)
      OutdatedFormat.Json -> formatOutdatedJson(filtered)
    }
  println(output)

  // Per-row fetch errors stay non-fatal but the overall exit code is non-zero
  // so CI / scripts can detect "we couldn't reach Maven Central for some
  // deps." Filter is applied first because applyOutdatedFilter always keeps
  // error rows.
  val hasErrors = filtered.main.any { it.error != null } || filtered.test.any { it.error != null }
  return if (hasErrors) Err(EXIT_DEPENDENCY_ERROR) else Ok(Unit)
}

private fun fetchLatestVersionString(
  group: String,
  artifact: String,
  repos: List<Repository>,
  cacheBase: String,
): Result<String, String> {
  val groupPath = group.replace('.', '/')
  val metadataPath = "$cacheBase/$groupPath/$artifact/maven-metadata.xml"

  ensureDirectoryRecursive("$cacheBase/$groupPath/$artifact").getOrElse { error ->
    return Err("could not create cache directory ${error.path}")
  }

  val failure =
    downloadFromRepositories(
        repos,
        metadataPath,
        { repo -> buildMetadataDownloadUrl(group, artifact, repo.url) },
        ::downloadFile,
      )
      .getError()
  if (failure != null) {
    return Err(shortMetadataFailure(failure))
  }

  val xml =
    readFileAsString(metadataPath).getOrElse { error ->
      return Err("could not read ${error.path}")
    }

  val resolution =
    parseMetadataXml(xml).getOrElse { error ->
      return Err(error.message)
    }
  // `fallbackToPrerelease` means there was no stable release at all — the
  // dep is published only as -RC / -alpha / etc. Surface as an error row so
  // the user knows we picked a non-stable answer instead of silently
  // recommending a prerelease upgrade.
  if (resolution.fallbackToPrerelease) {
    return Err("no stable release found; latest is prerelease ${resolution.version}")
  }
  return Ok(resolution.version)
}

// `formatResolveError` (used by `kolt add`) renders one repository attempt
// per line — fine for stderr, but `kolt outdated` packs the whole error
// into a single column-aligned row's `(error: ...)` suffix, so we render
// the failure inline instead. Statuses come from the same
// `formatAttemptStatus` helper as the multi-line variant for parity.
private fun shortMetadataFailure(failure: RepositoryDownloadFailure): String =
  when (failure) {
    is RepositoryDownloadFailure.NoRepositoriesConfigured -> "no repositories configured"
    is RepositoryDownloadFailure.AllAttemptsFailed -> {
      val statuses = failure.attempts.joinToString(", ") { formatAttemptStatus(it.error) }
      "metadata fetch failed ($statuses)"
    }
    is RepositoryDownloadFailure.AuthFailed ->
      "metadata fetch failed (${failure.repositoryName}: ${failure.statusCode})"
  }

private fun reportArgsError(error: OutdatedArgsError) {
  when (error) {
    is OutdatedArgsError.UnknownFlag -> eprintError("unknown flag '${error.flag}'")
    is OutdatedArgsError.MissingFilterValue ->
      eprintError("--filter requires a value (e.g. --filter major,minor)")
    is OutdatedArgsError.InvalidFilter ->
      eprintError("invalid filter token '${error.token}' (expected major|minor|patch)")
  }
  eprintln("usage: kolt outdated [--filter <major,minor,patch>] [--json]")
}
