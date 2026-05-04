package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import kolt.build.ResolvedJar
import kolt.build.UserJdkError
import kolt.build.UserJdkHome
import kolt.build.autoInjectedMainDeps
import kolt.build.autoInjectedTestDeps
import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.build.daemon.resolveBootstrapJavaBin
import kolt.build.generateWorkspaceJson
import kolt.build.resolveUserJdkHome
import kolt.config.*
import kolt.infra.*
import kolt.resolve.*

internal const val LOCK_FILE = "kolt.lock"
private const val WORKSPACE_JSON = "workspace.json"

internal fun createResolverDeps(): ResolverDeps = defaultResolverDeps()

// Splits the resolved jars by origin so the JVM kind=app tail in BuildCommands
// can emit the runtime classpath manifest (ADR 0027 §1) from main-only jars,
// while `kolt test` can still build its compile/run classpath from the union.
// `allJars` is the disjoint concatenation `mainJars + testJars` (the kernel
// guarantees disjoint-ness via main-wins-on-overlap).
//
// `bundleClasspaths` and `bundleJars` carry the per-bundle resolution result
// for [classpaths.<name>] declarations. They never affect mainClasspath /
// mainJars / allJars — bundles are scope-isolated from main/test (Req 4.5).
// SysPropResolver consumes `bundleClasspaths` to materialise `-Dkey=path`
// values for `{ classpath = "<bundle>" }` sysprops; `bundleJars` is exposed
// for `kolt tree` and similar tooling.
internal data class JvmResolutionOutcome(
  val mainClasspath: String?,
  val mainJars: List<ResolvedJar>,
  val allJars: List<ResolvedJar>,
  val bundleClasspaths: Map<String, String> = emptyMap(),
  val bundleJars: Map<String, List<ResolvedJar>> = emptyMap(),
)

internal data class OverlappingDep(
  val groupArtifact: String,
  val mainVersion: String?,
  val testVersion: String?,
)

internal fun findOverlappingDependencies(
  mainDeps: Map<String, String>,
  testDeps: Map<String, String>,
): List<OverlappingDep> {
  val overlap = mainDeps.keys.intersect(testDeps.keys)
  return overlap
    .filter { mainDeps[it] != testDeps[it] }
    .map { OverlappingDep(it, mainDeps[it], testDeps[it]) }
}

internal fun resolveDependencies(
  config: KoltConfig,
  allowLockfileMigration: Boolean = false,
): Result<JvmResolutionOutcome, Int> {
  for (dep in findOverlappingDependencies(config.dependencies, config.testDependencies)) {
    eprintln(
      "warning: '${dep.groupArtifact}' is in both [dependencies] (${dep.mainVersion}) and [test-dependencies] (${dep.testVersion}); using ${dep.mainVersion}"
    )
  }

  val mainSeeds = autoInjectedMainDeps(config) + config.dependencies
  val testSeeds = autoInjectedTestDeps(config) + config.testDependencies
  if (mainSeeds.isEmpty() && testSeeds.isEmpty()) {
    if (fileExists(LOCK_FILE)) {
      deleteFile(LOCK_FILE)
    }
    return Ok(
      JvmResolutionOutcome(mainClasspath = null, mainJars = emptyList(), allJars = emptyList())
    )
  }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  val lockJsonOnDisk =
    if (fileExists(LOCK_FILE)) {
      readFileAsString(LOCK_FILE).getOrElse { error ->
        eprintln("warning: could not read $LOCK_FILE: ${error.path}")
        null
      }
    } else null

  val existingLock =
    when (
      val outcome = classifyLockfileLoad(lockJsonOnDisk, allowMigration = allowLockfileMigration)
    ) {
      is LockfileLoadResult.Loaded -> outcome.lockfile
      is LockfileLoadResult.Absent -> null
      is LockfileLoadResult.Corrupt -> {
        eprintln("warning: ${outcome.message}")
        null
      }
      is LockfileLoadResult.UnsupportedAndMigrationAllowed -> {
        eprintln(
          "warning: kolt.lock v${outcome.version} detected, regenerating as v$LOCKFILE_VERSION (one-time migration for v0.X)"
        )
        null
      }
      is LockfileLoadResult.UnsupportedAndMigrationDenied -> {
        eprintln(
          "error: kolt.lock v${outcome.version} is no longer supported, run 'kolt fetch' to regenerate"
        )
        return Err(EXIT_DEPENDENCY_ERROR)
      }
    }

  println("resolving dependencies...")
  val resolverDeps = createResolverDeps()
  val resolveResult =
    resolve(
        config,
        existingLock,
        paths.cacheBase,
        resolverDeps,
        mainSeeds = mainSeeds,
        testSeeds = testSeeds,
      )
      .getOrElse { error ->
        eprintln(formatResolveError(error))
        if (error is ResolveError.Sha256Mismatch) {
          eprintln("delete the cached jar and rebuild to re-download")
        }
        return Err(EXIT_DEPENDENCY_ERROR)
      }

  val bundleResolutions =
    resolveAllBundles(config, existingLock, paths.cacheBase, resolverDeps).getOrElse { error ->
      eprintln(formatResolveError(error))
      if (error is ResolveError.Sha256Mismatch) {
        eprintln("delete the cached jar and rebuild to re-download")
      }
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  val bundleDeps = bundleResolutions.mapValues { it.value.deps }
  val lockfileChanged =
    resolveResult.lockChanged ||
      bundleLockChanged(existingLock, bundleResolutions, config.classpaths.keys)
  if (lockfileChanged) {
    val lockfile = buildLockfileFromResolved(config, resolveResult.deps, bundleDeps)
    val lockJson = serializeLockfile(lockfile)
    writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_DEPENDENCY_ERROR)
    }
  }

  if (lockfileChanged || !fileExists(WORKSPACE_JSON)) {
    writeWorkspaceFiles(config, paths, resolveResult.deps)
  }

  val baseOutcome = splitJvmOutcome(resolveResult.deps)
  return Ok(integrateBundleClasspaths(baseOutcome, bundleResolutions))
}

