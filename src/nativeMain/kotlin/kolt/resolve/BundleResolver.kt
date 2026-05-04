package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import kolt.build.ResolvedJar
import kolt.config.KoltConfig

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
// core` ⇒ `-core-jvm`) are not persisted in the lockfile, so reusing the
// stored GA to compose the URL would 404 against the redirected jar. The
// cachePath is the source of truth for where the jar lives — swapping the
// cacheBase prefix for a repo URL gives a URL that matches the cachePath.
//
// Sha-verify asymmetry: only freshly downloaded jars are re-hashed against
// the locked entry. Cache-warm jars are trusted, matching the existing
// reuse path's "lockfile is trusted while it remains in sync with kolt.toml"
// policy. Re-verifying every warm bundle jar would defeat the reuse-path
// performance win for no extra safety the main `kolt fetch` call doesn't
// already provide on the next resolveTransitive pass.
internal fun materialiseBundleJarsFromLock(
  resolution: BundleResolution,
  config: KoltConfig,
  existingLock: Lockfile,
  bundleName: String,
  cacheBase: String,
  deps: ResolverDeps,
): Result<Unit, ResolveError> {
  val locked = existingLock.classpathBundles[bundleName] ?: return Ok(Unit)
  val repos = config.repositories.values.toList()
  val cachePrefix = "$cacheBase/"
  for (dep in resolution.deps) {
    if (deps.fileExists(dep.cachePath)) continue
    val relativePath =
      if (dep.cachePath.startsWith(cachePrefix)) dep.cachePath.substring(cachePrefix.length)
      else return Err(ResolveError.InvalidDependency(dep.groupArtifact))
    val parentDir = dep.cachePath.substringBeforeLast('/')
    deps.ensureDirectoryRecursive(parentDir).getOrElse {
      return Err(ResolveError.DirectoryCreateFailed(parentDir))
    }
    downloadFromRepositories(
        repos,
        dep.cachePath,
        { repo -> "$repo/$relativePath" },
        deps::downloadFile,
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
