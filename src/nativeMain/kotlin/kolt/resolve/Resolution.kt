package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

data class DependencyNode(
  val groupArtifact: String,
  val version: String,
  val direct: Boolean,
  val origin: Origin = Origin.MAIN,
)

/**
 * A child as seen by the resolution kernel. Populated by resolver-specific lookup adapters
 * (pom-based for JVM, gradle-metadata-based for native).
 *
 * Fields other than [groupArtifact] and [version] default to empty so a resolver that doesn't use a
 * feature (e.g. JVM has no strict/rejects today) doesn't have to populate it.
 */
data class Child(
  val groupArtifact: String,
  val version: String,
  val strict: Boolean = false,
  val rejects: List<String> = emptyList(),
  val exclusions: Set<PomExclusion> = emptySet(),
)

/**
 * Immutable snapshot of the resolution state between fixpoint passes.
 *
 * The kernel treats a pass as `Resolution -> Result<Resolution, ResolveError>`; the fixpoint loop
 * iterates until [versions] stops changing. Internal per-pass scratch buffers (rejects, strict
 * pins, visited, queue) stay mutable for readability and are rebuilt each pass from the same
 * contributors, so they don't need to be threaded across passes today.
 */
data class Resolution(val versions: Map<String, Pair<String, Boolean>>)

private data class QueueEntry(
  val groupArtifact: String,
  val version: String,
  val exclusions: Set<PomExclusion>,
)

/**
 * Shared resolution kernel.
 *
 * Direct deps always win; among transitives, highest version wins. Exclusions propagate
 * transitively; strict pins and rejects error out.
 *
 * Resolution iterates a BFS pass to a fixpoint. Each pass seeds child versions with the prior
 * pass's committed version (highest-wins), so an upgrade that happened late in pass N is visible at
 * every child-selection site in pass N+1. Children pulled only by a superseded version stop
 * reappearing and drop out of the result.
 */
fun fixpointResolve(
  directDeps: Map<String, String>,
  childLookup: (groupArtifact: String, version: String) -> Result<List<Child>, ResolveError>,
): Result<List<DependencyNode>, ResolveError> {
  for ((groupArtifact, version) in directDeps) {
    parseCoordinate(groupArtifact, version).getOrElse {
      return Err(ResolveError.InvalidDependency(groupArtifact))
    }
  }

  var state = Resolution(versions = directDeps.mapValues { (_, v) -> Pair(v, true) })

  while (true) {
    val next =
      iterate(state, directDeps, childLookup).getOrElse {
        return Err(it)
      }
    if (next.versions == state.versions) {
      return Ok(next.versions.map { (ga, vd) -> DependencyNode(ga, vd.first, vd.second) })
    }
    state = next
  }
}

