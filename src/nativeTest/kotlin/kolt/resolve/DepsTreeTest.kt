package kolt.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepsTreeTest {

    private fun pomInfo(deps: List<PomDependency> = emptyList()) = PomInfo(
        parent = null, groupId = null, artifactId = "", version = null,
        properties = emptyMap(), dependencyManagement = emptyList(),
        dependencies = deps
    )

    private fun pomDep(group: String, artifact: String, version: String) =
        PomDependency(group, artifact, version, scope = null, optional = false)

    @Test
    fun singleDirectDependencyNoTransitives() {
        val directDeps = mapOf("com.example:lib-a" to "1.0.0")
        val pomLookup = { _: String, _: String -> pomInfo() }

        val tree = buildDependencyTree(directDeps, pomLookup)

        assertEquals(1, tree.size)
        assertEquals("com.example:lib-a", tree[0].groupArtifact)
        assertEquals("1.0.0", tree[0].version)
        assertEquals(0, tree[0].children.size)
    }

    @Test
    fun directWithTransitiveDependencies() {
        val directDeps = mapOf("com.example:lib-a" to "1.0.0")
        val pomLookup = { ga: String, _: String ->
            when (ga) {
                "com.example:lib-a" -> pomInfo(listOf(pomDep("com.example", "lib-b", "2.0.0")))
                else -> pomInfo()
            }
        }

        val tree = buildDependencyTree(directDeps, pomLookup)

        assertEquals(1, tree.size)
        assertEquals(1, tree[0].children.size)
        assertEquals("com.example:lib-b", tree[0].children[0].groupArtifact)
        assertEquals("2.0.0", tree[0].children[0].version)
    }

    @Test
    fun formatTreeOutput() {
        val tree = listOf(
            TreeNode("com.example:lib-a", "1.0.0", children = listOf(
                TreeNode("com.example:lib-b", "2.0.0", children = listOf(
                    TreeNode("com.example:lib-c", "3.0.0")
                ))
            )),
            TreeNode("com.example:lib-d", "1.0.0")
        )

        val output = formatDependencyTree(tree)
        val expected = listOf(
            "├── com.example:lib-a:1.0.0",
            "│   └── com.example:lib-b:2.0.0",
            "│       └── com.example:lib-c:3.0.0",
            "└── com.example:lib-d:1.0.0"
        ).joinToString("\n")
        assertEquals(expected, output)
    }

    @Test
    fun duplicateSubtreeMarkedWithStar() {
        val directDeps = mapOf(
            "com.example:lib-a" to "1.0.0",
            "com.example:lib-b" to "1.0.0"
        )
        val pomLookup = { ga: String, _: String ->
            when (ga) {
                "com.example:lib-a" -> pomInfo(listOf(pomDep("com.example", "lib-c", "1.0.0")))
                "com.example:lib-b" -> pomInfo(listOf(pomDep("com.example", "lib-c", "1.0.0")))
                else -> pomInfo()
            }
        }

        val tree = buildDependencyTree(directDeps, pomLookup)
        val output = formatDependencyTree(tree)
        val expected = listOf(
            "├── com.example:lib-a:1.0.0",
            "│   └── com.example:lib-c:1.0.0",
            "└── com.example:lib-b:1.0.0",
            "    └── com.example:lib-c:1.0.0 (*)"
        ).joinToString("\n")
        assertEquals(expected, output)
    }

    @Test
    fun formatTreeSingleRoot() {
        val tree = listOf(
            TreeNode("com.example:lib-a", "1.0.0")
        )

        val output = formatDependencyTree(tree)
        assertEquals("└── com.example:lib-a:1.0.0", output)
    }

    @Test
    fun formatTreeWithMultipleChildrenUsesConnectors() {
        val tree = listOf(
            TreeNode("com.example:root", "1.0.0", children = listOf(
                TreeNode("com.example:child-a", "1.0.0"),
                TreeNode("com.example:child-b", "2.0.0"),
                TreeNode("com.example:child-c", "3.0.0")
            ))
        )

        val output = formatDependencyTree(tree)
        val expected = listOf(
            "└── com.example:root:1.0.0",
            "    ├── com.example:child-a:1.0.0",
            "    ├── com.example:child-b:2.0.0",
            "    └── com.example:child-c:3.0.0"
        ).joinToString("\n")
        assertEquals(expected, output)
    }

    private fun nativeInfo(
        displayGroupArtifact: String,
        displayVersion: String,
        deps: List<Pair<String, String>> = emptyList()
    ) = NativeNodeInfo(displayGroupArtifact, displayVersion, deps)

    @Test
    fun nativeTreeDisplaysRedirectedCoordinate() {
        val directDeps = mapOf("org.jetbrains.kotlinx:kotlinx-serialization-json" to "1.7.3")
        val lookup = { ga: String, v: String ->
            if (ga == "org.jetbrains.kotlinx:kotlinx-serialization-json" && v == "1.7.3") {
                nativeInfo("org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", "1.7.3")
            } else null
        }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        assertEquals("org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", tree[0].groupArtifact)
        assertEquals("1.7.3", tree[0].version)
        assertEquals(0, tree[0].children.size)
    }

    @Test
    fun nativeTreeWalksTransitiveDependencies() {
        val directDeps = mapOf("org.jetbrains.kotlinx:kotlinx-serialization-json" to "1.7.3")
        val lookup = { ga: String, _: String ->
            when (ga) {
                "org.jetbrains.kotlinx:kotlinx-serialization-json" -> nativeInfo(
                    "org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", "1.7.3",
                    deps = listOf("org.jetbrains.kotlinx:kotlinx-serialization-core" to "1.7.3")
                )
                "org.jetbrains.kotlinx:kotlinx-serialization-core" -> nativeInfo(
                    "org.jetbrains.kotlinx:kotlinx-serialization-core-linuxx64", "1.7.3"
                )
                else -> null
            }
        }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        val root = tree[0]
        assertEquals("org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", root.groupArtifact)
        assertEquals(1, root.children.size)
        assertEquals("org.jetbrains.kotlinx:kotlinx-serialization-core-linuxx64", root.children[0].groupArtifact)
        assertEquals("1.7.3", root.children[0].version)
    }

    @Test
    fun nativeTreeSkipsKotlinStdlibAsDirect() {
        val directDeps = mapOf(
            "org.jetbrains.kotlin:kotlin-stdlib" to "2.0.20",
            "org.jetbrains.kotlinx:kotlinx-serialization-json" to "1.7.3"
        )
        val lookup = { ga: String, _: String ->
            if (ga == "org.jetbrains.kotlinx:kotlinx-serialization-json")
                nativeInfo("org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", "1.7.3")
            else null
        }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        assertEquals("org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", tree[0].groupArtifact)
    }

    @Test
    fun nativeTreeSkipsKotlinStdlibAsTransitive() {
        val directDeps = mapOf("org.jetbrains.kotlinx:kotlinx-serialization-json" to "1.7.3")
        val lookup = { ga: String, _: String ->
            if (ga == "org.jetbrains.kotlinx:kotlinx-serialization-json") nativeInfo(
                "org.jetbrains.kotlinx:kotlinx-serialization-json-linuxx64", "1.7.3",
                deps = listOf(
                    "org.jetbrains.kotlin:kotlin-stdlib" to "2.0.20",
                    "org.jetbrains.kotlin:kotlin-stdlib-common" to "2.0.20"
                )
            ) else null
        }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        assertEquals(0, tree[0].children.size)
    }

    @Test
    fun nativeTreeHandlesCycleViaVisited() {
        val directDeps = mapOf("com.example:lib-a" to "1.0")
        val lookup = { ga: String, _: String ->
            when (ga) {
                "com.example:lib-a" -> nativeInfo(
                    "com.example:lib-a-linuxx64", "1.0",
                    deps = listOf("com.example:lib-b" to "1.0")
                )
                "com.example:lib-b" -> nativeInfo(
                    "com.example:lib-b-linuxx64", "1.0",
                    deps = listOf("com.example:lib-a" to "1.0") // cycle back
                )
                else -> null
            }
        }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        val libA = tree[0]
        assertEquals("com.example:lib-a-linuxx64", libA.groupArtifact)
        assertEquals(1, libA.children.size)
        val libB = libA.children[0]
        assertEquals("com.example:lib-b-linuxx64", libB.groupArtifact)
        assertEquals(1, libB.children.size)
        assertEquals(0, libB.children[0].children.size)
    }

    @Test
    fun nativeTreeRendersOriginalCoordinateWhenLookupReturnsNull() {
        val directDeps = mapOf("com.example:missing" to "9.9.9")
        val lookup = { _: String, _: String -> null }

        val tree = buildNativeDependencyTree(directDeps, lookup)

        assertEquals(1, tree.size)
        assertEquals("com.example:missing", tree[0].groupArtifact)
        assertEquals("9.9.9", tree[0].version)
        assertEquals(0, tree[0].children.size)
    }

    @Test
    fun formatTreeEmpty() {
        assertEquals("no dependencies", formatDependencyTree(emptyList()))
    }
}
