package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResolutionTest {

    @Test
    fun resolveGraphWithNoDeps() {
        val result = resolveGraph(emptyMap()) { _, _ -> null }
        val nodes = assertNotNull(result.get())
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun resolveGraphSingleDirectDep() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo("com.example", "lib", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        assertEquals(1, nodes.size)
        assertEquals("com.example:lib", nodes[0].groupArtifact)
        assertEquals("1.0.0", nodes[0].version)
        assertTrue(nodes[0].direct)
    }

    @Test
    fun resolveGraphSingleTransitiveDep() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(pomDep("com.example", "transitive", "2.0.0"))
            ),
            "com.example:transitive:2.0.0" to pomInfo("com.example", "transitive", "2.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        assertEquals(2, nodes.size)

        val direct = nodes.first { it.groupArtifact == "com.example:lib" }
        assertTrue(direct.direct)

        val transitive = nodes.first { it.groupArtifact == "com.example:transitive" }
        assertFalse(transitive.direct)
        assertEquals("2.0.0", transitive.version)
    }

    @Test
    fun resolveGraphDiamondHighestVersionWins() {
        val poms = mapOf(
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "1.0.0"))
            ),
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "2.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0"),
            "com.example:c:2.0.0" to pomInfo("com.example", "c", "2.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:a" to "1.0.0", "com.example:b" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val c = nodes.first { it.groupArtifact == "com.example:c" }
        assertEquals("2.0.0", c.version)
    }

    @Test
    fun resolveGraphDirectDepsWinOverTransitive() {
        val poms = mapOf(
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "2.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:a" to "1.0.0", "com.example:c" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val c = nodes.first { it.groupArtifact == "com.example:c" }
        assertEquals("1.0.0", c.version)
        assertTrue(c.direct)
    }

    @Test
    fun resolveGraphFiltersTestAndProvidedScope() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "compile-dep", "1.0.0"),
                    pomDep("com.example", "runtime-dep", "1.0.0", scope = "runtime"),
                    pomDep("junit", "junit", "4.13.2", scope = "test"),
                    pomDep("javax.servlet", "servlet-api", "3.0", scope = "provided")
                )
            ),
            "com.example:compile-dep:1.0.0" to pomInfo("com.example", "compile-dep", "1.0.0"),
            "com.example:runtime-dep:1.0.0" to pomInfo("com.example", "runtime-dep", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertEquals(3, nodes.size)
        assertTrue("com.example:compile-dep" in names)
        assertTrue("com.example:runtime-dep" in names)
        assertFalse("junit:junit" in names)
    }

    @Test
    fun resolveGraphSkipsOptionalDeps() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "required", "1.0.0"),
                    pomDep("com.example", "optional-lib", "1.0.0", optional = true)
                )
            ),
            "com.example:required:1.0.0" to pomInfo("com.example", "required", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertTrue("com.example:required" in names)
        assertFalse("com.example:optional-lib" in names)
    }

    @Test
    fun resolveGraphDetectsCycles() {
        val poms = mapOf(
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "b", "1.0.0"))
            ),
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "a", "1.0.0"))
            )
        )
        val result = resolveGraph(
            mapOf("com.example:a" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        assertEquals(2, nodes.size)
    }

    @Test
    fun resolveGraphMultiLevel() {
        val poms = mapOf(
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "b", "1.0.0"))
            ),
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "1.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:a" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        assertEquals(3, nodes.size)
    }

    @Test
    fun resolveGraphWithDependencyManagement() {
        val childPom = PomInfo(
            parent = PomParent("com.example", "parent-pom", "1.0.0"),
            groupId = null,
            artifactId = "child",
            version = null,
            properties = emptyMap(),
            dependencyManagement = emptyList(),
            dependencies = listOf(PomDependency("com.example", "managed", null, null, false))
        )
        val parentPom = pomInfo(
            "com.example", "parent-pom", "1.0.0",
            depMgmt = listOf(pomDep("com.example", "managed", "3.0.0"))
        )
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(pomDep("com.example", "child", "1.0.0"))
            ),
            "com.example:child:1.0.0" to childPom,
            "com.example:parent-pom:1.0.0" to parentPom,
            "com.example:managed:3.0.0" to pomInfo("com.example", "managed", "3.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val managed = nodes.first { it.groupArtifact == "com.example:managed" }
        assertEquals("3.0.0", managed.version)
    }

    @Test
    fun resolveGraphInvalidCoordinateReturnsErr() {
        val result = resolveGraph(
            mapOf("invalid-no-colon" to "1.0.0")
        ) { _, _ -> null }
        assertIs<ResolveError.InvalidDependency>(result.getError())
    }

    @Test
    fun resolveGraphPomNotFoundSkipsTransitiveDeps() {
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0")
        ) { _, _ -> null }  // No POMs available
        val nodes = assertNotNull(result.get())
        assertEquals(1, nodes.size)
        assertEquals("com.example:lib", nodes[0].groupArtifact)
    }

    @Test
    fun resolveGraphExcludesSpecificTransitiveDep() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "a", "1.0.0",
                        exclusions = listOf(PomExclusion("com.example", "b")))
                )
            ),
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "b", "1.0.0"))
            ),
            "com.example:b:1.0.0" to pomInfo("com.example", "b", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertTrue("com.example:lib" in names)
        assertTrue("com.example:a" in names)
        assertFalse("com.example:b" in names)
    }

    @Test
    fun resolveGraphWildcardExclusionExcludesAllTransitives() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "a", "1.0.0",
                        exclusions = listOf(PomExclusion("*", "*")))
                )
            ),
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "b", "1.0.0"),
                    pomDep("com.example", "c", "1.0.0")
                )
            ),
            "com.example:b:1.0.0" to pomInfo("com.example", "b", "1.0.0"),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertTrue("com.example:lib" in names)
        assertTrue("com.example:a" in names)
        assertFalse("com.example:b" in names)
        assertFalse("com.example:c" in names)
    }

    @Test
    fun resolveGraphExclusionsPropagateTransitively() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "a", "1.0.0",
                        exclusions = listOf(PomExclusion("com.example", "c")))
                )
            ),
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(pomDep("com.example", "b", "1.0.0"))
            ),
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "1.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertTrue("com.example:a" in names)
        assertTrue("com.example:b" in names)
        assertFalse("com.example:c" in names)
    }

    @Test
    fun resolveGraphExclusionDoesNotAffectOtherPaths() {
        val poms = mapOf(
            "com.example:a:1.0.0" to pomInfo(
                "com.example", "a", "1.0.0",
                deps = listOf(
                    pomDep("com.example", "c", "1.0.0",
                        exclusions = emptyList())
                )
            ),
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "1.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        // a has exclusion on c declared at the direct dep level
        val aWithExclusion = pomInfo(
            "com.example", "a", "1.0.0",
            deps = listOf(pomDep("com.example", "c", "1.0.0"))
        )
        val pomsWithExclusion = mapOf(
            "com.example:a:1.0.0" to aWithExclusion,
            "com.example:b:1.0.0" to pomInfo(
                "com.example", "b", "1.0.0",
                deps = listOf(pomDep("com.example", "c", "1.0.0"))
            ),
            "com.example:c:1.0.0" to pomInfo("com.example", "c", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:a" to "1.0.0", "com.example:b" to "1.0.0"),
            pomsWithExclusion.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val names = nodes.map { it.groupArtifact }.toSet()
        assertTrue("com.example:c" in names)
    }

    private fun pomDep(
        group: String,
        artifact: String,
        version: String? = null,
        scope: String? = null,
        optional: Boolean = false,
        exclusions: List<PomExclusion> = emptyList()
    ) = PomDependency(group, artifact, version, scope, optional, exclusions)

    private fun pomInfo(
        group: String,
        artifact: String,
        version: String,
        deps: List<PomDependency> = emptyList(),
        depMgmt: List<PomDependency> = emptyList(),
        properties: Map<String, String> = emptyMap()
    ) = PomInfo(
        parent = null,
        groupId = group,
        artifactId = artifact,
        version = version,
        properties = properties,
        dependencyManagement = depMgmt,
        dependencies = deps
    )

    @Test
    fun resolveGraphVersionRangeSelectsConcreteVersion() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(pomDep("com.example", "util", "[1.0.0,2.0.0)"))
            ),
            "com.example:util:1.0.0" to pomInfo("com.example", "util", "1.0.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val util = nodes.first { it.groupArtifact == "com.example:util" }
        assertEquals("1.0.0", util.version)
    }

    @Test
    fun resolveGraphPinnedIntervalSelectsExactVersion() {
        val poms = mapOf(
            "com.example:lib:1.0.0" to pomInfo(
                "com.example", "lib", "1.0.0",
                deps = listOf(pomDep("com.example", "util", "[3.5.0]"))
            ),
            "com.example:util:3.5.0" to pomInfo("com.example", "util", "3.5.0")
        )
        val result = resolveGraph(
            mapOf("com.example:lib" to "1.0.0"),
            poms.pomLookup()
        )
        val nodes = assertNotNull(result.get())
        val util = nodes.first { it.groupArtifact == "com.example:util" }
        assertEquals("3.5.0", util.version)
    }

    private fun Map<String, PomInfo>.pomLookup(): (String, String) -> PomInfo? = { groupArtifact, version ->
        this["$groupArtifact:$version"]
    }
}