// Detects whether the bundle section of the lockfile must be rewritten. Even
// when no bundle was freshly re-resolved, declaration changes can drop a bundle
// (config.classpaths shrinks) or rename it; both require rewriting the lock.
private fun bundleLockChanged(
  existingLock: Lockfile?,
  bundleResolutions: Map<String, BundleResolution>,
  declaredBundleNames: Set<String>,
): Boolean {
  if (existingLock == null) return bundleResolutions.isNotEmpty()
  if (existingLock.classpathBundles.keys != declaredBundleNames) return true
  for ((bundleName, resolution) in bundleResolutions) {
    val locked = existingLock.classpathBundles[bundleName] ?: return true
    if (locked.size != resolution.deps.size) return true
    for (dep in resolution.deps) {
      val lockEntry = locked[dep.groupArtifact] ?: return true
      if (lockEntry.version != dep.version) return true
      if (lockEntry.sha256 != dep.sha256) return true
      if (lockEntry.transitive != dep.transitive) return true
    }
  }
  return false
}

// Pure projection: bundle resolutions are layered onto a base outcome derived
// from main/test resolve. Bundle data never mutates main/test fields (Req 4.5).
internal fun integrateBundleClasspaths(
  base: JvmResolutionOutcome,
  bundleResolutions: Map<String, BundleResolution>,
): JvmResolutionOutcome =
  base.copy(
    bundleClasspaths = bundleResolutions.mapValues { it.value.classpath },
    bundleJars = bundleResolutions.mapValues { it.value.jars },
  )

// Resolves every [classpaths.<name>] declaration. When the locked direct
// entries match the declared GAV-version map exactly, the bundle is reused
// from the lock without invoking the resolver kernel (Req 4.4 — "re-resolve
// only on declaration change"). Locked transitives are projected back into the
// reused BundleResolution so the outcome and the rewritten lockfile stay
// stable.
internal fun resolveAllBundles(
  config: KoltConfig,
  existingLock: Lockfile?,
  cacheBase: String,
  resolverDeps: ResolverDeps,
): Result<Map<String, BundleResolution>, ResolveError> {
  if (config.classpaths.isEmpty()) return Ok(emptyMap())

  val out = LinkedHashMap<String, BundleResolution>(config.classpaths.size)
  for ((bundleName, bundleSeeds) in config.classpaths) {
    if (existingLock != null) {
      val cached = reuseBundleFromLock(bundleName, bundleSeeds, existingLock, cacheBase)
      if (cached != null) {
        materialiseBundleJarsFromLock(
            cached,
            config,
            existingLock,
            bundleName,
            cacheBase,
            resolverDeps,
          )
          .getOrElse {
            return Err(it)
          }
        out[bundleName] = cached
        continue
      }
    }
    val resolution =
      resolveBundle(
          config = config,
          bundleName = bundleName,
          bundleSeeds = bundleSeeds,
          existingLock = existingLock,
          cacheBase = cacheBase,
          deps = resolverDeps,
        )
        .getOrElse { error ->
          return Err(error)
        }
    out[bundleName] = resolution
  }
  return Ok(out)
}

