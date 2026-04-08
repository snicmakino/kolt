package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

sealed class ResolveError {
    data class InvalidDependency(val input: String) : ResolveError()
    data class Sha256Mismatch(
        val groupArtifact: String,
        val expected: String,
        val actual: String
    ) : ResolveError()
    data class DownloadFailed(val groupArtifact: String, val error: DownloadError) : ResolveError()
    data class HashComputeFailed(val groupArtifact: String, val error: Sha256Error) : ResolveError()
    data class DirectoryCreateFailed(val path: String) : ResolveError()
}

data class ResolvedDep(
    val groupArtifact: String,
    val version: String,
    val sha256: String,
    val cachePath: String
)

data class ResolveResult(
    val deps: List<ResolvedDep>,
    val lockChanged: Boolean
)

/**
 * Abstraction of side effects required for dependency resolution.
 * Inject a fake implementation in tests for isolation.
 */
interface ResolverDeps {
    fun fileExists(path: String): Boolean
    fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>
    fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError>
    fun computeSha256(filePath: String): Result<String, Sha256Error>
}

/**
 * Resolves dependencies. For each dependency:
 * 1. Parse Maven coordinate
 * 2. Download from Maven Central if not cached
 * 3. Compute SHA256 hash
 * 4. Verify hash against existing lockfile (mismatch is an error)
 * 5. Detect changes in kotlin/jvmTarget or added/removed deps, reflected in [ResolveResult.lockChanged]
 */
fun resolve(
    config: KeelConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    var lockChanged = false
    val resolvedDeps = mutableListOf<ResolvedDep>()

    for ((groupArtifact, version) in config.dependencies) {
        val coord = parseCoordinate(groupArtifact, version).getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }

        val relativePath = buildCachePath(coord)
        val fullCachePath = "$cacheBase/$relativePath"
        val lockEntry = existingLock?.dependencies?.get(groupArtifact)

        // Download if not cached
        if (!deps.fileExists(fullCachePath)) {
            val parentDir = fullCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
                return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            val url = buildDownloadUrl(coord)
            deps.downloadFile(url, fullCachePath).getOrElse { error ->
                return Err(ResolveError.DownloadFailed(groupArtifact, error))
            }
            lockChanged = true
        }

        // SHA256計算
        val hash = deps.computeSha256(fullCachePath).getOrElse { error ->
            return Err(ResolveError.HashComputeFailed(groupArtifact, error))
        }

        // Verify against lockfile
        if (lockEntry != null) {
            if (lockEntry.version != version) {
                lockChanged = true
            } else if (lockEntry.sha256 != hash) {
                return Err(ResolveError.Sha256Mismatch(groupArtifact, lockEntry.sha256, hash))
            }
        } else {
            lockChanged = true
        }

        resolvedDeps.add(ResolvedDep(groupArtifact, version, hash, fullCachePath))
    }

    if (existingLock != null) {
        // Detect kotlin/jvmTarget changes
        if (existingLock.kotlin != config.kotlin || existingLock.jvmTarget != config.jvmTarget) {
            lockChanged = true
        }
        // Detect removed dependencies
        for (key in existingLock.dependencies.keys) {
            if (key !in config.dependencies) {
                lockChanged = true
            }
        }
    }

    return Ok(ResolveResult(resolvedDeps, lockChanged))
}

fun buildLockfileFromResolved(config: KeelConfig, deps: List<ResolvedDep>): Lockfile {
    return Lockfile(
        version = 1,
        kotlin = config.kotlin,
        jvmTarget = config.jvmTarget,
        dependencies = deps.associate { it.groupArtifact to LockEntry(it.version, it.sha256) }
    )
}
