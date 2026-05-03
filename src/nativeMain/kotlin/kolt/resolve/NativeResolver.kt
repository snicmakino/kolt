package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.konanTargetGradleName

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

private data class NativeResolved(val redirect: NativeRedirect, val artifact: NativeArtifact)

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
  for (node in nodes) {
    val resolved =
      processed["${node.groupArtifact}:${node.version}"]
        ?: return Err(ResolveError.MetadataParseFailed(node.groupArtifact))

    val targetCoord =
      Coordinate(resolved.redirect.group, resolved.redirect.module, resolved.redirect.version)
    val klibCachePath = "$cacheBase/${buildKlibCachePath(targetCoord)}"

    if (!deps.fileExists(klibCachePath)) {
      val parentDir = klibCachePath.substringBeforeLast('/')
      deps.ensureDirectoryRecursive(parentDir).getOrElse {
        return Err(ResolveError.DirectoryCreateFailed(parentDir))
      }
      downloadFromRepositories(
          repos,
          klibCachePath,
          { buildKlibDownloadUrl(targetCoord, it) },
          deps::downloadFile,
        )
        .getOrElse { failure ->
          return Err(ResolveError.DownloadFailed(node.groupArtifact, failure))
        }
    }

    val actualHash =
      deps.computeSha256(klibCachePath).getOrElse {
        return Err(ResolveError.HashComputeFailed(node.groupArtifact, it))
      }
    if (actualHash != resolved.artifact.klibSha256) {
      return Err(
        ResolveError.Sha256Mismatch(node.groupArtifact, resolved.artifact.klibSha256, actualHash)
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

  return Ok(ResolveResult(deps = resolvedDeps, lockChanged = false))
}

private fun makeNativeChildLookup(
  processed: MutableMap<String, NativeResolved>,
  nativeTarget: String,
  cacheBase: String,
  repos: List<String>,
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
    native.artifact.dependencies.mapNotNull { d ->
      val depGA = "${d.group}:${d.module}"
      if (isKotlinStdlib(depGA)) return@mapNotNull null
      Child(groupArtifact = depGA, version = d.version, strict = d.strict, rejects = d.rejects)
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
  repos: List<String>,
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
        NativeNodeInfo(
          displayGroupArtifact = "${it.redirect.group}:${it.redirect.module}",
          displayVersion = it.redirect.version,
          dependencies =
            it.artifact.dependencies.map { dep -> "${dep.group}:${dep.module}" to dep.version },
        )
      }
    }
  }
}

/**
 * Fetches and parses both the root `.module` (for the available-at redirect) and the
 * platform-specific `.module` (for the klib file + dependencies) for a single coordinate.
 */
private fun fetchNativeMetadata(
  groupArtifact: String,
  version: String,
  nativeTarget: String,
  cacheBase: String,
  repos: List<String>,
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
        urlBuilder = { buildModuleDownloadUrl(rootCoord, it) },
        cacheBase = cacheBase,
        repos = repos,
        deps = deps,
      )
      .getOrElse {
        return Err(it)
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
        urlBuilder = { buildModuleDownloadUrl(targetCoord, it) },
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

  return Ok(NativeResolved(redirect, artifact))
}

/**
 * Downloads a file from the first available repository if it is not yet on disk, then reads and
 * returns its contents. Used for `.module` fetches.
 */
private fun fetchAndRead(
  coord: Coordinate,
  relativePath: String,
  urlBuilder: (String) -> String,
  cacheBase: String,
  repos: List<String>,
  deps: ResolverDeps,
): Result<String, ResolveError> {
  val groupArtifact = "${coord.group}:${coord.artifact}"
  val cachePath = "$cacheBase/$relativePath"

  if (!deps.fileExists(cachePath)) {
    val parentDir = cachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return Err(ResolveError.DirectoryCreateFailed(parentDir))
    }
    downloadFromRepositories(repos, cachePath, urlBuilder, deps::downloadFile).getOrElse {
      failure ->
      return Err(ResolveError.DownloadFailed(groupArtifact, failure))
    }
  }
  val content =
    deps.readFileContent(cachePath).getOrElse {
      return Err(ResolveError.MetadataFetchFailed(groupArtifact))
    }
  return Ok(content)
}
