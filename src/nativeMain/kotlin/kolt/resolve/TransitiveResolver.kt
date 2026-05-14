package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.Repository
import kolt.config.toHeaders
import kolt.config.toStateProjection
import kolt.infra.DownloadError
import kolt.infra.redactUrlUserinfo

fun resolveTransitive(
  config: KoltConfig,
  existingLock: Lockfile?,
  cacheBase: String,
  deps: ResolverDeps,
  mainSeeds: Map<String, String> = config.dependencies,
  testSeeds: Map<String, String> = emptyMap(),
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<ResolveResult, ResolveError> {
  val repos = config.repositories.values.toList()

  val basePomLookup = createPomLookup(repos, cacheBase, deps)

  val redirects = mutableMapOf<String, String>()
  val moduleLookup = createModuleLookup(repos, cacheBase, deps)

  val pomLookup: (String, String) -> PomInfo? = { groupArtifact, version ->
    val redirect = moduleLookup(groupArtifact, version)
    if (redirect != null) {
      val redirectedGA = "${redirect.group}:${redirect.module}"
      redirects[groupArtifact] = redirectedGA
      basePomLookup(redirectedGA, redirect.version)
    } else {
      basePomLookup(groupArtifact, version)
    }
  }

  val nodes =
    fixpointResolve(
        mainSeeds = mainSeeds,
        testSeeds = testSeeds,
        childLookup = pomChildLookup(pomLookup),
      )
      .getOrElse { error ->
        return Err(error)
      }

  return materialize(nodes, redirects, config, existingLock, cacheBase, deps, repos, progress)
}

// Best-effort sources fetch. Sources are editor UX — missing upstream
// sources are common (esp. internal artifacts) and network hiccups on
// sources must never fail the build. A probe is attempted only when the
// binary was freshly downloaded in this resolve, so that subsequent
// resolves on a warm cache do not re-hit the network for negative
// results. Ground truth is cache presence: if the file lands on disk
// (either now or from a prior run) we emit its path; otherwise null.
internal fun resolveSourcesPath(
  coord: Coordinate,
  cacheBase: String,
  repos: List<Repository>,
  deps: ResolverDeps,
  binaryWasCached: Boolean,
): String? {
  val sourcesCachePath = "$cacheBase/${buildSourcesCachePath(coord)}"
  if (deps.fileExists(sourcesCachePath)) return sourcesCachePath
  if (binaryWasCached) return null
  val error =
    downloadFromRepositories(
        repos,
        sourcesCachePath,
        { buildSourcesDownloadUrl(coord, it.url) },
        deps::downloadFile,
      )
      .getError()
  return if (error == null) sourcesCachePath else null
}

// Falls back to next repo only on 404; 401/403 short-circuits to AuthFailed
// so a misconfigured private repo never falls through to a public mirror
// (Req 6.1, 6.2, 6.4). Any other non-2xx stops the loop and surfaces with
// the attempts list. Each attempt records the repository name + URL we
// tried so the resolver can dump per-repo status (#355). Empty repos
// surfaces as `NoRepositoriesConfigured` so the user gets a config-fix
// hint instead of a "tried nothing" misread.
internal fun downloadFromRepositories(
  repos: List<Repository>,
  destPath: String,
  urlBuilder: (Repository) -> String,
  download: (String, String, Map<String, String>?) -> Result<Unit, DownloadError>,
  onRetry: (Repository) -> Unit = {},
): Result<Unit, RepositoryDownloadFailure> {
  if (repos.isEmpty()) return Err(RepositoryDownloadFailure.NoRepositoriesConfigured)
  val attempts = mutableListOf<RepositoryAttempt>()
  for (i in repos.indices) {
    val repo = repos[i]
    val url = urlBuilder(repo)
    val headers = repo.auth?.toHeaders()
    val error = download(url, destPath, headers).getError()
    if (error == null) return Ok(Unit)
    if (error is DownloadError.HttpFailed) {
      if (error.statusCode == 401 || error.statusCode == 403) {
        return Err(
          RepositoryDownloadFailure.AuthFailed(
            repositoryName = repo.name,
            url = redactUrlUserinfo(error.url),
            statusCode = error.statusCode,
            authState = repo.auth.toStateProjection(),
          )
        )
      }
      if (error.statusCode == 404) {
        attempts.add(RepositoryAttempt(repo.name, url, error))
        if (i + 1 < repos.size) {
          onRetry(repos[i + 1])
        }
        continue
      }
    }
    attempts.add(RepositoryAttempt(repo.name, url, error))
    return Err(RepositoryDownloadFailure.AllAttemptsFailed(attempts))
  }
  return Err(RepositoryDownloadFailure.AllAttemptsFailed(attempts))
}

internal fun createPomLookup(
  repos: List<Repository>,
  cacheBase: String,
  deps: ResolverDeps,
): (String, String) -> PomInfo? {
  val cache = mutableMapOf<String, PomInfo?>()

  return { groupArtifact, version ->
    val cacheKey = "$groupArtifact:$version"
    cache.getOrPut(cacheKey) {
      val parts = groupArtifact.split(":")
      if (parts.size == 2) {
        val coord = Coordinate(parts[0], parts[1], version)
        val pomCachePath = "$cacheBase/${buildPomCachePath(coord)}"

        if (!deps.fileExists(pomCachePath)) {
          val parentDir = pomCachePath.substringBeforeLast('/')
          val dirOk = deps.ensureDirectoryRecursive(parentDir).getOrElse { null }
          if (dirOk != null) {
            downloadFromRepositories(
                repos,
                pomCachePath,
                { buildPomDownloadUrl(coord, it.url) },
                deps::downloadFile,
              )
              .getOrElse { null }
          }
        }

        val content = deps.readFileContent(pomCachePath).getOrElse { null }
        content?.let { parsePom(it).getOrElse { null } }
      } else {
        null
      }
    }
  }
}

private fun createModuleLookup(
  repos: List<Repository>,
  cacheBase: String,
  deps: ResolverDeps,
): (String, String) -> JvmRedirect? {
  // Sentinel to distinguish "checked, no redirect" from "not yet checked"
  val noRedirect = JvmRedirect("", "", "")
  val cache = mutableMapOf<String, JvmRedirect>()

  return { groupArtifact, version ->
    val cacheKey = "$groupArtifact:$version"
    val cached = cache[cacheKey]
    if (cached != null) {
      if (cached === noRedirect) null else cached
    } else {
      val result = checkModuleFile(groupArtifact, version, repos, cacheBase, deps)
      cache[cacheKey] = result ?: noRedirect
      result
    }
  }
}

private fun checkModuleFile(
  groupArtifact: String,
  version: String,
  repos: List<Repository>,
  cacheBase: String,
  deps: ResolverDeps,
): JvmRedirect? {
  val parts = groupArtifact.split(":")
  if (parts.size != 2) return null
  val coord = Coordinate(parts[0], parts[1], version)
  val moduleCachePath = "$cacheBase/${buildModuleCachePath(coord)}"

  if (!deps.fileExists(moduleCachePath)) {
    val parentDir = moduleCachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return null
    }
    downloadFromRepositories(
        repos,
        moduleCachePath,
        { buildModuleDownloadUrl(coord, it.url) },
        deps::downloadFile,
      )
      .getOrElse {
        return null
      }
  }

  val content =
    deps.readFileContent(moduleCachePath).getOrElse {
      return null
    }
  return parseJvmRedirect(content)
}

