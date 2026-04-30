package kolt.resolve

import com.github.michaelbull.result.Result
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
