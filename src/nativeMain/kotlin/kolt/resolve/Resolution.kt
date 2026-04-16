package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

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

// Direct deps always win; among transitives, highest version wins.
// Exclusions propagate transitively.
fun resolveGraph(
    directDeps: Map<String, String>,
    pomLookup: (groupArtifact: String, version: String) -> PomInfo?
): Result<List<DependencyNode>, ResolveError> {
    val resolvedVersions = mutableMapOf<String, Pair<String, Boolean>>()
    val queue = ArrayDeque<QueueEntry>()
    val visited = mutableSetOf<String>()

    for ((groupArtifact, version) in directDeps) {
        parseCoordinate(groupArtifact, version).getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }
        resolvedVersions[groupArtifact] = Pair(version, true)
        queue.addLast(QueueEntry(groupArtifact, version, emptySet()))
    }

    while (queue.isNotEmpty()) {
        val entry = queue.removeFirst()
        val visitKey = "${entry.groupArtifact}:${entry.version}"
        if (visitKey in visited) continue
        visited.add(visitKey)

        val pomInfo = pomLookup(entry.groupArtifact, entry.version) ?: continue

        val depMgmt = collectDepMgmt(pomInfo, pomLookup)

        for (pomDep in pomInfo.dependencies) {
            if (!isIncludedScope(pomDep.scope)) continue
            if (pomDep.optional) continue

            val depGroupArtifact = "${pomDep.groupId}:${pomDep.artifactId}"

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
