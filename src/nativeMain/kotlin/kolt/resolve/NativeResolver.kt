package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.Repository
import kolt.config.konanTargetGradleName
import kolt.infra.DownloadError
import kolt.infra.eprintln

// Kotlin/Native bundles the stdlib in the konanc distribution — it must be
// skipped during dependency resolution even though Gradle module metadata
// declares it as a dependency on every native variant.
// kotlin-stdlib and kotlin-stdlib-common are both bundled by konanc and must
// not be resolved from Maven: the former has no native variant published in
// the shape our resolver expects, and the latter (a pre-Gradle-metadata
// artifact from the 1.8-era split) publishes only a `.pom`, not a `.module`,
// so the native resolver's `.module` fetch would 404. See ADR 0011.
private fun isKotlinStdlib(groupArtifact: String): Boolean =
  groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib" ||
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib-common"

// Distinguishes "every repo replied 404" from any other download failure
// (5xx, network, local write). Only the all-404 case can be interpreted as
// "this artifact is structurally not published" and trigger the `.pom`
// fallback; transient failures must surface as the existing DownloadFailed.
internal fun is404OnAllAttempts(error: ResolveError): Boolean {
  if (error !is ResolveError.DownloadFailed) return false
  val failure = error.failure
  if (failure !is RepositoryDownloadFailure.AllAttemptsFailed) return false
  if (failure.attempts.isEmpty()) return false
  return failure.attempts.all { attempt ->
    val downloadError = attempt.error
    downloadError is DownloadError.HttpFailed && downloadError.statusCode == 404
  }
}

internal sealed class NativeResolved {
  data class Klib(val redirect: NativeRedirect, val artifact: NativeArtifact) : NativeResolved()

  data class JvmOnly(val coordinate: Coordinate) : NativeResolved()
}

/**
 * Resolves Kotlin/Native dependencies via the shared resolution kernel ([fixpointResolve]).
 *
 * Native-specific responsibilities live here:
 * - Konan target selection and module redirect handling (via `fetchNativeMetadata`)
 * - klib download and sha256 verification
 * - Skipping stdlib artifacts konanc bundles (both as direct deps and children)
 *
 * Fixpoint loop, highest-wins, direct-wins, strict/rejects, and superseded-child rollback all live
 * in the kernel.
 *
 * The lockfile is not consulted or written for native targets in Phase B; `lockChanged` is always
 * false. Lockfile support for native comes later.
 */
