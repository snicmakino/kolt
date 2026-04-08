package keel

data class TreeNode(
    val groupArtifact: String,
    val version: String,
    val children: List<TreeNode> = emptyList()
)

/**
 * Build a dependency tree from direct dependencies and a POM lookup function.
 * Pure function — POM fetching is abstracted behind [pomLookup].
 */
fun buildDependencyTree(
    directDeps: Map<String, String>,
    pomLookup: (groupArtifact: String, version: String) -> PomInfo?
): List<TreeNode> {
    val visited = mutableSetOf<String>()
    return directDeps.map { (groupArtifact, version) ->
        buildNode(groupArtifact, version, pomLookup, visited)
    }
}

private fun buildNode(
    groupArtifact: String,
    version: String,
    pomLookup: (String, String) -> PomInfo?,
    visited: MutableSet<String>
): TreeNode {
    val key = "$groupArtifact:$version"
    if (key in visited) {
        return TreeNode(groupArtifact, version)
    }
    visited.add(key)

    val pomInfo = pomLookup(groupArtifact, version)
    val children = pomInfo?.dependencies.orEmpty()
        .filter { isIncludedDep(it) }
        .map { dep ->
            val childGa = "${dep.groupId}:${dep.artifactId}"
            val childVersion = dep.version ?: return@map null
            buildNode(childGa, childVersion, pomLookup, visited)
        }
        .filterNotNull()

    return TreeNode(groupArtifact, version, children)
}

private fun isIncludedDep(dep: PomDependency): Boolean {
    if (dep.optional) return false
    val scope = dep.scope ?: "compile"
    return scope == "compile" || scope == "runtime"
}

/**
 * Format a dependency tree as indented text.
 * Duplicate subtrees are marked with (*).
 */
fun formatDependencyTree(tree: List<TreeNode>): String {
    if (tree.isEmpty()) return "no dependencies"
    val seen = mutableSetOf<String>()
    val lines = mutableListOf<String>()
    for (node in tree) {
        formatNode(node, 0, seen, lines)
    }
    return lines.joinToString("\n")
}

private fun formatNode(
    node: TreeNode,
    depth: Int,
    seen: MutableSet<String>,
    lines: MutableList<String>
) {
    val indent = "  ".repeat(depth)
    val key = "${node.groupArtifact}:${node.version}"
    if (key in seen) {
        lines.add("$indent$key (*)")
        return
    }
    seen.add(key)
    lines.add("$indent$key")
    for (child in node.children) {
        formatNode(child, depth + 1, seen, lines)
    }
}