// KMP redirects: lockfile keys use original groupArtifact, but hash/path use the redirected JAR.
private fun materialize(
  nodes: List<DependencyNode>,
  redirects: Map<String, String>,
  config: KoltConfig,
  existingLock: Lockfile?,
  cacheBase: String,
  deps: ResolverDeps,
  repos: List<Repository>,
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<ResolveResult, ResolveError> {
  var lockChanged = false
  val resolvedDeps = mutableListOf<ResolvedDep>()

  // Pre-count uncached JAR nodes so the total `M` is known before any
  // emission. A node whose coordinate fails to parse is excluded from `M`
  // here; the main loop returns `InvalidDependency` for it before any
  // emission happens.
  val total =
    nodes.count { node ->
      val jarGa = redirects[node.groupArtifact] ?: node.groupArtifact
      val coord =
        parseCoordinate(jarGa, node.version).getOrElse {
          return@count false
        }
      !deps.fileExists("$cacheBase/${buildCachePath(coord)}")
    }
  var index = 0

  for (node in nodes) {
    val jarGroupArtifact = redirects[node.groupArtifact] ?: node.groupArtifact
    val coord =
      parseCoordinate(jarGroupArtifact, node.version).getOrElse {
        return Err(ResolveError.InvalidDependency(node.groupArtifact))
      }

    val relativePath = buildCachePath(coord)
    val fullCachePath = "$cacheBase/$relativePath"
    val lockEntry = existingLock?.dependencies?.get(node.groupArtifact)

    val binaryWasCached = deps.fileExists(fullCachePath)
    if (!binaryWasCached) {
      val parentDir = fullCachePath.substringBeforeLast('/')
      deps.ensureDirectoryRecursive(parentDir).getOrElse {
        return Err(ResolveError.DirectoryCreateFailed(parentDir))
      }
      index += 1
      progress.onArtifactStart(index, total, node.groupArtifact, node.version)
      downloadFromRepositories(
          repos,
          fullCachePath,
          { buildDownloadUrl(coord, it.url) },
          deps::downloadFile,
          onRetry = { repo -> progress.onRetryAgainst(repo.url) },
        )
        .getOrElse { failure ->
          return Err(ResolveError.DownloadFailed(node.groupArtifact, failure))
        }
      lockChanged = true
    }

    val hash =
      deps.computeSha256(fullCachePath).getOrElse { error ->
        return Err(ResolveError.HashComputeFailed(node.groupArtifact, error))
      }

    if (lockEntry != null) {
      if (lockEntry.version != node.version) {
        lockChanged = true
      } else if (lockEntry.sha256 != hash) {
        return Err(ResolveError.Sha256Mismatch(node.groupArtifact, lockEntry.sha256, hash))
      }
    } else {
      lockChanged = true
    }

    val sourcesPath = resolveSourcesPath(coord, cacheBase, repos, deps, binaryWasCached)

    resolvedDeps.add(
      ResolvedDep(
        groupArtifact = node.groupArtifact,
        version = node.version,
        sha256 = hash,
        cachePath = fullCachePath,
        transitive = !node.direct,
        origin = node.origin,
        sourcesPath = sourcesPath,
        redirectTarget = redirects[node.groupArtifact],
      )
    )
  }

  if (existingLock != null) {
    if (
      existingLock.kotlin != config.kotlin.version ||
        existingLock.jvmTarget != config.build.jvmTarget
    ) {
      lockChanged = true
    }
    val resolvedKeys = nodes.map { it.groupArtifact }.toSet()
    for (key in existingLock.dependencies.keys) {
      if (key !in resolvedKeys) {
        lockChanged = true
      }
    }
  }

  return Ok(ResolveResult(resolvedDeps, lockChanged))
}
