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

/**
 * A child as seen by the resolution kernel. Populated by resolver-specific
 * lookup adapters (pom-based for JVM, gradle-metadata-based for native).
 *
 * Fields other than [groupArtifact] and [version] default to empty so a
 * resolver that doesn't use a feature (e.g. JVM has no strict/rejects today)
 * doesn't have to populate it.
 */
data class Child(
    val groupArtifact: String,
    val version: String,
    val strict: Boolean = false,
    val rejects: List<String> = emptyList(),
    val exclusions: Set<PomExclusion> = emptySet()
)

/**
 * Immutable snapshot of the resolution state between fixpoint passes.
 *
 * The kernel treats a pass as `Resolution -> Result<Resolution, ResolveError>`;
 * the fixpoint loop iterates until [versions] stops changing. Internal per-pass
 * scratch buffers (rejects, strict pins, visited, queue) stay mutable for
 * readability and are rebuilt each pass from the same contributors, so they
 * don't need to be threaded across passes today. Future reachability-aware
 * resolution (#216) may need to extend this record.
 */
data class Resolution(
    val versions: Map<String, Pair<String, Boolean>>
)

private data class QueueEntry(
    val groupArtifact: String,
    val version: String,
    val exclusions: Set<PomExclusion>
)

/**
 * Shared resolution kernel.
 *
 * Direct deps always win; among transitives, highest version wins. Exclusions
 * propagate transitively; strict pins and rejects error out.
 *
 * Resolution iterates a BFS pass to a fixpoint. Each pass walks direct deps
 * and, for every visited GA, fetches its children using the *prior pass's
 * chosen version* — so a GA that gets upgraded in pass N is traversed through
 * its new version in pass N+1, and children pulled only by the superseded
 * version drop out. Inside one pass the `visited` set is keyed by GA (not by
 * GA+version), so the first dequeue for a GA determines which version's
 * children are enumerated this pass. All version proposals are collected and
 * reduced by highest-wins at the end of the pass.
 */
fun fixpointResolve(
    directDeps: Map<String, String>,
    childLookup: (groupArtifact: String, version: String) -> Result<List<Child>, ResolveError>
): Result<List<DependencyNode>, ResolveError> {
    for ((groupArtifact, version) in directDeps) {
        parseCoordinate(groupArtifact, version).getOrElse {
            return Err(ResolveError.InvalidDependency(groupArtifact))
        }
    }

    var state = Resolution(
        versions = directDeps.mapValues { (_, v) -> Pair(v, true) }
    )

    // Safety cap on iterations. Fixpoint convergence is not proven monotone in
    // pathological graphs; a non-converging input should fail loudly rather
    // than hang. Bumped well past any realistic depth.
    repeat(MAX_FIXPOINT_PASSES) {
        val next = iterate(state, directDeps, childLookup).getOrElse { return Err(it) }
        if (next.versions == state.versions) {
            return Ok(next.versions.map { (ga, vd) -> DependencyNode(ga, vd.first, vd.second) })
        }
        state = next
    }
    return Err(ResolveError.ResolutionDidNotConverge(MAX_FIXPOINT_PASSES))
}

private const val MAX_FIXPOINT_PASSES = 100

