package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import kolt.build.ResolvedJar
import kolt.config.KoltConfig
import kolt.config.Repository

// BundleResolver runs an independent fixpointResolve pass per bundle so that
// transitive deps, strict pins, and rejects stay bundle-local (Req 4.5). The
// existing main/test resolver in Resolver.kt stays untouched. Each call here
// builds its own graph from `bundleSeeds` alone — main/test/other-bundle state
// is never threaded in, so there is no leakage path at the function-contract
// level. See design.md §Resolver (extended) — Bundle resolver.
// `deps` carries the per-bundle ResolvedDep list so callers can persist bundle
// entries into kolt.lock.classpathBundles via buildLockfileFromResolved without
// re-deriving sha256 / transitive flags. `jars` and `classpath` are the
// downstream-friendly views consumed by SysPropResolver and outcome assembly.
internal data class BundleResolution(
  val jars: List<ResolvedJar>,
  val classpath: String,
  val deps: List<ResolvedDep>,
)

internal fun resolveBundle(
  config: KoltConfig,
  bundleName: String,
  bundleSeeds: Map<String, String>,
  existingLock: Lockfile?,
  cacheBase: String,
  deps: ResolverDeps,
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<BundleResolution, ResolveError> {
  // materialize() consults existingLock.dependencies for sha-mismatch and
  // lockChanged tracking. Project the bundle's own sub-map (not main/test's)
  // into a synthetic Lockfile so the lookup stays bundle-local.
  val bundleLock =
    existingLock?.let {
      Lockfile(
        version = it.version,
        kotlin = it.kotlin,
        jvmTarget = it.jvmTarget,
        dependencies = it.classpathBundles[bundleName] ?: emptyMap(),
        classpathBundles = emptyMap(),
      )
    }

  return resolveTransitive(
      config = config,
      existingLock = bundleLock,
      cacheBase = cacheBase,
      deps = deps,
      mainSeeds = bundleSeeds,
      testSeeds = emptyMap(),
      progress = progress,
    )
    .map { result ->
      val jars =
        result.deps.map { dep ->
          ResolvedJar(
            cachePath = dep.cachePath,
            groupArtifactVersion = "${dep.groupArtifact}:${dep.version}",
          )
        }
      BundleResolution(
        jars = jars,
        classpath = buildClasspath(jars.map { it.cachePath }),
        deps = result.deps,
      )
    }
}

// The lockfile-trusted bundle reuse path skips the resolver kernel but
// still needs to put jars on disk. A clean ~/.kolt/cache otherwise leaves
// consumers handing out paths to non-existent jars; subsequent kolt build
// / kolt test reads bundle classpaths through the reuse path and fails at
// compile time with `Unresolved reference`. Mirrors the download-then-
// verify shape of `materialize()` in TransitiveResolver but derives the
// download URL from `dep.cachePath` rather than rebuilding it from the
// `groupArtifact`: KMP module-metadata redirects (e.g. `kotlinx-coroutines-
// core` ⇒ `-core-jvm`) make the declared GA diverge from the on-disk jar
// path. cachePath is reconstructed by reuseBundleFromLock from the locked
// `redirect_target` and is the single source of truth here — swapping the
// cacheBase prefix for a repo URL gives a URL that matches the cachePath.
//
// Sha-verify asymmetry: only freshly downloaded jars are re-hashed against
// the locked entry. Cache-warm jars are trusted, matching the existing
// reuse path's "lockfile is trusted while it remains in sync with kolt.toml"
// policy. Re-verifying every warm bundle jar would defeat the reuse-path
// performance win for no extra safety the main `kolt fetch` call doesn't
// already provide on the next resolveTransitive pass.
/**
 * Single-artifact variant of `resolveBundle` that skips both POM-derived transitive enumeration and
 * Gradle-module-metadata redirects.
 *
 * Intended for `[tools]` runnable jars (R2.4 + design.md §Resolver / ToolResolution): the caller
 * supplies a Maven coordinate and an optional classifier and gets back the cache-resident jar's
 * path + SHA-256, with no traversal of dependency graphs. The function is purely additive — the
 * `[classpaths]` `resolveBundle` path is untouched.
 *
 * Returned `groupArtifact` and `version` mirror the input `coord`. `classifier` is echoed back so
 * lockfile keying can include it.
 */
fun resolveSingleArtifact(
  coord: Coordinate,
  classifier: String?,
  repos: List<Repository>,
  cacheBase: String,
  deps: ResolverDeps,
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<SingleArtifact, ResolveError> {
  val groupArtifact = "${coord.group}:${coord.artifact}"
  val relativePath = buildRelativeJarPath(coord, classifier)
  val cachePath = "$cacheBase/$relativePath"

  if (!deps.fileExists(cachePath)) {
    val parentDir = cachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return Err(ResolveError.DirectoryCreateFailed(parentDir))
    }
    progress.onArtifactStart(1, 1, groupArtifact, coord.version)
    downloadFromRepositories(
        repos,
        cachePath,
        { repo -> "${repo.url}/$relativePath" },
        deps::downloadFile,
        onRetry = { repo -> progress.onRetryAgainst(repo.url) },
      )
      .getOrElse { failure ->
        return Err(ResolveError.DownloadFailed(groupArtifact, failure))
      }
  }

  val sha =
    deps.computeSha256(cachePath).getOrElse { error ->
      return Err(ResolveError.HashComputeFailed(groupArtifact, error))
    }

  return Ok(
    SingleArtifact(
      groupArtifact = groupArtifact,
      version = coord.version,
      classifier = classifier,
      cachePath = cachePath,
      sha256 = sha,
    )
  )
}

data class SingleArtifact(
  val groupArtifact: String,
  val version: String,
  val classifier: String?,
  val cachePath: String,
  val sha256: String,
)

// Kept private to BundleResolver because the single-artifact path does not need to share Maven
// path construction with the transitive resolver — `buildCachePath` there has no classifier
// parameter, and exposing one there would invite accidental classifier-bearing transitive
// resolves. See ADR 0028 §3 freeze on the `[classpaths]` resolution shape.
private fun buildRelativeJarPath(coord: Coordinate, classifier: String?): String {
  val groupPath = coord.group.replace('.', '/')
  val suffix = if (classifier != null) "-$classifier" else ""
  return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}$suffix.jar"
}

