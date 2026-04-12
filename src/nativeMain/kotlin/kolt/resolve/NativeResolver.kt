package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig

// Phase B is linuxX64-only. Cross-platform support lands in a later phase.
internal const val NATIVE_TARGET_LINUX_X64 = "linux_x64"

// Kotlin/Native bundles the stdlib in the konanc distribution — it must be
// skipped during dependency resolution even though Gradle module metadata
// declares it as a dependency on every native variant.
private fun isKotlinStdlib(groupArtifact: String): Boolean =
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib"

private data class NativeResolved(val redirect: NativeRedirect, val artifact: NativeArtifact)

/**
 * Resolves Kotlin/Native dependencies by walking Gradle Module Metadata.
 *
 * For each dependency in the BFS queue:
 * 1. Fetch the root `.module` file and find the available-at redirect for
 *    the current native target (linux_x64 in Phase B).
 * 2. Fetch the redirect target's `.module` file and extract the `.klib`
 *    file reference (url + sha256) and the variant's `dependencies[]`.
 * 3. Enqueue each transitive dependency (except `kotlin-stdlib`, which
 *    konanc bundles).
 *
 * After BFS, each resolved `(groupArtifact, version)` has its `.klib`
 * downloaded and hashed; the sha256 is verified against the metadata.
 *
 * Version conflict resolution mirrors the JVM resolver:
 * - Direct deps always win over transitive.
 * - Among transitive deps, the highest version wins.
 *
 * The lockfile is not consulted or written for native targets in Phase B;
 * `lockChanged` is always false. Lockfile support for native comes later.
 */
fun resolveNative(
    config: KoltConfig,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    val nativeTarget = NATIVE_TARGET_LINUX_X64
    val repos = config.repositories.values.toList()

    // Validate direct coords up front (mirrors jvm resolver)
    for ((groupArtifact, _) in config.dependencies) {
        if (isKotlinStdlib(groupArtifact)) continue
        parseCoordinate(groupArtifact, "0").getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }
    }

    // resolvedVersions: groupArtifact -> (version, isDirect)
    val resolvedVersions = mutableMapOf<String, Pair<String, Boolean>>()
    val queue = ArrayDeque<Pair<String, String>>()
    val visited = mutableSetOf<String>()
    // processed[ga:version] = (redirect, artifact) populated during BFS
    val processed = mutableMapOf<String, NativeResolved>()

    // Seed direct deps (skipping stdlib)
    for ((groupArtifact, version) in config.dependencies) {
        if (isKotlinStdlib(groupArtifact)) continue
        resolvedVersions[groupArtifact] = Pair(version, true)
        queue.addLast(groupArtifact to version)
    }

    while (queue.isNotEmpty()) {
        val (groupArtifact, version) = queue.removeFirst()
        val visitKey = "$groupArtifact:$version"
        if (visitKey in visited) continue
        visited.add(visitKey)

        // A higher version may have superseded this entry while it waited in
        // the queue. Skip fetching metadata for versions that no longer match
        // the current resolution — we only need to materialize the final one.
        if (resolvedVersions[groupArtifact]?.first != version) continue

        val resolved = fetchNativeMetadata(
            groupArtifact, version, nativeTarget, cacheBase, repos, deps
        ).getOrElse { return Err(it) }
        processed[visitKey] = resolved

        // Enqueue transitive deps
        for (dep in resolved.artifact.dependencies) {
            val depGA = "${dep.group}:${dep.module}"
            if (isKotlinStdlib(depGA)) continue

            val existing = resolvedVersions[depGA]
            if (existing != null) {
                val (existingVersion, isDirect) = existing
                if (isDirect) continue
                if (compareVersions(dep.version, existingVersion) <= 0) continue
            }
            resolvedVersions[depGA] = Pair(dep.version, false)
            queue.addLast(depGA to dep.version)
        }
    }

    // Materialize: for each resolved (ga, version), download the .klib and
    // verify its sha256. processed[ga:version] is guaranteed to exist because
    // BFS visits every version that ends up in resolvedVersions.
    val resolvedDeps = mutableListOf<ResolvedDep>()
    for ((groupArtifact, versionAndDirect) in resolvedVersions) {
        val (version, isDirect) = versionAndDirect
        val resolved = processed["$groupArtifact:$version"]
            ?: return Err(ResolveError.MetadataParseFailed(groupArtifact))

        val targetCoord = Coordinate(
            resolved.redirect.group,
            resolved.redirect.module,
            resolved.redirect.version
        )
        val klibCachePath = "$cacheBase/${buildKlibCachePath(targetCoord)}"

        if (!deps.fileExists(klibCachePath)) {
            val parentDir = klibCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
                return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            downloadFromRepositories(
                repos,
                klibCachePath,
                { buildKlibDownloadUrl(targetCoord, it) },
                deps::downloadFile
            ).getOrElse { return Err(ResolveError.DownloadFailed(groupArtifact, it)) }
        }

        val actualHash = deps.computeSha256(klibCachePath).getOrElse {
            return Err(ResolveError.HashComputeFailed(groupArtifact, it))
        }
        if (actualHash != resolved.artifact.klibSha256) {
            return Err(ResolveError.Sha256Mismatch(groupArtifact, resolved.artifact.klibSha256, actualHash))
        }

        resolvedDeps.add(
            ResolvedDep(
                groupArtifact = groupArtifact,
                version = version,
                sha256 = actualHash,
                cachePath = klibCachePath,
                transitive = !isDirect
            )
        )
    }

    return Ok(ResolveResult(deps = resolvedDeps, lockChanged = false))
}

