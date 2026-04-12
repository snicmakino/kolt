package kolt.resolve

import com.github.michaelbull.result.Result
import kolt.config.KoltConfig
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error

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
    data class NoNativeVariant(val groupArtifact: String, val nativeTarget: String) : ResolveError()
    data class MetadataParseFailed(val groupArtifact: String) : ResolveError()
    data class MetadataFetchFailed(val groupArtifact: String) : ResolveError()
}

fun formatResolveError(error: ResolveError): String = when (error) {
    is ResolveError.InvalidDependency -> "error: invalid dependency '${error.input}'"
    is ResolveError.Sha256Mismatch -> buildString {
        appendLine("error: sha256 mismatch for ${error.groupArtifact}")
        appendLine("  expected: ${error.expected}")
        append("  got:      ${error.actual}")
    }
    is ResolveError.DownloadFailed -> "error: failed to download ${error.groupArtifact}"
    is ResolveError.HashComputeFailed -> "error: failed to compute hash for ${error.groupArtifact}"
    is ResolveError.DirectoryCreateFailed -> "error: could not create directory ${error.path}"
    is ResolveError.NoNativeVariant ->
        "error: ${error.groupArtifact} has no Kotlin/Native variant for target '${error.nativeTarget}'"
    is ResolveError.MetadataParseFailed ->
        "error: failed to parse Gradle module metadata for ${error.groupArtifact}"
    is ResolveError.MetadataFetchFailed ->
        "error: failed to read Gradle module metadata for ${error.groupArtifact}"
}

data class ResolvedDep(
    val groupArtifact: String,
    val version: String,
    val sha256: String,
    val cachePath: String,
    val transitive: Boolean = false
)

data class ResolveResult(
    val deps: List<ResolvedDep>,
    val lockChanged: Boolean
)

interface ResolverDeps {
    fun fileExists(path: String): Boolean
    fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>
    fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError>
    fun computeSha256(filePath: String): Result<String, Sha256Error>
    fun readFileContent(path: String): Result<String, OpenFailed>
}

fun resolve(
    config: KoltConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> =
    if (config.target == "native") resolveNative(config, cacheBase, deps)
    else resolveTransitive(config, existingLock, cacheBase, deps)

fun buildLockfileFromResolved(config: KoltConfig, deps: List<ResolvedDep>): Lockfile {
    return Lockfile(
        version = 2,
        kotlin = config.kotlin,
        jvmTarget = config.jvmTarget,
        dependencies = deps.associate {
            it.groupArtifact to LockEntry(it.version, it.sha256, it.transitive)
        }
    )
}
