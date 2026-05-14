package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.config.MAVEN_CENTRAL_BASE
import kolt.config.Repository
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
  repos: List<Repository> =
    listOf(Repository(name = "central", url = MAVEN_CENTRAL_BASE, auth = null)),
): Result<ToolJarHandle, ToolResolutionError> where T : ResolverDeps, T : ToolFsDeps {
  val coords = entry.coords
  val classifier = entry.classifier
  val fileName = jarFileName(coords, classifier)
  val toolsJarPath = paths.toolsBundleJarPath(alias, coords.version, fileName)
  val innerKey = innerLockfileKey(coords, classifier)
  val aliasPins = lockfile.toolsBundles[alias].orEmpty()

  // Lockfile-mismatch reject (R4.3): if the alias has any pin at all, it must match the toml's
  // (group, artifact, version, classifier) exactly. The transitive-skip resolver guarantees a
  // single pin per alias, so we either find an exact match keyed by `innerKey` with the right
  // version, or we surface a loud mismatch — even when the divergence is in the inner key (group
  // / artifact / classifier), in which case the inner-key lookup misses entirely.
  if (aliasPins.isNotEmpty()) {
    val matchedPin = aliasPins[innerKey]
    if (matchedPin == null || matchedPin.version != coords.version) {
      val (lockedKey, lockedEntry) = aliasPins.entries.first().toPair()
      val (lockedCoords, lockedClassifier) = decomposeInnerKey(lockedKey, lockedEntry.version)
      return Err(
        ToolResolutionError.LockfileMismatch(
          alias = alias,
          tomlCoords = coords,
          tomlClassifier = classifier,
          lockedCoords = lockedCoords,
          lockedClassifier = lockedClassifier,
        )
      )
    }
  }

  val pin = aliasPins[innerKey]

  // Cache hit path — verify SHA-256 against pin if a pin exists. The pin is required to declare
  // the cache trustworthy; without one there is nothing to verify against. SHA divergence on the
  // cached jar is a loud `IntegrityMismatch` and must NOT trigger automatic refetch (R3.3): the
  // user has to inspect / refresh manually.
  if (pin != null && netDeps.fileExists(toolsJarPath)) {
    val cachedSha =
      netDeps.computeSha256(toolsJarPath).getOrElse {
        return Err(
          ToolResolutionError.IntegrityMismatch(alias, coords, expected = pin.sha256, actual = "")
        )
      }
    if (cachedSha == pin.sha256) {
      return Ok(
        ToolJarHandle(
          jarPath = toolsJarPath,
          resolvedCoords = coords,
          classifier = classifier,
          lockfileChanged = false,
        )
      )
    }
    return Err(
      ToolResolutionError.IntegrityMismatch(
        alias = alias,
        coords = coords,
        expected = pin.sha256,
        actual = cachedSha,
      )
    )
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
          is RepositoryDownloadFailure.AuthFailed -> listOf(f.url)
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

// Inverse of `innerLockfileKey`. Used only on the loud-reject path to render the locked-side
// coords inside the `LockfileMismatch` message; not in the validation hot loop. Splits on `:` and
// expects exactly two or three parts. A malformed key falls back to a placeholder Coordinate so
// the failure still surfaces with `alias` context (the actual loud reject already happened).
internal fun decomposeInnerKey(innerKey: String, version: String): Pair<Coordinate, String?> {
  val parts = innerKey.split(":")
  return when (parts.size) {
    2 -> Coordinate(parts[0], parts[1], version) to null
    3 -> Coordinate(parts[0], parts[1], version) to parts[2]
    else -> Coordinate(innerKey, "", version) to null
  }
}