fun resolveNative(
  config: KoltConfig,
  cacheBase: String,
  deps: ResolverDeps,
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
  noteSink: (String) -> Unit = ::eprintln,
): Result<ResolveResult, ResolveError> {
  val nativeTarget = konanTargetGradleName(config.build.target)
  val repos = config.repositories.values.toList()

  for ((groupArtifact, _) in config.dependencies) {
    if (isKotlinStdlib(groupArtifact)) continue
    parseCoordinate(groupArtifact, "0").getOrElse {
      return Err(ResolveError.InvalidDependency(groupArtifact))
    }
  }

  val directDeps = config.dependencies.filterKeys { !isKotlinStdlib(it) }

  // Populated by childLookup during resolution and reused for materialization.
  val processed = mutableMapOf<String, NativeResolved>()
  val childLookup = makeNativeChildLookup(processed, nativeTarget, cacheBase, repos, deps)

  val nodes =
    fixpointResolve(mainSeeds = directDeps, childLookup = childLookup).getOrElse {
      return Err(it)
    }

  val resolvedDeps = mutableListOf<ResolvedDep>()

  // Pre-count uncached klib nodes so the total `M` is known before any
  // emission. Nodes whose redirect metadata is missing are excluded from
  // `M` here; the main loop returns `MetadataParseFailed` for them before
  // any emission happens. A variant with multiple klibs (platform klib +
  // cinterop sub-klibs) counts as a single node — progress ticks per
  // artifact, not per klib file, so the M/N counter stays artifact-shaped.
  val total =
    nodes.count { node ->
      val resolved = processed["${node.groupArtifact}:${node.version}"] ?: return@count false
      when (resolved) {
        is NativeResolved.Klib -> {
          val targetCoord =
            Coordinate(resolved.redirect.group, resolved.redirect.module, resolved.redirect.version)
          resolved.artifact.klibFiles.any { klibFile ->
            !deps.fileExists("$cacheBase/${buildKlibCachePath(targetCoord, klibFile.url)}")
          }
        }
        // JvmOnly nodes have no klib artifact to download, so they contribute
        // nothing to the M/N progress total. Keeping them out here aligns the
        // total with the main loop's index increment, which is Klib-only.
        is NativeResolved.JvmOnly -> false
      }
    }
  var index = 0

  for (node in nodes) {
    val resolved =
      processed["${node.groupArtifact}:${node.version}"]
        ?: return Err(ResolveError.MetadataParseFailed(node.groupArtifact))

    when (resolved) {
      is NativeResolved.Klib -> {
        val targetCoord =
          Coordinate(resolved.redirect.group, resolved.redirect.module, resolved.redirect.version)
        val klibPaths =
          resolved.artifact.klibFiles.map { klibFile ->
            klibFile to "$cacheBase/${buildKlibCachePath(targetCoord, klibFile.url)}"
          }
        val anyMissing = klibPaths.any { (_, path) -> !deps.fileExists(path) }
        if (anyMissing) {
          index += 1
          progress.onArtifactStart(index, total, node.groupArtifact, node.version)
        }

        for ((klibFile, klibCachePath) in klibPaths) {
          if (!deps.fileExists(klibCachePath)) {
            val parentDir = klibCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
              return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            downloadFromRepositories(
                repos,
                klibCachePath,
                { buildKlibDownloadUrl(targetCoord, it.url, klibFile.url) },
                deps::downloadFile,
                onRetry = { repo -> progress.onRetryAgainst(repo.url) },
              )
              .getOrElse { failure ->
                return Err(ResolveError.DownloadFailed(node.groupArtifact, failure))
              }
          }

          val actualHash =
            deps.computeSha256(klibCachePath).getOrElse {
              return Err(ResolveError.HashComputeFailed(node.groupArtifact, it))
            }
          if (actualHash != klibFile.sha256) {
            return Err(
              ResolveError.Sha256Mismatch(
                groupArtifact = node.groupArtifact,
                expected = klibFile.sha256,
                actual = actualHash,
                fileName = klibFile.url,
              )
            )
          }

          resolvedDeps.add(
            ResolvedDep(
              groupArtifact = node.groupArtifact,
              version = node.version,
              sha256 = actualHash,
              cachePath = klibCachePath,
              transitive = !node.direct,
            )
          )
        }
      }
      is NativeResolved.JvmOnly -> {
        // Direct JvmOnly deps (other than the kotlin-stdlib bundle already
        // filtered out in `resolveNative` above) cannot be silently skipped:
        // the user declared the artifact in `[dependencies]`, so surface
        // NoNativeVariant rather than continuing.
        if (node.direct) {
          return Err(ResolveError.NoNativeVariant(node.groupArtifact, nativeTarget))
        }
        // ADR 0011 §4: structural skip generalises the kotlin-stdlib-common
        // silent-skip policy; stdlib coordinates remain silent here, every
        // other JvmOnly transitive surfaces a single stderr note so a build
        // log records which artifact had no native variant.
        if (!isKotlinStdlib(node.groupArtifact)) {
          noteSink(
            "note: ${node.groupArtifact}:${node.version} has no Gradle Module Metadata; skipping for native target"
          )
        }
      }
    }
  }

  return Ok(ResolveResult(deps = resolvedDeps, lockChanged = false))
}

internal fun makeNativeChildLookup(
  processed: MutableMap<String, NativeResolved>,
  nativeTarget: String,
  cacheBase: String,
  repos: List<Repository>,
  deps: ResolverDeps,
): (String, String) -> Result<List<Child>, ResolveError> = lookup@{ ga, v ->
  val cacheKey = "$ga:$v"
  val cached = processed[cacheKey]
  val native =
    if (cached != null) {
      cached
    } else {
      val fetched =
        fetchNativeMetadata(ga, v, nativeTarget, cacheBase, repos, deps).getOrElse {
          return@lookup Err(it)
        }
      processed[cacheKey] = fetched
      fetched
    }
  val children =
    when (native) {
      is NativeResolved.Klib ->
        native.artifact.dependencies.mapNotNull { d ->
          val depGA = "${d.group}:${d.module}"
          if (isKotlinStdlib(depGA)) return@mapNotNull null
          Child(groupArtifact = depGA, version = d.version, strict = d.strict, rejects = d.rejects)
        }
      // JvmOnly nodes have no Gradle Module Metadata, so no transitive children
      // are descended.
      is NativeResolved.JvmOnly -> emptyList()
    }
  Ok(children)
}

/**
 * Builds a lookup function for [buildNativeDependencyTree]. For each (groupArtifact, version),
 * fetches and parses the Gradle module metadata twice (root + redirect target) and returns a
 * [NativeNodeInfo] containing the redirected display coordinate and the transitive dependencies
 * from the linux_x64 variant. Returns null on any fetch or parse failure so the tree walker can
 * render a partial graph instead of aborting.
 *
 * Results are memoized by (groupArtifact, version) so diamond dependencies don't re-fetch and
 * re-parse the same `.module` files on every occurrence, matching [createPomLookup]'s caching
 * behaviour.
 */
