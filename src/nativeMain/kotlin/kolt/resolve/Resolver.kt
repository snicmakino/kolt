package kolt.resolve

import com.github.michaelbull.result.Result
import kolt.config.KoltConfig
import kolt.config.NATIVE_TARGETS
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

// Real-IO ResolverDeps wired to kolt.infra. Lives here (rather than in
// kolt.cli) so non-CLI callers — currently kolt.build.daemon.BtaImplFetcher
// — can use it without an upward import.
internal fun defaultResolverDeps(): ResolverDeps = object : ResolverDeps {
    override fun fileExists(path: String): Boolean = kolt.infra.fileExists(path)
    override fun ensureDirectoryRecursive(path: String) = kolt.infra.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String) = kolt.infra.downloadFile(url, destPath)
    override fun computeSha256(filePath: String) = kolt.infra.computeSha256(filePath)
    override fun readFileContent(path: String) = kolt.infra.readFileAsString(path)
}

fun resolve(
    config: KoltConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> =
    if (config.build.target in NATIVE_TARGETS) resolveNative(config, cacheBase, deps)
    else resolveTransitive(config, existingLock, cacheBase, deps)

fun buildLockfileFromResolved(config: KoltConfig, deps: List<ResolvedDep>): Lockfile {
    return Lockfile(
        version = 2,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = deps.associate {
            it.groupArtifact to LockEntry(it.version, it.sha256, it.transitive)
        }
    )
}
