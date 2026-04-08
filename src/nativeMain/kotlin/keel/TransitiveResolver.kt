package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

/**
 * Resolves dependencies transitively. Orchestrates I/O (POM/JAR fetching,
 * hashing) around the pure [resolveGraph] algorithm.
 *
 * 1. Creates a POM lookup function backed by cache + download
 * 2. Calls [resolveGraph] to resolve the dependency graph
 * 3. Downloads JARs and computes SHA256 hashes
 * 4. Detects lockfile changes
 */
fun resolveTransitive(
    config: KeelConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    // Create POM lookup backed by cache + download
    val pomLookup = createPomLookup(cacheBase, deps)

    // Pure resolution
    val nodes = resolveGraph(config.dependencies, pomLookup).getOrElse { error ->
        return Err(error)
    }

    // Download JARs and compute hashes
    return materialize(nodes, config, existingLock, cacheBase, deps)
}

/**
 * Creates a POM lookup function that downloads, caches, and parses POM files.
 */
private fun createPomLookup(
    cacheBase: String,
    deps: ResolverDeps
): (String, String) -> PomInfo? {
    return { groupArtifact, version ->
        val parts = groupArtifact.split(":")
        if (parts.size == 2) {
            val coord = Coordinate(parts[0], parts[1], version)
            val pomCachePath = "$cacheBase/${buildPomCachePath(coord)}"

            // Download POM if not cached
            if (!deps.fileExists(pomCachePath)) {
                val parentDir = pomCachePath.substringBeforeLast('/')
                val dirOk = deps.ensureDirectoryRecursive(parentDir).getOrElse { null }
                if (dirOk != null) {
                    deps.downloadFile(buildPomDownloadUrl(coord), pomCachePath).getOrElse { null }
                }
            }

            // Read and parse POM
            val content = deps.readFileContent(pomCachePath).getOrElse { null }
            content?.let { parsePom(it).getOrElse { null } }
        } else {
            null
        }
    }
}

/**
 * Downloads JARs, computes SHA256 hashes, and checks lockfile for changes.
 * Converts pure [DependencyNode] list into [ResolveResult] with I/O.
 */
private fun materialize(
    nodes: List<DependencyNode>,
    config: KeelConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    var lockChanged = false
    val resolvedDeps = mutableListOf<ResolvedDep>()

    for (node in nodes) {
        val coord = parseCoordinate(node.groupArtifact, node.version).getOrElse {
            return Err(ResolveError.InvalidDependency(node.groupArtifact))
        }

        val relativePath = buildCachePath(coord)
        val fullCachePath = "$cacheBase/$relativePath"
        val lockEntry = existingLock?.dependencies?.get(node.groupArtifact)

        // Download JAR if not cached
        if (!deps.fileExists(fullCachePath)) {
            val parentDir = fullCachePath.substringBeforeLast('/')
            deps.ensureDirectoryRecursive(parentDir).getOrElse {
                return Err(ResolveError.DirectoryCreateFailed(parentDir))
            }
            deps.downloadFile(buildDownloadUrl(coord), fullCachePath).getOrElse { error ->
                return Err(ResolveError.DownloadFailed(node.groupArtifact, error))
            }
            lockChanged = true
        }

        // Compute SHA256
        val hash = deps.computeSha256(fullCachePath).getOrElse { error ->
            return Err(ResolveError.HashComputeFailed(node.groupArtifact, error))
        }

        // Verify against lockfile
        if (lockEntry != null) {
            if (lockEntry.version != node.version) {
                lockChanged = true
            } else if (lockEntry.sha256 != hash) {
                return Err(ResolveError.Sha256Mismatch(node.groupArtifact, lockEntry.sha256, hash))
            }
        } else {
            lockChanged = true
        }

        resolvedDeps.add(ResolvedDep(node.groupArtifact, node.version, hash, fullCachePath, transitive = !node.direct))
    }

    if (existingLock != null) {
        if (existingLock.kotlin != config.kotlin || existingLock.jvmTarget != config.jvmTarget) {
            lockChanged = true
        }
        val resolvedKeys = nodes.map { it.groupArtifact }.toSet()
        for (key in existingLock.dependencies.keys) {
            if (key !in resolvedKeys) {
                lockChanged = true
            }
        }
    }

    return Ok(ResolveResult(resolvedDeps, lockChanged))
}
