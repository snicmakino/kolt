package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.infra.DownloadError

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
    config: KoltConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps
): Result<ResolveResult, ResolveError> {
    val repos = config.repositories.values.toList()

    // Create POM lookup backed by cache + download
    val basePomLookup = createPomLookup(repos, cacheBase, deps)

    // Track KMP redirects: original groupArtifact -> redirected groupArtifact
    val redirects = mutableMapOf<String, String>()
    val moduleLookup = createModuleLookup(repos, cacheBase, deps)

    val pomLookup: (String, String) -> PomInfo? = { groupArtifact, version ->
        val redirect = moduleLookup(groupArtifact, version)
        if (redirect != null) {
            val redirectedGA = "${redirect.group}:${redirect.module}"
            redirects[groupArtifact] = redirectedGA
            basePomLookup(redirectedGA, redirect.version)
        } else {
            basePomLookup(groupArtifact, version)
        }
    }

    // Pure resolution
    val nodes = resolveGraph(config.dependencies, pomLookup).getOrElse { error ->
        return Err(error)
    }

    // Download JARs and compute hashes
    return materialize(nodes, redirects, config, existingLock, cacheBase, deps, repos)
}

/**
 * Tries each repository in order, downloading to [destPath].
 * Falls back to the next repository only on HTTP 404. Any other error stops immediately.
 */
internal fun downloadFromRepositories(
    repos: List<String>,
    destPath: String,
    urlBuilder: (String) -> String,
    download: (String, String) -> Result<Unit, DownloadError>
): Result<Unit, DownloadError> {
    var lastError: DownloadError? = null
    for (repo in repos) {
        val url = urlBuilder(repo)
        val error = download(url, destPath).getError()
        if (error == null) return Ok(Unit)
        if (error is DownloadError.HttpFailed && error.statusCode == 404) {
            lastError = error
        } else {
            return Err(error)
        }
    }
    return Err(lastError ?: DownloadError.NetworkError("", "no repositories configured"))
}

/**
 * Creates a POM lookup function that downloads, caches, and parses POM files.
 * Parsed POM metadata is cached in memory to avoid re-reading and re-parsing
 * the same POM (e.g., shared parent POMs in diamond dependencies).
 */
internal fun createPomLookup(
    repos: List<String>,
    cacheBase: String,
    deps: ResolverDeps
): (String, String) -> PomInfo? {
    val cache = mutableMapOf<String, PomInfo?>()

    return { groupArtifact, version ->
        val cacheKey = "$groupArtifact:$version"
        cache.getOrPut(cacheKey) {
            val parts = groupArtifact.split(":")
            if (parts.size == 2) {
                val coord = Coordinate(parts[0], parts[1], version)
                val pomCachePath = "$cacheBase/${buildPomCachePath(coord)}"

                // Download POM if not cached on disk
                if (!deps.fileExists(pomCachePath)) {
                    val parentDir = pomCachePath.substringBeforeLast('/')
                    val dirOk = deps.ensureDirectoryRecursive(parentDir).getOrElse { null }
                    if (dirOk != null) {
                        downloadFromRepositories(repos, pomCachePath, { buildPomDownloadUrl(coord, it) }, deps::downloadFile).getOrElse { null }
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
}

/**
 * Creates a memoized lookup function for Gradle Module Metadata JVM redirects.
 * Results (including "no redirect") are cached in memory to avoid redundant
 * .module file downloads for the same coordinate.
 */
private fun createModuleLookup(
    repos: List<String>,
    cacheBase: String,
    deps: ResolverDeps
): (String, String) -> JvmRedirect? {
    // Sentinel to distinguish "checked, no redirect" from "not yet checked"
    val noRedirect = JvmRedirect("", "", "")
    val cache = mutableMapOf<String, JvmRedirect>()

    return { groupArtifact, version ->
        val cacheKey = "$groupArtifact:$version"
        val cached = cache[cacheKey]
        if (cached != null) {
            if (cached === noRedirect) null else cached
        } else {
            val result = checkModuleFile(groupArtifact, version, repos, cacheBase, deps)
            cache[cacheKey] = result ?: noRedirect
            result
        }
    }
}

private fun checkModuleFile(
    groupArtifact: String,
    version: String,
    repos: List<String>,
    cacheBase: String,
    deps: ResolverDeps
): JvmRedirect? {
    val parts = groupArtifact.split(":")
    if (parts.size != 2) return null
    val coord = Coordinate(parts[0], parts[1], version)
    val moduleCachePath = "$cacheBase/${buildModuleCachePath(coord)}"

    // Download .module file if not cached on disk
    if (!deps.fileExists(moduleCachePath)) {
        val parentDir = moduleCachePath.substringBeforeLast('/')
        deps.ensureDirectoryRecursive(parentDir).getOrElse { return null }
        downloadFromRepositories(repos, moduleCachePath, { buildModuleDownloadUrl(coord, it) }, deps::downloadFile).getOrElse { return null }
    }

    val content = deps.readFileContent(moduleCachePath).getOrElse { return null }
    return parseJvmRedirect(content)
}

/**
 * Downloads JARs, computes SHA256 hashes, and checks lockfile for changes.
 * Converts pure [DependencyNode] list into [ResolveResult] with I/O.
 *
 * For KMP libraries, [redirects] maps the original groupArtifact (e.g.,
 * "com.squareup.okhttp3:okhttp") to the JVM-specific artifact (e.g.,
 * "com.squareup.okhttp3:okhttp-jvm"). The lockfile and ResolvedDep keep
 * the original groupArtifact as the key, but the SHA256 hash and cache
 * path correspond to the redirected JAR.
 */
private fun materialize(
    nodes: List<DependencyNode>,
    redirects: Map<String, String>,
    config: KoltConfig,
    existingLock: Lockfile?,
    cacheBase: String,
    deps: ResolverDeps,
    repos: List<String>
): Result<ResolveResult, ResolveError> {
    var lockChanged = false
    val resolvedDeps = mutableListOf<ResolvedDep>()

    for (node in nodes) {
        // Use redirected artifact for JAR download (KMP libraries)
        val jarGroupArtifact = redirects[node.groupArtifact] ?: node.groupArtifact
        val coord = parseCoordinate(jarGroupArtifact, node.version).getOrElse {
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
            downloadFromRepositories(repos, fullCachePath, { buildDownloadUrl(coord, it) }, deps::downloadFile).getOrElse { error ->
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
