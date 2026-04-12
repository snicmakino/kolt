package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

/**
 * A resolved dependency node in the dependency graph.
 * Pure data — no file paths, hashes, or I/O concerns.
 */
data class DependencyNode(
    val groupArtifact: String,
    val version: String,
    val direct: Boolean
)

private data class QueueEntry(
    val groupArtifact: String,
    val version: String,
    val exclusions: Set<PomExclusion>
)

/**
 * Pure dependency graph resolution via BFS.
 *
 * Given direct dependencies and a POM lookup function, resolves the full
 * transitive dependency graph. The algorithm is independent of I/O — POM
 * fetching is abstracted behind [pomLookup].
 *
 * Resolution rules:
 * - Direct deps always win over transitive version conflicts
 * - Among transitive deps, highest version wins
 * - Scopes: only `compile` and `runtime` are included
 * - Optional dependencies are skipped
 * - Exclusions propagate transitively through the dependency chain
 * - Cycles are detected via visited set
 * - Parent POM chain is followed for dependencyManagement version lookup
 *
 * @param directDeps Map of groupArtifact -> version from project config
 * @param pomLookup Function that returns parsed POM for a given (groupArtifact, version),
 *                  or null if unavailable. May perform I/O internally.
 */
fun resolveGraph(
    directDeps: Map<String, String>,
    pomLookup: (groupArtifact: String, version: String) -> PomInfo?
): Result<List<DependencyNode>, ResolveError> {
    // Track resolved versions: groupArtifact -> (version, isDirect)
    val resolvedVersions = mutableMapOf<String, Pair<String, Boolean>>()
    val queue = ArrayDeque<QueueEntry>()
    val visited = mutableSetOf<String>() // "groupArtifact:version"

    // Seed with direct deps (no exclusions at the root level)
    for ((groupArtifact, version) in directDeps) {
        parseCoordinate(groupArtifact, version).getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }
        resolvedVersions[groupArtifact] = Pair(version, true)
        queue.addLast(QueueEntry(groupArtifact, version, emptySet()))
    }

    // BFS
    while (queue.isNotEmpty()) {
        val entry = queue.removeFirst()
        val visitKey = "${entry.groupArtifact}:${entry.version}"
        if (visitKey in visited) continue
        visited.add(visitKey)

        val pomInfo = pomLookup(entry.groupArtifact, entry.version) ?: continue

        // Collect dependencyManagement from this POM and its parent chain
        val depMgmt = collectDepMgmt(pomInfo, pomLookup)

        // Process transitive dependencies
        for (pomDep in pomInfo.dependencies) {
            if (!isIncludedScope(pomDep.scope)) continue
            if (pomDep.optional) continue

            val depGroupArtifact = "${pomDep.groupId}:${pomDep.artifactId}"

            // Check if this dep is excluded by inherited exclusions
            if (isExcluded(pomDep.groupId, pomDep.artifactId, entry.exclusions)) continue

            val rawVersion = pomDep.version
                ?: depMgmt[depGroupArtifact]
                ?: continue
            val depVersion = selectVersion(rawVersion)

            val existing = resolvedVersions[depGroupArtifact]
            if (existing != null) {
                val (existingVersion, isDirect) = existing
                if (isDirect) continue
                if (compareVersions(depVersion, existingVersion) <= 0) continue
            }

            // Merge inherited exclusions with this dep's own exclusions
            val mergedExclusions = entry.exclusions + pomDep.exclusions.toSet()

            resolvedVersions[depGroupArtifact] = Pair(depVersion, false)
            queue.addLast(QueueEntry(depGroupArtifact, depVersion, mergedExclusions))
        }
    }

    val nodes = resolvedVersions.map { (groupArtifact, versionAndDirect) ->
        DependencyNode(groupArtifact, versionAndDirect.first, versionAndDirect.second)
    }
    return Ok(nodes)
}

/**
 * Checks if a dependency is excluded by the given exclusion set.
 * Supports wildcards: `*` matches any group or artifact.
 */
private fun isExcluded(groupId: String, artifactId: String, exclusions: Set<PomExclusion>): Boolean {
    return exclusions.any { ex ->
        (ex.groupId == "*" || ex.groupId == groupId) &&
            (ex.artifactId == "*" || ex.artifactId == artifactId)
    }
}

private fun isIncludedScope(scope: String?): Boolean {
    val s = scope ?: "compile"
    return s == "compile" || s == "runtime"
}

/**
 * Collects dependencyManagement entries from a POM and its parent chain.
 * Pure function — parent POM lookup is done via the same [pomLookup] function.
 */
private fun collectDepMgmt(
    pomInfo: PomInfo,
    pomLookup: (String, String) -> PomInfo?,
    depth: Int = 0
): Map<String, String> {
    if (depth > 10) return emptyMap()

    val result = mutableMapOf<String, String>()

    for (managed in pomInfo.dependencyManagement) {
        val key = "${managed.groupId}:${managed.artifactId}"
        if (managed.version != null && key !in result) {
            result[key] = managed.version
        }
    }

    val parent = pomInfo.parent ?: return result
    val parentGroupArtifact = "${parent.groupId}:${parent.artifactId}"
    val parentPomInfo = pomLookup(parentGroupArtifact, parent.version) ?: return result

    val parentMgmt = collectDepMgmt(parentPomInfo, pomLookup, depth + 1)
    for ((key, version) in parentMgmt) {
        if (key !in result) {
            result[key] = version
        }
    }

    return result
}
