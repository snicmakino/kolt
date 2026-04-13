package kolt.resolve

data class TreeNode(
    val groupArtifact: String,
    val version: String,
    val children: List<TreeNode> = emptyList()
)

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
 * Information required to render a single node in the native dependency tree.
 * The display coordinate is the *redirected* target (e.g. `...-linuxx64`), so
 * users see the klib that will actually be linked rather than the root module
 * coordinate they declared in `kolt.toml`. Transitive deps are the contents of
 * the linux_x64 variant's `dependencies[]` from Gradle Module Metadata.
 */
data class NativeNodeInfo(
    val displayGroupArtifact: String,
    val displayVersion: String,
    val dependencies: List<Pair<String, String>> = emptyList()
)

/**
 * Native counterpart of [buildDependencyTree]. Walks Gradle Module Metadata
 * via [nativeLookup] instead of POMs, rendering the redirected `-linuxx64`
 * display names and skipping kotlin-stdlib / kotlin-stdlib-common (ADR 0011)
 * at both direct and transitive positions.
 *
 * Note: this is a *display* walker, not a resolver. It does not perform the
 * "highest-version-wins" conflict resolution that [kolt.resolve.resolveNative]
 * applies across the whole graph, so two branches of the rendered tree can
 * show different versions of the same coordinate while the actual link would
 * pick only the highest. Matches the behaviour of [buildDependencyTree] for
 * JVM targets.
 *
 * When [nativeLookup] returns null (metadata missing or unparseable for a
 * coordinate), the tree renders that coordinate as a leaf with no children
 * rather than aborting — a partial tree is still useful.
 */
fun buildNativeDependencyTree(
    directDeps: Map<String, String>,
    nativeLookup: (groupArtifact: String, version: String) -> NativeNodeInfo?
): List<TreeNode> {
    val visited = mutableSetOf<String>()
    return directDeps.mapNotNull { (groupArtifact, version) ->
        if (isNativeStdlibSkipped(groupArtifact)) null
        else buildNativeNode(groupArtifact, version, nativeLookup, visited)
    }
}

private fun buildNativeNode(
    groupArtifact: String,
    version: String,
    lookup: (String, String) -> NativeNodeInfo?,
    visited: MutableSet<String>
): TreeNode {
    val info = lookup(groupArtifact, version)
        ?: return TreeNode(groupArtifact, version)

    val key = "${info.displayGroupArtifact}:${info.displayVersion}"
    if (key in visited) {
        return TreeNode(info.displayGroupArtifact, info.displayVersion)
    }
    visited.add(key)

    val children = info.dependencies
        .filterNot { (ga, _) -> isNativeStdlibSkipped(ga) }
        .map { (childGa, childVersion) -> buildNativeNode(childGa, childVersion, lookup, visited) }

    return TreeNode(info.displayGroupArtifact, info.displayVersion, children)
}

// Mirrors NativeResolver's stdlib skip policy (ADR 0011). Duplicated here so
// the tree walker can be used with a stub lookup in tests without pulling in
// NativeResolver; the two predicates must stay in sync.
private fun isNativeStdlibSkipped(groupArtifact: String): Boolean =
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib" ||
        groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib-common"

private const val BRANCH = "├── "
private const val CORNER = "└── "
private const val PIPE = "│   "
private const val SPACE = "    "

fun formatDependencyTree(tree: List<TreeNode>): String {
    if (tree.isEmpty()) return "no dependencies"
    val seen = mutableSetOf<String>()
    val lines = mutableListOf<String>()
    for ((index, node) in tree.withIndex()) {
        val isLast = index == tree.lastIndex
        formatNode(node, "", isLast, seen, lines)
    }
    return lines.joinToString("\n")
}

private fun formatNode(
    node: TreeNode,
    prefix: String,
    isLast: Boolean,
    seen: MutableSet<String>,
    lines: MutableList<String>
) {
    val connector = if (isLast) CORNER else BRANCH
    val key = "${node.groupArtifact}:${node.version}"
    if (key in seen) {
        lines.add("$prefix$connector$key (*)")
        return
    }
    seen.add(key)
    lines.add("$prefix$connector$key")
    val newPrefix = prefix + if (isLast) SPACE else PIPE
    for ((index, child) in node.children.withIndex()) {
        formatNode(child, newPrefix, index == node.children.lastIndex, seen, lines)
    }
}