fun createNativeLookup(
  repos: List<Repository>,
  cacheBase: String,
  deps: ResolverDeps,
  nativeTarget: String,
): (String, String) -> NativeNodeInfo? {
  val cache = mutableMapOf<String, NativeNodeInfo?>()

  return { groupArtifact, version ->
    val cacheKey = "$groupArtifact:$version"
    cache.getOrPut(cacheKey) {
      val resolved =
        fetchNativeMetadata(
            groupArtifact = groupArtifact,
            version = version,
            nativeTarget = nativeTarget,
            cacheBase = cacheBase,
            repos = repos,
            deps = deps,
          )
          .getOrElse { null }

      resolved?.let {
        when (it) {
          is NativeResolved.Klib ->
            NativeNodeInfo(
              displayGroupArtifact = "${it.redirect.group}:${it.redirect.module}",
              displayVersion = it.redirect.version,
              dependencies =
                it.artifact.dependencies.map { dep -> "${dep.group}:${dep.module}" to dep.version },
            )
          // JvmOnly artifacts have no Gradle Module Metadata, so the tree
          // surfaces them as a leaf at the original (root) coordinate with no
          // children. The "JVM-only" UI label is intentionally out of scope.
          is NativeResolved.JvmOnly ->
            NativeNodeInfo(
              displayGroupArtifact = "${it.coordinate.group}:${it.coordinate.artifact}",
              displayVersion = it.coordinate.version,
              dependencies = emptyList(),
            )
        }
      }
    }
  }
}

/**
 * Fetches and parses both the root `.module` (for the available-at redirect) and the
 * platform-specific `.module` (for the klib file + dependencies) for a single coordinate.
 *
 * When the root `.module` returns 404 from every repository, falls back to fetching the same
 * coordinate's `.pom` for existence-only confirmation. If the `.pom` is available, the artifact is
 * structurally JVM-only (no Gradle Module Metadata published) and is returned as
 * `NativeResolved.JvmOnly`. The `.pom` body is not parsed; downstream materialization decides how
 * to handle the variant.
 */
internal fun fetchNativeMetadata(
  groupArtifact: String,
  version: String,
  nativeTarget: String,
  cacheBase: String,
  repos: List<Repository>,
  deps: ResolverDeps,
): Result<NativeResolved, ResolveError> {
  val rootCoord =
    parseCoordinate(groupArtifact, version).getOrElse {
      return Err(ResolveError.InvalidDependency(groupArtifact))
    }

  val rootJson =
    fetchAndRead(
        coord = rootCoord,
        relativePath = buildModuleCachePath(rootCoord),
        urlBuilder = { buildModuleDownloadUrl(rootCoord, it.url) },
        cacheBase = cacheBase,
        repos = repos,
        deps = deps,
      )
      .getOrElse { moduleError ->
        if (is404OnAllAttempts(moduleError)) {
          val pomResult =
            fetchAndRead(
              coord = rootCoord,
              relativePath = buildPomCachePath(rootCoord),
              urlBuilder = { buildPomDownloadUrl(rootCoord, it.url) },
              cacheBase = cacheBase,
              repos = repos,
              deps = deps,
            )
          if (pomResult.isErr) {
            return Err(moduleError)
          }
          return Ok(NativeResolved.JvmOnly(rootCoord))
        }
        return Err(moduleError)
      }

  if (!isValidGradleModuleJson(rootJson)) {
    return Err(ResolveError.MetadataParseFailed(groupArtifact))
  }
  val redirect =
    parseNativeRedirect(rootJson, nativeTarget)
      ?: return Err(ResolveError.NoNativeVariant(groupArtifact, nativeTarget))

  val targetCoord = Coordinate(redirect.group, redirect.module, redirect.version)
  val targetJson =
    fetchAndRead(
        coord = targetCoord,
        relativePath = buildModuleCachePath(targetCoord),
        urlBuilder = { buildModuleDownloadUrl(targetCoord, it.url) },
        cacheBase = cacheBase,
        repos = repos,
        deps = deps,
      )
      .getOrElse {
        return Err(it)
      }

  if (!isValidGradleModuleJson(targetJson)) {
    return Err(ResolveError.MetadataParseFailed(groupArtifact))
  }
  val artifact =
    parseNativeArtifact(targetJson, nativeTarget)
      ?: return Err(ResolveError.MetadataParseFailed(groupArtifact))

  return Ok(NativeResolved.Klib(redirect, artifact))
}

/**
 * Downloads a file from the first available repository if it is not yet on disk, then reads and
 * returns its contents. Used for `.module` fetches.
 */
private fun fetchAndRead(
  coord: Coordinate,
  relativePath: String,
  urlBuilder: (Repository) -> String,
  cacheBase: String,
  repos: List<Repository>,
  deps: ResolverDeps,
): Result<String, ResolveError> {
  val groupArtifact = "${coord.group}:${coord.artifact}"
  val cachePath = "$cacheBase/$relativePath"

  if (!deps.fileExists(cachePath)) {
    val parentDir = cachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return Err(ResolveError.DirectoryCreateFailed(parentDir))
    }
    downloadFromRepositories(repos, cachePath, urlBuilder, deps::downloadFile).getOrElse { failure
      ->
      return Err(ResolveError.DownloadFailed(groupArtifact, failure))
    }
  }
  val content =
    deps.readFileContent(cachePath).getOrElse {
      return Err(ResolveError.MetadataFetchFailed(groupArtifact))
    }
  return Ok(content)
}
