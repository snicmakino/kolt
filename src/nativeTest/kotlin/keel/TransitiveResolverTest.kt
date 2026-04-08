package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransitiveResolverTest {

    @Test
    fun resolveWithNoTransitiveDeps() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val pomXml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
        assertFalse(resolved.deps[0].transitive)
    }

    @Test
    fun resolveSingleTransitiveDep() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val libPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>transitive</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val transitivePom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>transitive</artifactId>
                <version>2.0.0</version>
            </project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1",
                "/cache/com/example/transitive/2.0.0/transitive-2.0.0.jar" to "hash2"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom,
                "/cache/com/example/transitive/2.0.0/transitive-2.0.0.pom" to transitivePom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(2, resolved.deps.size)

        val direct = resolved.deps.first { it.groupArtifact == "com.example:lib" }
        assertFalse(direct.transitive)

        val transitive = resolved.deps.first { it.groupArtifact == "com.example:transitive" }
        assertTrue(transitive.transitive)
        assertEquals("2.0.0", transitive.version)
    }

    @Test
    fun diamondDependencyHighestVersionWins() {
        // A -> C:1.0, B -> C:2.0 => C:2.0 should win
        val config = testConfig().copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )
        val aPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>a</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>c</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val bPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>b</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>c</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val c1Pom = """
            <project><groupId>com.example</groupId><artifactId>c</artifactId><version>1.0.0</version></project>
        """.trimIndent()
        val c2Pom = """
            <project><groupId>com.example</groupId><artifactId>c</artifactId><version>2.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
                "/cache/com/example/b/1.0.0/b-1.0.0.jar" to "hashB",
                "/cache/com/example/c/2.0.0/c-2.0.0.jar" to "hashC2"
            ),
            pomContents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
                "/cache/com/example/b/1.0.0/b-1.0.0.pom" to bPom,
                "/cache/com/example/c/1.0.0/c-1.0.0.pom" to c1Pom,
                "/cache/com/example/c/2.0.0/c-2.0.0.pom" to c2Pom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val c = resolved.deps.first { it.groupArtifact == "com.example:c" }
        assertEquals("2.0.0", c.version)
    }

    @Test
    fun scopeFilteringSkipsTestAndProvided() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val libPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>compile-dep</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>runtime-dep</artifactId>
                        <version>1.0.0</version>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                        <scope>test</scope>
                    </dependency>
                    <dependency>
                        <groupId>javax.servlet</groupId>
                        <artifactId>servlet-api</artifactId>
                        <version>3.0</version>
                        <scope>provided</scope>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val compilePom = """
            <project><groupId>com.example</groupId><artifactId>compile-dep</artifactId><version>1.0.0</version></project>
        """.trimIndent()
        val runtimePom = """
            <project><groupId>com.example</groupId><artifactId>runtime-dep</artifactId><version>1.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1",
                "/cache/com/example/compile-dep/1.0.0/compile-dep-1.0.0.jar" to "hash2",
                "/cache/com/example/runtime-dep/1.0.0/runtime-dep-1.0.0.jar" to "hash3"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom,
                "/cache/com/example/compile-dep/1.0.0/compile-dep-1.0.0.pom" to compilePom,
                "/cache/com/example/runtime-dep/1.0.0/runtime-dep-1.0.0.pom" to runtimePom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        // Should have lib + compile-dep + runtime-dep (3 total), no junit or servlet-api
        assertEquals(3, resolved.deps.size)
        val names = resolved.deps.map { it.groupArtifact }.toSet()
        assertTrue("com.example:compile-dep" in names)
        assertTrue("com.example:runtime-dep" in names)
        assertFalse("junit:junit" in names)
        assertFalse("javax.servlet:servlet-api" in names)
    }

    @Test
    fun optionalDepsAreSkipped() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val libPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>required</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>optional-lib</artifactId>
                        <version>1.0.0</version>
                        <optional>true</optional>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val requiredPom = """
            <project><groupId>com.example</groupId><artifactId>required</artifactId><version>1.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1",
                "/cache/com/example/required/1.0.0/required-1.0.0.jar" to "hash2"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom,
                "/cache/com/example/required/1.0.0/required-1.0.0.pom" to requiredPom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(2, resolved.deps.size)
        val names = resolved.deps.map { it.groupArtifact }.toSet()
        assertTrue("com.example:required" in names)
        assertFalse("com.example:optional-lib" in names)
    }

    @Test
    fun cycleDetection() {
        // A -> B -> A (cycle)
        val config = testConfig().copy(
            dependencies = mapOf("com.example:a" to "1.0.0")
        )
        val aPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>a</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>b</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val bPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>b</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>a</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
                "/cache/com/example/b/1.0.0/b-1.0.0.jar" to "hashB"
            ),
            pomContents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
                "/cache/com/example/b/1.0.0/b-1.0.0.pom" to bPom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        // Should resolve both without infinite loop
        assertEquals(2, resolved.deps.size)
    }

    @Test
    fun parentPomVersionInheritance() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val libPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>child</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val childPom = """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>child</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>managed</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val parentPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>parent-pom</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>managed</artifactId>
                            <version>3.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()
        val managedPom = """
            <project><groupId>com.example</groupId><artifactId>managed</artifactId><version>3.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1",
                "/cache/com/example/child/1.0.0/child-1.0.0.jar" to "hash2",
                "/cache/com/example/managed/3.0.0/managed-3.0.0.jar" to "hash3"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom,
                "/cache/com/example/child/1.0.0/child-1.0.0.pom" to childPom,
                "/cache/com/example/parent-pom/1.0.0/parent-pom-1.0.0.pom" to parentPom,
                "/cache/com/example/managed/3.0.0/managed-3.0.0.pom" to managedPom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val managed = resolved.deps.first { it.groupArtifact == "com.example:managed" }
        assertEquals("3.0.0", managed.version)
    }

    @Test
    fun directDepsWinOverTransitiveVersionConflict() {
        // Direct: C:1.0.0. Transitive via A: C:2.0.0. Direct should win.
        val config = testConfig().copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:c" to "1.0.0"
            )
        )
        val aPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>a</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>c</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val c1Pom = """
            <project><groupId>com.example</groupId><artifactId>c</artifactId><version>1.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
                "/cache/com/example/c/1.0.0/c-1.0.0.jar" to "hashC"
            ),
            pomContents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
                "/cache/com/example/c/1.0.0/c-1.0.0.pom" to c1Pom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val c = resolved.deps.first { it.groupArtifact == "com.example:c" }
        assertEquals("1.0.0", c.version)
        assertFalse(c.transitive)
    }

    @Test
    fun multiLevelTransitivity() {
        // A -> B -> C (two levels deep)
        val config = testConfig().copy(
            dependencies = mapOf("com.example:a" to "1.0.0")
        )
        val aPom = """
            <project>
                <groupId>com.example</groupId><artifactId>a</artifactId><version>1.0.0</version>
                <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>b</artifactId><version>1.0.0</version></dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val bPom = """
            <project>
                <groupId>com.example</groupId><artifactId>b</artifactId><version>1.0.0</version>
                <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>c</artifactId><version>1.0.0</version></dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val cPom = """
            <project><groupId>com.example</groupId><artifactId>c</artifactId><version>1.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
                "/cache/com/example/b/1.0.0/b-1.0.0.jar" to "hashB",
                "/cache/com/example/c/1.0.0/c-1.0.0.jar" to "hashC"
            ),
            pomContents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
                "/cache/com/example/b/1.0.0/b-1.0.0.pom" to bPom,
                "/cache/com/example/c/1.0.0/c-1.0.0.pom" to cPom
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(3, resolved.deps.size)
        val names = resolved.deps.map { it.groupArtifact }.toSet()
        assertTrue("com.example:a" in names)
        assertTrue("com.example:b" in names)
        assertTrue("com.example:c" in names)
    }

    @Test
    fun pomLookupCachesSharedParentPom() {
        // child-a and child-b share the same parent POM.
        // The parent POM should be read only once.
        val config = testConfig().copy(
            dependencies = mapOf(
                "com.example:child-a" to "1.0.0",
                "com.example:child-b" to "1.0.0"
            )
        )
        val childAPom = """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>child-a</artifactId>
                <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>util</artifactId></dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val childBPom = """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>child-b</artifactId>
                <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>util</artifactId></dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val parentPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency><groupId>com.example</groupId><artifactId>util</artifactId><version>2.0.0</version></dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()
        val utilPom = """
            <project><groupId>com.example</groupId><artifactId>util</artifactId><version>2.0.0</version></project>
        """.trimIndent()

        val readCounts = mutableMapOf<String, Int>()
        val deps = countingDeps(
            sha256Results = mapOf(
                "/cache/com/example/child-a/1.0.0/child-a-1.0.0.jar" to "hashA",
                "/cache/com/example/child-b/1.0.0/child-b-1.0.0.jar" to "hashB",
                "/cache/com/example/util/2.0.0/util-2.0.0.jar" to "hashU"
            ),
            pomContents = mapOf(
                "/cache/com/example/child-a/1.0.0/child-a-1.0.0.pom" to childAPom,
                "/cache/com/example/child-b/1.0.0/child-b-1.0.0.pom" to childBPom,
                "/cache/com/example/parent/1.0.0/parent-1.0.0.pom" to parentPom,
                "/cache/com/example/util/2.0.0/util-2.0.0.pom" to utilPom
            ),
            readCounts = readCounts
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        assertNotNull(result.get())
        val parentReads = readCounts["/cache/com/example/parent/1.0.0/parent-1.0.0.pom"] ?: 0
        assertEquals(1, parentReads, "Shared parent POM should be read exactly once")
    }

    // Helper: creates a fake ResolverDeps with POM content support
    private fun fakeTransitiveDeps(
        cachedFiles: MutableSet<String> = mutableSetOf(),
        sha256Results: Map<String, String> = emptyMap(),
        pomContents: Map<String, String> = emptyMap()
    ): ResolverDeps {
        return object : ResolverDeps {
            override fun fileExists(path: String): Boolean = path in cachedFiles

            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                cachedFiles.add(destPath)
                return Ok(Unit)
            }

            override fun computeSha256(filePath: String): Result<String, Sha256Error> {
                val hash = sha256Results[filePath]
                    ?: return Err(Sha256Error(filePath))
                return Ok(hash)
            }

            override fun readFileContent(path: String): Result<String, OpenFailed> {
                val content = pomContents[path]
                    ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }
    }

    // Helper: like fakeTransitiveDeps but tracks readFileContent call counts
    private fun countingDeps(
        cachedFiles: MutableSet<String> = mutableSetOf(),
        sha256Results: Map<String, String> = emptyMap(),
        pomContents: Map<String, String> = emptyMap(),
        readCounts: MutableMap<String, Int>
    ): ResolverDeps {
        return object : ResolverDeps {
            override fun fileExists(path: String): Boolean = path in cachedFiles

            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                cachedFiles.add(destPath)
                return Ok(Unit)
            }

            override fun computeSha256(filePath: String): Result<String, Sha256Error> {
                val hash = sha256Results[filePath]
                    ?: return Err(Sha256Error(filePath))
                return Ok(hash)
            }

            override fun readFileContent(path: String): Result<String, OpenFailed> {
                readCounts[path] = (readCounts[path] ?: 0) + 1
                val content = pomContents[path]
                    ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }
    }
}
