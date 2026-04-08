package keel

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
            "com.example:lib-a:1.0.0",
            "  com.example:lib-b:2.0.0",
            "    com.example:lib-c:3.0.0",
            "com.example:lib-d:1.0.0"
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
        assertTrue(output.contains("com.example:lib-c:1.0.0"))
        assertTrue(output.contains("(*)"))
    }

    @Test
    fun formatTreeEmpty() {
        assertEquals("no dependencies", formatDependencyTree(emptyList()))
    }
}
