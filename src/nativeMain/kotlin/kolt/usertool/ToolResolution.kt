package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.CopyFailed
import kolt.resolve.Coordinate
import kolt.resolve.Lockfile
import kolt.resolve.RepositoryDownloadFailure
import kolt.resolve.ResolveError
import kolt.resolve.ResolverDeps
import kolt.resolve.SingleArtifact
import kolt.resolve.resolveSingleArtifact

/**
 * Filesystem capabilities used by [ensureTool] beyond what [ResolverDeps] supplies. Split from
 * [ResolverDeps] so tests can record copies independently of resolver behaviour, and so the
 * `[classpaths]` resolver — which has no copy step — does not grow an unused interface member.
 */
interface ToolFsDeps {
  fun copyFile(src: String, dest: String): Result<Unit, CopyFailed>
}

/**
 * Handle returned to [ensureTool] callers. `jarPath` is the per-alias bundle path that the launcher
 * reads. `lockfileChanged=true` means the caller must persist the new pin via `serializeLockfile`
 * (the typical first-fetch flow).
 */
data class ToolJarHandle(
  val jarPath: String,
  val resolvedCoords: Coordinate,
  val classifier: String?,
  val lockfileChanged: Boolean,
)

/**
 * Resolve the runnable jar for a `[tools.<alias>]` entry, honouring the per-alias bundle cache and
 * any existing lockfile pin.
 *
 * Three behaviours, all mutually exclusive:
 * 1. **Cache hit** — `paths.toolsBundleJarPath` exists AND lockfile pin matches version + sha256.
 *    Returns the existing path with `lockfileChanged=false`. No network, no copy.
 * 2. **Cache miss + no pin** (first run) — fetch via [resolveSingleArtifact], copy into the
 *    per-alias path, return with `lockfileChanged=true` so the caller writes the new pin.
 * 3. **Cache miss + matching pin** — fetch into the Maven cache, copy into the per-alias path,
 *    return with `lockfileChanged=false`.
 *
 * Failure paths (`LockfileMismatch`, `IntegrityMismatch`, `ResolveFailed`) are layered in by
 * follow-up implementations; this happy-path entry point is enough to land sections 4.2.
 */
internal fun <T> ensureTool(
  alias: String,
  entry: ToolEntry,
  paths: KoltPaths,
  lockfile: Lockfile,
  netDeps: T,
  repos: List<String> = listOf("https://repo.maven.apache.org/maven2"),
): Result<ToolJarHandle, ToolResolutionError> where T : ResolverDeps, T : ToolFsDeps {
  val coords = entry.coords
  val classifier = entry.classifier
  val fileName = jarFileName(coords, classifier)
  val toolsJarPath = paths.toolsBundleJarPath(alias, coords.version, fileName)
  val innerKey = innerLockfileKey(coords, classifier)
  val pin = lockfile.toolsBundles[alias]?.get(innerKey)

  // Cache hit path — verify SHA-256 against pin if a pin exists. The pin is required to declare
  // the cache trustworthy; without one there is nothing to verify against.
  if (pin != null && netDeps.fileExists(toolsJarPath)) {
    val cachedSha =
      netDeps.computeSha256(toolsJarPath).getOrElse {
        return Err(
          ToolResolutionError.IntegrityMismatch(alias, coords, expected = pin.sha256, actual = "")
        )
      }
    if (cachedSha == pin.sha256 && pin.version == coords.version) {
      return Ok(
        ToolJarHandle(
          jarPath = toolsJarPath,
          resolvedCoords = coords,
          classifier = classifier,
          lockfileChanged = false,
        )
      )
    }
  }

  // Cache miss — fetch into the Maven-layout cache and then copy to the per-alias path.
  val artifact =
    resolveSingleArtifact(
        coord = coords,
        classifier = classifier,
        repos = repos,
        cacheBase = paths.cacheBase,
        deps = netDeps,
      )
      .getOrElse { resolveError ->
        return Err(translateResolveError(alias, coords, resolveError))
      }

  storeInPerAliasCache(artifact, toolsJarPath, netDeps).getOrElse { err ->
    return Err(err)
  }

  val lockfileChanged = pin == null
  return Ok(
    ToolJarHandle(
      jarPath = toolsJarPath,
      resolvedCoords = coords,
      classifier = classifier,
      lockfileChanged = lockfileChanged,
    )
  )
}

private fun <T> storeInPerAliasCache(
  artifact: SingleArtifact,
  toolsJarPath: String,
  netDeps: T,
): Result<Unit, ToolResolutionError> where T : ResolverDeps, T : ToolFsDeps {
  val parentDir = toolsJarPath.substringBeforeLast('/')
  netDeps.ensureDirectoryRecursive(parentDir).getOrElse {
    return Err(
      ToolResolutionError.ResolveFailed(
        alias = "",
        coords = Coordinate("", "", artifact.version),
        attempts = listOf("mkdir $parentDir"),
      )
    )
  }
  netDeps.copyFile(artifact.cachePath, toolsJarPath).getOrElse {
    return Err(
      ToolResolutionError.ResolveFailed(
        alias = "",
        coords = Coordinate("", "", artifact.version),
        attempts = listOf("copy ${artifact.cachePath} → $toolsJarPath"),
      )
    )
  }
  return Ok(Unit)
}

private fun translateResolveError(
  alias: String,
  coords: Coordinate,
  err: ResolveError,
): ToolResolutionError =
  when (err) {
    is ResolveError.DownloadFailed -> {
      val urls =
        when (val f = err.failure) {
          is RepositoryDownloadFailure.AllAttemptsFailed -> f.attempts.map { it.url }
          RepositoryDownloadFailure.NoRepositoriesConfigured -> emptyList()
        }
      ToolResolutionError.ResolveFailed(alias = alias, coords = coords, attempts = urls)
    }
    is ResolveError.HashComputeFailed ->
      ToolResolutionError.ResolveFailed(
        alias = alias,
        coords = coords,
        attempts = listOf("hash compute failed"),
      )
    is ResolveError.DirectoryCreateFailed ->
      ToolResolutionError.ResolveFailed(
        alias = alias,
        coords = coords,
        attempts = listOf("mkdir ${err.path}"),
      )
    else ->
      ToolResolutionError.ResolveFailed(
        alias = alias,
        coords = coords,
        attempts = listOf(err.toString()),
      )
  }

internal fun jarFileName(coords: Coordinate, classifier: String?): String {
  val suffix = if (classifier != null) "-$classifier" else ""
  return "${coords.artifact}-${coords.version}$suffix.jar"
}

internal fun innerLockfileKey(coords: Coordinate, classifier: String?): String {
  val ga = "${coords.group}:${coords.artifact}"
  return if (classifier != null) "$ga:$classifier" else ga
}