/**
 * Fetches and parses both the root `.module` (for the available-at redirect)
 * and the platform-specific `.module` (for the klib file + dependencies) for
 * a single coordinate.
 */
private fun fetchNativeMetadata(
    groupArtifact: String,
    version: String,
    nativeTarget: String,
    cacheBase: String,
    repos: List<String>,
    deps: ResolverDeps
): Result<NativeResolved, ResolveError> {
    val rootCoord = parseCoordinate(groupArtifact, version).getOrElse {
        return Err(ResolveError.InvalidDependency(groupArtifact))
    }

    val rootJson = fetchAndRead(
        coord = rootCoord,
        relativePath = buildModuleCachePath(rootCoord),
        urlBuilder = { buildModuleDownloadUrl(rootCoord, it) },
        cacheBase = cacheBase,
        repos = repos,
        deps = deps
    ).getOrElse { return Err(it) }

    if (!isValidGradleModuleJson(rootJson)) {
        return Err(ResolveError.MetadataParseFailed(groupArtifact))
    }
    val redirect = parseNativeRedirect(rootJson, nativeTarget)
        ?: return Err(ResolveError.NoNativeVariant(groupArtifact, nativeTarget))

    val targetCoord = Coordinate(redirect.group, redirect.module, redirect.version)
    val targetJson = fetchAndRead(
        coord = targetCoord,
        relativePath = buildModuleCachePath(targetCoord),
        urlBuilder = { buildModuleDownloadUrl(targetCoord, it) },
        cacheBase = cacheBase,
        repos = repos,
        deps = deps
    ).getOrElse { return Err(it) }

    if (!isValidGradleModuleJson(targetJson)) {
        return Err(ResolveError.MetadataParseFailed(groupArtifact))
    }
    val artifact = parseNativeArtifact(targetJson, nativeTarget)
        ?: return Err(ResolveError.MetadataParseFailed(groupArtifact))

    return Ok(NativeResolved(redirect, artifact))
}

/**
 * Downloads a file from the first available repository if it is not yet on
 * disk, then reads and returns its contents. Used for `.module` fetches.
 */
private fun fetchAndRead(
    coord: Coordinate,
    relativePath: String,
    urlBuilder: (String) -> String,
    cacheBase: String,
    repos: List<String>,
    deps: ResolverDeps
): Result<String, ResolveError> {
    val groupArtifact = "${coord.group}:${coord.artifact}"
    val cachePath = "$cacheBase/$relativePath"

    if (!deps.fileExists(cachePath)) {
        val parentDir = cachePath.substringBeforeLast('/')
        deps.ensureDirectoryRecursive(parentDir).getOrElse {
            return Err(ResolveError.DirectoryCreateFailed(parentDir))
        }
        downloadFromRepositories(repos, cachePath, urlBuilder, deps::downloadFile).getOrElse {
            return Err(ResolveError.DownloadFailed(groupArtifact, it))
        }
    }
    val content = deps.readFileContent(cachePath).getOrElse {
        return Err(ResolveError.MetadataFetchFailed(groupArtifact))
    }
    return Ok(content)
}
