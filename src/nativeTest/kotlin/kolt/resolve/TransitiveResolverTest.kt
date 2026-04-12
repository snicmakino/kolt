package kolt.resolve

import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
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

    @Test
    fun kmpLibraryRedirectsToJvmArtifact() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:kmp-lib" to "1.0.0")
        )

        val moduleJson = """
        {
          "formatVersion": "1.1",
          "component": { "group": "com.example", "module": "kmp-lib", "version": "1.0.0" },
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "jvm" },
              "available-at": {
                "url": "../../kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.module",
                "group": "com.example",
                "module": "kmp-lib-jvm",
                "version": "1.0.0"
              }
            }
          ]
        }
        """.trimIndent()

        val jvmPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>kmp-lib-jvm</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>util</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val utilPom = """
            <project><groupId>com.example</groupId><artifactId>util</artifactId><version>2.0.0</version></project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar" to "hashKmp",
                "/cache/com/example/util/2.0.0/util-2.0.0.jar" to "hashUtil"
            ),
            pomContents = mapOf(
                "/cache/com/example/kmp-lib/1.0.0/kmp-lib-1.0.0.module" to moduleJson,
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.pom" to jvmPom,
                "/cache/com/example/util/2.0.0/util-2.0.0.pom" to utilPom
            )
        )

        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())

        // kmp-lib should use kmp-lib-jvm JAR
        val kmpDep = resolved.deps.first { it.groupArtifact == "com.example:kmp-lib" }
        assertEquals("/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar", kmpDep.cachePath)
        assertFalse(kmpDep.transitive)

        // Transitive dep from kmp-lib-jvm POM should be resolved
        val util = resolved.deps.first { it.groupArtifact == "com.example:util" }
        assertEquals("2.0.0", util.version)
        assertTrue(util.transitive)
    }

    @Test
    fun nonKmpLibraryIgnoresModuleFile() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )
        val libPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()
        // Module file exists but has no JVM redirect (like Guava)
        val moduleJson = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "apiElements",
              "attributes": { "org.gradle.usage": "java-api" },
              "dependencies": []
            }
          ]
        }
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1"
            ),
            pomContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom,
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to moduleJson
            )
        )
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        // Should use original artifact JAR path
        assertEquals("/cache/com/example/lib/1.0.0/lib-1.0.0.jar", resolved.deps[0].cachePath)
    }

    @Test
    fun mixedKmpAndNonKmpDependencies() {
        val config = testConfig().copy(
            dependencies = mapOf(
                "com.example:kmp-lib" to "1.0.0",
                "com.example:plain-lib" to "2.0.0"
            )
        )

        val kmpModuleJson = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "jvm" },
              "available-at": {
                "url": "../../kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.module",
                "group": "com.example",
                "module": "kmp-lib-jvm",
                "version": "1.0.0"
              }
            }
          ]
        }
        """.trimIndent()

        val jvmPom = """
            <project>
                <groupId>com.example</groupId><artifactId>kmp-lib-jvm</artifactId><version>1.0.0</version>
            </project>
        """.trimIndent()

        val plainPom = """
            <project>
                <groupId>com.example</groupId><artifactId>plain-lib</artifactId><version>2.0.0</version>
            </project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            sha256Results = mapOf(
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar" to "hashKmp",
                "/cache/com/example/plain-lib/2.0.0/plain-lib-2.0.0.jar" to "hashPlain"
            ),
            pomContents = mapOf(
                "/cache/com/example/kmp-lib/1.0.0/kmp-lib-1.0.0.module" to kmpModuleJson,
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.pom" to jvmPom,
                "/cache/com/example/plain-lib/2.0.0/plain-lib-2.0.0.pom" to plainPom
            )
        )

        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(2, resolved.deps.size)

        val kmp = resolved.deps.first { it.groupArtifact == "com.example:kmp-lib" }
        assertEquals("/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar", kmp.cachePath)

        val plain = resolved.deps.first { it.groupArtifact == "com.example:plain-lib" }
        assertEquals("/cache/com/example/plain-lib/2.0.0/plain-lib-2.0.0.jar", plain.cachePath)
    }

    @Test
    fun kmpRedirectWithExistingLockfile() {
        val config = testConfig().copy(
            dependencies = mapOf("com.example:kmp-lib" to "1.0.0")
        )
        // Lockfile stores original groupArtifact key with hash of redirected JAR
        val lock = Lockfile(
            version = 2,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:kmp-lib" to LockEntry("1.0.0", "hashKmp")
            )
        )

        val kmpModuleJson = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "jvm" },
              "available-at": {
                "url": "../../kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.module",
                "group": "com.example",
                "module": "kmp-lib-jvm",
                "version": "1.0.0"
              }
            }
          ]
        }
        """.trimIndent()

        val jvmPom = """
            <project>
                <groupId>com.example</groupId><artifactId>kmp-lib-jvm</artifactId><version>1.0.0</version>
            </project>
        """.trimIndent()

        val deps = fakeTransitiveDeps(
            cachedFiles = mutableSetOf(
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar",
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.pom"
            ),
            sha256Results = mapOf(
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.jar" to "hashKmp"
            ),
            pomContents = mapOf(
                "/cache/com/example/kmp-lib/1.0.0/kmp-lib-1.0.0.module" to kmpModuleJson,
                "/cache/com/example/kmp-lib-jvm/1.0.0/kmp-lib-jvm-1.0.0.pom" to jvmPom
            )
        )

        val result = resolveTransitive(config, lock, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertFalse(resolved.lockChanged)
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

    // ---- Multi-repository fallback tests ----

    @Test
    fun downloadFromRepositoriesSucceedsOnFirstRepo() {
        // Given: two repos, first repo returns the artifact
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val downloadedUrls = mutableListOf<String>()

        // When: downloading with two repos
        val result = downloadFromRepositories(
            repos = listOf("https://repo1.example.com", "https://repo2.example.com"),
            destPath = "/cache/lib.jar",
            urlBuilder = { repo -> buildDownloadUrl(coord, repo) },
            download = { url, _ -> downloadedUrls.add(url); Ok(Unit) }
        )

        // Then: succeeds, only contacts first repo
        assertNotNull(result.get())
        assertEquals(1, downloadedUrls.size)
        assertEquals(
            "https://repo1.example.com/com/example/lib/1.0.0/lib-1.0.0.jar",
            downloadedUrls[0]
        )
    }

    @Test
    fun downloadFromRepositoriesFallsBackToSecondRepoOn404() {
        // Given: first repo returns 404, second repo has the artifact
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val downloadedUrls = mutableListOf<String>()
        val repo1Base = "https://repo1.example.com"
        val repo2Base = "https://repo2.example.com"

        // When: downloading with two repos
        val result = downloadFromRepositories(
            repos = listOf(repo1Base, repo2Base),
            destPath = "/cache/lib.jar",
            urlBuilder = { repo -> buildDownloadUrl(coord, repo) },
            download = { url, _ ->
                downloadedUrls.add(url)
                if (url.startsWith(repo1Base)) Err(DownloadError.HttpFailed(url, 404)) else Ok(Unit)
            }
        )

        // Then: succeeds using second repo
        assertNotNull(result.get())
        assertEquals(2, downloadedUrls.size)
        assertEquals("https://repo1.example.com/com/example/lib/1.0.0/lib-1.0.0.jar", downloadedUrls[0])
        assertEquals("https://repo2.example.com/com/example/lib/1.0.0/lib-1.0.0.jar", downloadedUrls[1])
    }

    @Test
    fun downloadFromRepositoriesReturnsErrWhenAllRepos404() {
        // Given: all repos return 404
        val coord = Coordinate("com.example", "lib", "1.0.0")

        // When: all repos 404
        val result = downloadFromRepositories(
            repos = listOf("https://repo1.example.com", "https://repo2.example.com"),
            destPath = "/cache/lib.jar",
            urlBuilder = { repo -> buildDownloadUrl(coord, repo) },
            download = { url, _ -> Err(DownloadError.HttpFailed(url, 404)) }
        )

        // Then: returns the last 404 error
        val error = assertIs<DownloadError.HttpFailed>(result.getError())
        assertEquals(404, error.statusCode)
    }

    @Test
    fun downloadFromRepositoriesStopsOnNon404Error() {
        // Given: first repo returns a non-404 error (network failure)
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val downloadedUrls = mutableListOf<String>()

        // When: first repo has a non-404 error
        val result = downloadFromRepositories(
            repos = listOf("https://repo1.example.com", "https://repo2.example.com"),
            destPath = "/cache/lib.jar",
            urlBuilder = { repo -> buildDownloadUrl(coord, repo) },
            download = { url, _ ->
                downloadedUrls.add(url)
                Err(DownloadError.NetworkError(url, "connection refused"))
            }
        )

        // Then: stops immediately, returns the network error without trying second repo
        assertIs<DownloadError.NetworkError>(result.getError())
        assertEquals(1, downloadedUrls.size)
    }

    @Test
    fun resolveWithCustomRepositoryUrl() {
        // Given: config with a custom repository instead of Maven Central
        val customRepoBase = "https://nexus.example.com/repository/maven-public"
        val config = testConfig(
            dependencies = mapOf("com.example:lib" to "1.0.0"),
            repositories = mapOf("myrepo" to customRepoBase)
        )
        val pomXml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()

        val downloadedUrls = mutableListOf<String>()
        val deps = object : ResolverDeps {
            val cachedFiles = mutableSetOf<String>()
            val fileContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
            )

            override fun fileExists(path: String) = path in cachedFiles
            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)
            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                downloadedUrls.add(url)
                cachedFiles.add(destPath)
                return Ok(Unit)
            }
            override fun computeSha256(filePath: String): Result<String, Sha256Error> = Ok("hash1")
            override fun readFileContent(path: String): Result<String, OpenFailed> {
                val content = fileContents[path] ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }

        // When: resolving dependencies
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())

        // Then: downloaded from custom repo
        assertEquals(1, resolved.deps.size)
        assertTrue(downloadedUrls.any { it.startsWith(customRepoBase) })
    }

    @Test
    fun resolveWithMultipleRepositoriesFallsBackOn404() {
        // Given: config with two repos; jar is only in the second repo
        val repo1Base = "https://repo1.example.com"
        val repo2Base = "https://repo2.example.com"
        val config = testConfig(
            dependencies = mapOf("com.example:lib" to "1.0.0"),
            repositories = mapOf("primary" to repo1Base, "fallback" to repo2Base)
        )
        val pomXml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()

        val cachedFiles = mutableSetOf<String>()
        val deps = object : ResolverDeps {
            val fileContents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
            )

            override fun fileExists(path: String) = path in cachedFiles
            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)
            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                return if (url.startsWith(repo1Base)) {
                    Err(DownloadError.HttpFailed(url, 404))
                } else {
                    cachedFiles.add(destPath)
                    Ok(Unit)
                }
            }
            override fun computeSha256(filePath: String): Result<String, Sha256Error> = Ok("hash1")
            override fun readFileContent(path: String): Result<String, OpenFailed> {
                val content = fileContents[path] ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }

        // When: resolving with first repo returning 404 for jar
        val result = resolveTransitive(config, null, "/cache", deps)
        val resolved = assertNotNull(result.get())

        // Then: resolves successfully using the second repo
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
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