// Returns a BundleResolution reconstructed from the lockfile when (and only
// when) the declared seeds match the locked direct entries exactly. Direct
// entries are LockEntry rows with `transitive = false`. Locked transitives are
// preserved verbatim into the reconstructed deps so the outcome includes them.
private fun reuseBundleFromLock(
  bundleName: String,
  bundleSeeds: Map<String, String>,
  existingLock: Lockfile?,
  cacheBase: String,
): BundleResolution? {
  val locked = existingLock?.classpathBundles?.get(bundleName) ?: return null
  val lockedDirect = locked.filterValues { !it.transitive }
  if (lockedDirect.size != bundleSeeds.size) return null
  for ((ga, declaredVersion) in bundleSeeds) {
    val entry = lockedDirect[ga] ?: return null
    if (entry.version != declaredVersion) return null
  }
  // Declaration matches lock — rebuild the resolution view from locked
  // entries. Each entry keeps its locked transitive bit; cache paths are
  // computed from coordinates (no I/O, no SHA verification — the lockfile is
  // trusted while it remains in sync with kolt.toml).
  val reconstructed = mutableListOf<ResolvedDep>()
  for ((ga, entry) in locked) {
    val coord =
      parseCoordinate(ga, entry.version).getOrElse {
        return null
      }
    val cachePath = "$cacheBase/${buildCachePath(coord)}"
    reconstructed.add(
      ResolvedDep(
        groupArtifact = ga,
        version = entry.version,
        sha256 = entry.sha256,
        cachePath = cachePath,
        transitive = entry.transitive,
        origin = Origin.MAIN,
      )
    )
  }
  val jars =
    reconstructed.map {
      ResolvedJar(
        cachePath = it.cachePath,
        groupArtifactVersion = "${it.groupArtifact}:${it.version}",
      )
    }
  return BundleResolution(
    jars = jars,
    classpath = buildClasspath(jars.map { it.cachePath }),
    deps = reconstructed,
  )
}

internal fun splitByOrigin(deps: List<ResolvedDep>): Pair<List<ResolvedDep>, List<ResolvedDep>> =
  deps.partition { it.origin == Origin.MAIN }

// Extracted for testability: constructs JvmResolutionOutcome from a resolved
// dep list by splitting on Origin. `allJars` = main ++ test (disjoint —
// kernel enforces main-wins-on-overlap).
internal fun splitJvmOutcome(deps: List<ResolvedDep>): JvmResolutionOutcome {
  val mainJars =
    deps
      .filter { it.origin == Origin.MAIN }
      .map { dep ->
        ResolvedJar(
          cachePath = dep.cachePath,
          groupArtifactVersion = "${dep.groupArtifact}:${dep.version}",
        )
      }
  val testJars =
    deps
      .filter { it.origin == Origin.TEST }
      .map { dep ->
        ResolvedJar(
          cachePath = dep.cachePath,
          groupArtifactVersion = "${dep.groupArtifact}:${dep.version}",
        )
      }
  return JvmResolutionOutcome(
    mainClasspath = buildClasspath(mainJars.map { it.cachePath }).ifEmpty { null },
    mainJars = mainJars,
    allJars = mainJars + testJars,
  )
}

internal fun resolveNativeDependencies(
  config: KoltConfig,
  paths: KoltPaths,
): Result<List<String>, Int> {
  if (config.dependencies.isEmpty()) return Ok(emptyList())

  println("resolving native dependencies...")
  val result =
    resolve(config, existingLock = null, paths.cacheBase, createResolverDeps()).getOrElse { error ->
      eprintln(formatResolveError(error))
      return Err(EXIT_DEPENDENCY_ERROR)
    }
  return Ok(result.deps.map { it.cachePath })
}

private fun writeWorkspaceFiles(config: KoltConfig, paths: KoltPaths, deps: List<ResolvedDep>) {
  val (mainDeps, testDeps) = splitByOrigin(deps)
  val sdkHomePath = resolveWorkspaceSdkHomePath(config, paths)
  val workspaceJson = generateWorkspaceJson(config, mainDeps, testDeps, sdkHomePath = sdkHomePath)
  writeFileAsString(WORKSPACE_JSON, workspaceJson).getOrElse { error ->
    eprintln("warning: could not write $WORKSPACE_JSON: ${error.path}")
    return
  }
}

// Best-effort: a null homePath just means the editor falls back to its own
// JDK detection. ManagedMissing carries a concrete action item, so warn.
// SystemProbeFailed silently falls back to the kolt-managed bootstrap JDK
// that actually drives the build (ADR 0017) — that home is the most honest
// answer for the IDE, and pre-#356 this branch printed an "install a JDK"
// warning that contradicted reality on machines where the build succeeded
// via bootstrap. If bootstrap is not yet provisioned (pre-first-build),
// stay silent and leave homePath null; the next build provisions bootstrap
// and re-writes workspace.json.
internal fun resolveWorkspaceSdkHomePath(
  config: KoltConfig,
  paths: KoltPaths,
  resolveUserJdk: (KoltConfig, KoltPaths) -> Result<UserJdkHome, UserJdkError> = { c, p ->
    resolveUserJdkHome(c, p)
  },
  resolveBootstrap: (KoltPaths) -> String? = ::resolveBootstrapJavaBin,
  warningSink: (String) -> Unit = ::eprintln,
): String? =
  resolveUserJdk(config, paths)
    .map { it.home }
    .getOrElse { err ->
      when (err) {
        is UserJdkError.ManagedMissing -> {
          warningSink(
            "warning: workspace.json sdk homePath left unset — jdk ${err.version} not installed at ${err.expectedPath} (run `kolt toolchain install`)"
          )
          null
        }
        UserJdkError.SystemProbeFailed ->
          resolveBootstrap(paths)?.let { paths.jdkPath(BOOTSTRAP_JDK_VERSION) }
      }
    }