private fun iterate(
    prev: Resolution,
    directDeps: Map<String, String>,
    childLookup: (String, String) -> Result<List<Child>, ResolveError>
): Result<Resolution, ResolveError> {
    // ga -> all version proposals this pass (highest-wins reduced at the end).
    val proposals = mutableMapOf<String, MutableList<String>>()
    val queue = ArrayDeque<QueueEntry>()
    // ga:version-keyed. Same GA may dequeue at multiple versions; the
    // winning-version gate below decides which one actually fetches children.
    val visited = mutableSetOf<String>()
    // Per-GA union of rejects declared by every contributor seen this pass.
    val rejects = mutableMapOf<String, MutableList<String>>()
    // Per-GA strict pin. Any other proposal for the same GA is a hard error.
    val strictPins = mutableMapOf<String, String>()

    for ((groupArtifact, version) in directDeps) {
        proposals.getOrPut(groupArtifact) { mutableListOf() }.add(version)
        queue.addLast(QueueEntry(groupArtifact, version, emptySet()))
    }

    while (queue.isNotEmpty()) {
        val entry = queue.removeFirst()
        val ga = entry.groupArtifact
        val visitKey = "$ga:${entry.version}"
        if (visitKey in visited) continue

        // Winning-version gate: only fetch children for the version that will
        // actually win for this GA. `prev.versions[ga]` wins when set (the
        // previous pass's committed choice); otherwise fall back to the highest
        // proposal seen so far in this pass. This avoids fetching metadata for
        // a losing version (e.g., diamond test whose losing version has no
        // published metadata at all).
        val winningVersion = prev.versions[ga]?.first
            ?: proposals.getValue(ga).reduce { a, b -> if (compareVersions(a, b) >= 0) a else b }
        if (entry.version != winningVersion) continue
        visited.add(visitKey)

        val children = childLookup(ga, entry.version).getOrElse { return Err(it) }

        for (child in children) {
            val cga = child.groupArtifact

            if (isExcluded(cga, entry.exclusions)) continue

            if (child.rejects.isNotEmpty()) {
                rejects.getOrPut(cga) { mutableListOf() }.addAll(child.rejects)
            }

            val patterns = rejects[cga]
            if (patterns != null && patterns.any { matchesRejectPattern(child.version, it) }) continue

            val pin = strictPins[cga]
            if (pin != null && pin != child.version) {
                return Err(
                    ResolveError.StrictVersionConflict(cga, pin, child.version, otherIsStrict = child.strict)
                )
            }
            if (child.strict) {
                val existing = proposals[cga]?.firstOrNull { it != child.version }
                if (existing != null) {
                    return Err(ResolveError.StrictVersionConflict(cga, child.version, existing))
                }
                strictPins[cga] = child.version
            }

            proposals.getOrPut(cga) { mutableListOf() }.add(child.version)

            val mergedExclusions = entry.exclusions + child.exclusions
            queue.addLast(QueueEntry(cga, child.version, mergedExclusions))
        }
    }

    // Reduce proposals to a single chosen version per GA: direct wins,
    // otherwise highest-wins across everything proposed this pass.
    val newVersions = mutableMapOf<String, Pair<String, Boolean>>()
    for ((ga, v) in directDeps) {
        newVersions[ga] = Pair(v, true)
    }
    for ((ga, versionList) in proposals) {
        if (ga in directDeps) continue
        val highest = versionList.reduce { a, b -> if (compareVersions(a, b) >= 0) a else b }
        newVersions[ga] = Pair(highest, false)
    }

    // Re-check chosen versions against the fully-populated rejects. Catches
    // the case where a later-seen contributor's rejects would have blocked an
    // earlier-accepted version (including direct deps): the BFS can't re-queue
    // mid-pass. Rejects are rebuilt each pass from the same contributors, so
    // any reject that fires in pass N also fires in pass N+1.
    for ((ga, versionAndDirect) in newVersions) {
        val patterns = rejects[ga] ?: continue
        val matched = patterns.firstOrNull { matchesRejectPattern(versionAndDirect.first, it) } ?: continue
        return Err(ResolveError.RejectedVersionResolved(ga, versionAndDirect.first, matched))
    }

    return Ok(Resolution(versions = newVersions.toMap()))
}

/**
 * JVM / pom-based adapter. Kept as a thin wrapper so existing callers and
 * tests can keep passing `pomLookup` directly; the kernel itself is
 * [fixpointResolve].
 */
fun resolveGraph(
    directDeps: Map<String, String>,
    pomLookup: (groupArtifact: String, version: String) -> PomInfo?
): Result<List<DependencyNode>, ResolveError> =
    fixpointResolve(directDeps, pomChildLookup(pomLookup))

private fun pomChildLookup(
    pomLookup: (String, String) -> PomInfo?
): (String, String) -> Result<List<Child>, ResolveError> = { groupArtifact, version ->
    val pomInfo = pomLookup(groupArtifact, version)
    if (pomInfo == null) {
        Ok(emptyList())
    } else {
        val depMgmt = collectDepMgmt(pomInfo, pomLookup)
        val children = pomInfo.dependencies.mapNotNull { pomDep ->
            if (!isIncludedScope(pomDep.scope)) return@mapNotNull null
            if (pomDep.optional) return@mapNotNull null
            val depGA = "${pomDep.groupId}:${pomDep.artifactId}"
            val rawVersion = pomDep.version ?: depMgmt[depGA] ?: return@mapNotNull null
            Child(
                groupArtifact = depGA,
                version = selectVersion(rawVersion),
                exclusions = pomDep.exclusions.toSet()
            )
        }
        Ok(children)
    }
}

private fun isExcluded(groupArtifact: String, exclusions: Set<PomExclusion>): Boolean {
    if (exclusions.isEmpty()) return false
    val parts = groupArtifact.split(":", limit = 2)
    if (parts.size != 2) return false
    val (groupId, artifactId) = parts
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
