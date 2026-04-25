package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onErr
import kolt.build.ResolvedJar
import kolt.build.UserJdkError
import kolt.build.autoInjectedTestDeps
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
internal data class JvmResolutionOutcome(
  val mainClasspath: String?,
  val mainJars: List<ResolvedJar>,
  val allJars: List<ResolvedJar>,
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

internal fun resolveDependencies(config: KoltConfig): Result<JvmResolutionOutcome, Int> {
  for (dep in findOverlappingDependencies(config.dependencies, config.testDependencies)) {
    eprintln(
      "warning: '${dep.groupArtifact}' is in both [dependencies] (${dep.mainVersion}) and [test-dependencies] (${dep.testVersion}); using ${dep.mainVersion}"
    )
  }

  val mainSeeds = config.dependencies
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

  val existingLock =
    if (fileExists(LOCK_FILE)) {
      val lockJson =
        readFileAsString(LOCK_FILE).getOrElse { error ->
          eprintln("warning: could not read $LOCK_FILE: ${error.path}")
          null
        }
      lockJson?.let {
        parseLockfile(it).getOrElse { error ->
          when (error) {
            is LockfileError.ParseFailed -> eprintln("warning: ${error.message}")
            is LockfileError.UnsupportedVersion ->
              eprintln("warning: unsupported lock file version ${error.version}")
          }
          null
        }
      }
    } else null

  println("resolving dependencies...")
  val resolveResult =
    resolve(config, existingLock, paths.cacheBase, createResolverDeps(), testSeeds = testSeeds)
      .getOrElse { error ->
        eprintln(formatResolveError(error))
        if (error is ResolveError.Sha256Mismatch) {
          eprintln("delete the cached jar and rebuild to re-download")
        }
        return Err(EXIT_DEPENDENCY_ERROR)
      }

  if (resolveResult.lockChanged) {
    val lockfile = buildLockfileFromResolved(config, resolveResult.deps)
    val lockJson = serializeLockfile(lockfile)
    writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_DEPENDENCY_ERROR)
    }
  }

  if (resolveResult.lockChanged || !fileExists(WORKSPACE_JSON)) {
    writeWorkspaceFiles(config, paths, resolveResult.deps)
  }

  return Ok(splitJvmOutcome(resolveResult.deps))
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
// JDK detection. Warn in each failure mode so the user can act on it without
// blocking the resolve.
private fun resolveWorkspaceSdkHomePath(config: KoltConfig, paths: KoltPaths): String? =
  resolveUserJdkHome(config, paths)
    .onErr { err ->
      when (err) {
        is UserJdkError.ManagedMissing ->
          eprintln(
            "warning: workspace.json sdk homePath left unset — jdk ${err.version} not installed at ${err.expectedPath} (run `kolt toolchain install`)"
          )
        UserJdkError.SystemProbeFailed ->
          eprintln(
            "warning: workspace.json sdk homePath left unset — could not locate `java` on PATH (install a JDK or set [build] jdk)"
          )
      }
    }
    .get()
    ?.home