private fun iterate(
  prev: Resolution,
  directDeps: Map<String, String>,
  childLookup: (String, String) -> Result<List<Child>, ResolveError>,
): Result<Resolution, ResolveError> {
  val versions = mutableMapOf<String, Pair<String, Boolean>>()
  val queue = ArrayDeque<QueueEntry>()
  val visited = mutableSetOf<String>()
  // Per-GA union of rejects declared by every contributor seen so far.
  // Accumulated even when the contributor's own version proposal loses the
  // conflict, mirroring Gradle's "rejects applies to the GA globally".
  val rejects = mutableMapOf<String, MutableList<String>>()
  // Per-GA strict pin. Any other proposal for the same GA is a hard error.
  val strictPins = mutableMapOf<String, String>()

  for ((groupArtifact, version) in directDeps) {
    versions[groupArtifact] = Pair(version, true)
    queue.addLast(QueueEntry(groupArtifact, version, emptySet()))
  }

  while (queue.isNotEmpty()) {
    val entry = queue.removeFirst()
    // Path-aware exclusion: a (ga, version) reached through two different
    // parent paths with different exclusion sets must enumerate children
    // under each set independently. Keying visited by exclusions lets both
    // visits proceed so that a child excluded on one path can still reach
    // the resolved set via the other.
    val visitKey = "${entry.groupArtifact}:${entry.version}:${exclusionsKey(entry.exclusions)}"
    if (visitKey in visited) continue
    visited.add(visitKey)

    // A higher version may have superseded this entry while it waited in
    // the queue. Skip the lookup for versions no longer current — only the
    // final version needs its children enumerated.
    if (versions[entry.groupArtifact]?.first != entry.version) continue

    val children =
      childLookup(entry.groupArtifact, entry.version).getOrElse {
        return Err(it)
      }

    for (child in children) {
      val depGA = child.groupArtifact

      if (isExcluded(depGA, entry.exclusions)) continue

      if (child.rejects.isNotEmpty()) {
        rejects.getOrPut(depGA) { mutableListOf() }.addAll(child.rejects)
      }

      val patterns = rejects[depGA]
      if (patterns != null && patterns.any { matchesRejectPattern(child.version, it) }) continue

      val pin = strictPins[depGA]
      if (pin != null && pin != child.version) {
        return Err(
          ResolveError.StrictVersionConflict(
            depGA,
            pin,
            child.version,
            otherIsStrict = child.strict,
          )
        )
      }
      if (child.strict) {
        val alreadyResolved = versions[depGA]
        if (alreadyResolved != null && alreadyResolved.first != child.version) {
          return Err(
            ResolveError.StrictVersionConflict(depGA, child.version, alreadyResolved.first)
          )
        }
        strictPins[depGA] = child.version
      }

      val committedEntry = prev.versions[depGA]
      val depVersion =
        when {
          committedEntry == null -> child.version
          committedEntry.second -> continue
          compareVersions(committedEntry.first, child.version) > 0 -> committedEntry.first
          else -> child.version
        }

      val existing = versions[depGA]
      if (existing != null) {
        val (existingVersion, isDirect) = existing
        // Direct-dep wins even over a transitive path with a different
        // exclusion context: the user-declared direct dep decides the
        // children, not a transitive's exclusion list. Matches Gradle.
        if (isDirect) continue
        // Keep enqueueing same-version entries with different exclusion
        // sets so the per-path exclusion check runs against each one.
        // Strictly-lower versions stay skipped (they'd lose the
        // highest-wins anyway).
        if (compareVersions(depVersion, existingVersion) < 0) continue
      }

      val mergedExclusions = entry.exclusions + child.exclusions
      if (existing == null || compareVersions(depVersion, existing.first) > 0) {
        versions[depGA] = Pair(depVersion, false)
      }
      queue.addLast(QueueEntry(depGA, depVersion, mergedExclusions))
    }
  }

  // Re-check resolved versions against the fully-populated rejects. Catches
  // the case where a later-seen contributor's rejects would have blocked an
  // earlier-accepted version (including direct deps): the BFS can't re-queue
  // mid-pass. Rejects are rebuilt each pass from the same contributors, so
  // any reject that fires in pass N also fires in pass N+1.
  for ((groupArtifact, versionAndDirect) in versions) {
    val patterns = rejects[groupArtifact] ?: continue
    val version = versionAndDirect.first
    val matched = patterns.firstOrNull { matchesRejectPattern(version, it) } ?: continue
    return Err(ResolveError.RejectedVersionResolved(groupArtifact, version, matched))
  }

  return Ok(Resolution(versions = versions.toMap()))
}

/**
 * JVM / pom-based adapter. Kept as a thin wrapper so existing callers and tests can keep passing
 * `pomLookup` directly; the kernel itself is [fixpointResolve].
 */
fun resolveGraph(
  directDeps: Map<String, String>,
  pomLookup: (groupArtifact: String, version: String) -> PomInfo?,
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
    val children =
      pomInfo.dependencies.mapNotNull { pomDep ->
        if (!isIncludedScope(pomDep.scope)) return@mapNotNull null
        if (pomDep.optional) return@mapNotNull null
        val depGA = "${pomDep.groupId}:${pomDep.artifactId}"
        val rawVersion = pomDep.version ?: depMgmt[depGA] ?: return@mapNotNull null
        Child(
          groupArtifact = depGA,
          version = selectVersion(rawVersion),
          exclusions = pomDep.exclusions.toSet(),
        )
      }
    Ok(children)
  }
}

private fun exclusionsKey(exclusions: Set<PomExclusion>): String {
  if (exclusions.isEmpty()) return ""
  return exclusions.sortedWith(compareBy({ it.groupId }, { it.artifactId })).joinToString("|") {
    "${it.groupId}:${it.artifactId}"
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
  depth: Int = 0,
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