internal fun materialiseBundleJarsFromLock(
  resolution: BundleResolution,
  config: KoltConfig,
  existingLock: Lockfile,
  bundleName: String,
  cacheBase: String,
  deps: ResolverDeps,
  progress: ResolverProgressSink = ResolverProgressSink.NoOp,
): Result<Unit, ResolveError> {
  val locked = existingLock.classpathBundles[bundleName] ?: return Ok(Unit)
  val repos = config.repositories.values.toList()
  val cachePrefix = "$cacheBase/"
  // Pre-count uncached locked jars so the total `M` is known before any
  // emission. Cache-warm jars do not advance the index and stay silent.
  val total = resolution.deps.count { !deps.fileExists(it.cachePath) }
  var index = 0
  for (dep in resolution.deps) {
    if (deps.fileExists(dep.cachePath)) continue
    val relativePath =
      if (dep.cachePath.startsWith(cachePrefix)) dep.cachePath.substring(cachePrefix.length)
      else return Err(ResolveError.InvalidDependency(dep.groupArtifact))
    val parentDir = dep.cachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return Err(ResolveError.DirectoryCreateFailed(parentDir))
    }
    index += 1
    progress.onArtifactStart(index, total, dep.groupArtifact, dep.version)
    downloadFromRepositories(
        repos,
        dep.cachePath,
        { repo -> "${repo.url}/$relativePath" },
        deps::downloadFile,
        onRetry = { repo -> progress.onRetryAgainst(repo.url) },
      )
      .getOrElse { failure ->
        return Err(ResolveError.DownloadFailed(dep.groupArtifact, failure))
      }
    val hash =
      deps.computeSha256(dep.cachePath).getOrElse { error ->
        return Err(ResolveError.HashComputeFailed(dep.groupArtifact, error))
      }
    val expected = locked[dep.groupArtifact]?.sha256
    if (expected != null && expected != hash) {
      return Err(ResolveError.Sha256Mismatch(dep.groupArtifact, expected, hash))
    }
  }
  return Ok(Unit)
}
