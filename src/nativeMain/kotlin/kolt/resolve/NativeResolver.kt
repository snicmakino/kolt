package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.konanTargetGradleName

// Kotlin/Native bundles the stdlib in the konanc distribution — it must be
// skipped during dependency resolution even though Gradle module metadata
// declares it as a dependency on every native variant.
// kotlin-stdlib and kotlin-stdlib-common are both bundled by konanc and must
// not be resolved from Maven: the former has no native variant published in
// the shape our resolver expects, and the latter (a pre-Gradle-metadata
// artifact from the 1.8-era split) publishes only a `.pom`, not a `.module`,
// so the native resolver's `.module` fetch would 404. See ADR 0011.
private fun isKotlinStdlib(groupArtifact: String): Boolean =
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib" ||
        groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib-common"

private data class NativeResolved(val redirect: NativeRedirect, val artifact: NativeArtifact)

private data class NativePass(
    val resolvedVersions: Map<String, Pair<String, Boolean>>,
    val processed: Map<String, NativeResolved>
)

/**
 * Resolves Kotlin/Native dependencies by walking Gradle Module Metadata.
 *
 * Resolution iterates a BFS pass to a fixpoint. Each pass:
 * 1. Fetches the root `.module` file and finds the available-at redirect for
 *    the current native target (linux_x64 in Phase B).
 * 2. Fetches the redirect target's `.module` file and extracts the `.klib`
 *    file reference (url + sha256) and the variant's `dependencies[]`.
 * 3. Seeds each child version with the prior pass's committed version so an
 *    upgrade that happened late in the previous pass is visible at every
 *    child-selection site in this pass. Children pulled only by a superseded
 *    version stop being enqueued and drop out of the result.
 * 4. Skips stdlib artifacts covered by [isKotlinStdlib] (konanc bundles them).
 *
 * After the fixpoint is reached, each resolved `(groupArtifact, version)` has
 * its `.klib` downloaded and hashed; the sha256 is verified against the
 * metadata.
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
    val nativeTarget = konanTargetGradleName(config.build.target)
    val repos = config.repositories.values.toList()

    for ((groupArtifact, _) in config.dependencies) {
        if (isKotlinStdlib(groupArtifact)) continue
        parseCoordinate(groupArtifact, "0").getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }
    }

    val directDeps = config.dependencies.filterKeys { !isKotlinStdlib(it) }
    var committed: Map<String, Pair<String, Boolean>> =
        directDeps.mapValues { (_, v) -> Pair(v, true) }
    var finalProcessed: Map<String, NativeResolved> = emptyMap()

    while (true) {
        val pass = resolveNativeOnce(
            directDeps, committed, nativeTarget, cacheBase, repos, deps
        ).getOrElse { return Err(it) }
        finalProcessed = pass.processed
        if (pass.resolvedVersions == committed) break
        committed = pass.resolvedVersions
    }

    val resolvedDeps = mutableListOf<ResolvedDep>()
    for ((groupArtifact, versionAndDirect) in committed) {
        val (version, isDirect) = versionAndDirect
        val resolved = finalProcessed["$groupArtifact:$version"]
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

private fun resolveNativeOnce(
    directDeps: Map<String, String>,
    committed: Map<String, Pair<String, Boolean>>,
    nativeTarget: String,
    cacheBase: String,
    repos: List<String>,
    deps: ResolverDeps
): Result<NativePass, ResolveError> {
    val resolvedVersions = mutableMapOf<String, Pair<String, Boolean>>()
    val queue = ArrayDeque<Pair<String, String>>()
    val visited = mutableSetOf<String>()
    val processed = mutableMapOf<String, NativeResolved>()
    // Per-GA union of rejects declared by every contributor seen so far.
    // Accumulated even when the contributor's own version proposal loses the
    // conflict, mirroring Gradle's "rejects applies to the GA globally".
    // A post-BFS recheck below verifies every resolved version against this
    // final set, so an earlier-accepted version rejected by a later-seen
    // contributor is caught as an error rather than silently kept.
    val accumulatedRejects = mutableMapOf<String, MutableList<String>>()
    // Per-GA strict pin. Any other proposal for the same GA is a hard error.
    val strictPins = mutableMapOf<String, String>()

    for ((groupArtifact, version) in directDeps) {
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

        for (dep in resolved.artifact.dependencies) {
            val depGA = "${dep.group}:${dep.module}"
            if (isKotlinStdlib(depGA)) continue

            if (dep.rejects.isNotEmpty()) {
                accumulatedRejects.getOrPut(depGA) { mutableListOf() }.addAll(dep.rejects)
            }

            val patterns = accumulatedRejects[depGA]
            if (patterns != null && patterns.any { matchesRejectPattern(dep.version, it) }) continue

            val pin = strictPins[depGA]
            if (pin != null && pin != dep.version) {
                return Err(
                    ResolveError.StrictVersionConflict(depGA, pin, dep.version, otherIsStrict = dep.strict)
                )
            }
            if (dep.strict) {
                val alreadyResolved = resolvedVersions[depGA]
                if (alreadyResolved != null && alreadyResolved.first != dep.version) {
                    return Err(
                        ResolveError.StrictVersionConflict(depGA, dep.version, alreadyResolved.first)
                    )
                }
                strictPins[depGA] = dep.version
            }

            val committedEntry = committed[depGA]
            val depVersion = when {
                committedEntry == null -> dep.version
                committedEntry.second -> continue
                compareVersions(committedEntry.first, dep.version) > 0 -> committedEntry.first
                else -> dep.version
            }

            val existing = resolvedVersions[depGA]
            if (existing != null) {
                val (existingVersion, isDirect) = existing
                if (isDirect) continue
                if (compareVersions(depVersion, existingVersion) <= 0) continue
            }
            resolvedVersions[depGA] = Pair(depVersion, false)
            queue.addLast(depGA to depVersion)
        }
    }

    // After BFS within this pass, re-check every resolved version against the
    // accumulated rejects. Catches the case where a later-seen contributor's
    // rejects would have blocked an earlier-accepted version (including direct
    // deps): the BFS can't re-queue mid-pass. Rejects are per-pass, but the
    // fixpoint driver rebuilds accumulatedRejects every pass from the same
    // contributors, so any reject that fires in pass N also fires in pass N+1.
    for ((groupArtifact, versionAndDirect) in resolvedVersions) {
        val patterns = accumulatedRejects[groupArtifact] ?: continue
        val version = versionAndDirect.first
        val matched = patterns.firstOrNull { matchesRejectPattern(version, it) } ?: continue
        return Err(ResolveError.RejectedVersionResolved(groupArtifact, version, matched))
    }

    return Ok(NativePass(resolvedVersions, processed))
}

/**
 * Builds a lookup function for [buildNativeDependencyTree]. For each
 * (groupArtifact, version), fetches and parses the Gradle module metadata
 * twice (root + redirect target) and returns a [NativeNodeInfo] containing
 * the redirected display coordinate and the transitive dependencies from
 * the linux_x64 variant. Returns null on any fetch or parse failure so the
 * tree walker can render a partial graph instead of aborting.
 *
 * Results are memoized by (groupArtifact, version) so diamond dependencies
 * don't re-fetch and re-parse the same `.module` files on every occurrence,
 * matching [createPomLookup]'s caching behaviour.
 */
fun createNativeLookup(
    repos: List<String>,
    cacheBase: String,
    deps: ResolverDeps,
    nativeTarget: String
): (String, String) -> NativeNodeInfo? {
    val cache = mutableMapOf<String, NativeNodeInfo?>()

    return { groupArtifact, version ->
        val cacheKey = "$groupArtifact:$version"
        cache.getOrPut(cacheKey) {
            val resolved = fetchNativeMetadata(
                groupArtifact = groupArtifact,
                version = version,
                nativeTarget = nativeTarget,
                cacheBase = cacheBase,
                repos = repos,
                deps = deps
            ).getOrElse { null }

            resolved?.let {
                NativeNodeInfo(
                    displayGroupArtifact = "${it.redirect.group}:${it.redirect.module}",
                    displayVersion = it.redirect.version,
                    dependencies = it.artifact.dependencies.map { dep ->
                        "${dep.group}:${dep.module}" to dep.version
                    }
                )
            }
        }
    }
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
