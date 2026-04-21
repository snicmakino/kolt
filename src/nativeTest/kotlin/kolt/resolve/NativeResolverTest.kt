package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeResolverTest {

    @Test
    fun resolvesDirectDepWithNoTransitives() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val rootModule = rootModuleJson(
            redirectGroup = "com.example",
            redirectModule = "lib-linuxx64",
            redirectVersion = "1.0.0"
        )
        val platformModule = platformModuleJson(
            klibFileName = "lib-linuxx64-1.0.0.klib",
            klibSha256 = "hash1",
            dependencies = emptyList()
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to rootModule,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to platformModule
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "hash1"
            )
        )

        val result = resolveNative(config, "/cache", deps)

        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
        assertEquals("1.0.0", resolved.deps[0].version)
        assertEquals("hash1", resolved.deps[0].sha256)
        assertEquals(
            "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib",
            resolved.deps[0].cachePath
        )
        assertFalse(resolved.deps[0].transitive)
    }

    @Test
    fun resolvesSingleTransitiveDep() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val libPlatform = platformModuleJson(
            klibFileName = "lib-linuxx64-1.0.0.klib",
            klibSha256 = "h-lib",
            dependencies = listOf(
                NativeDependency("com.example", "trans", "2.0.0")
            )
        )
        val transRoot = rootModuleJson("com.example", "trans-linuxx64", "2.0.0")
        val transPlatform = platformModuleJson(
            klibFileName = "trans-linuxx64-2.0.0.klib",
            klibSha256 = "h-trans",
            dependencies = emptyList()
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform,
                "/cache/com/example/trans/2.0.0/trans-2.0.0.module" to transRoot,
                "/cache/com/example/trans-linuxx64/2.0.0/trans-linuxx64-2.0.0.module" to transPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h-lib",
                "/cache/com/example/trans-linuxx64/2.0.0/trans-linuxx64-2.0.0.klib" to "h-trans"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(2, resolved.deps.size)

        val direct = resolved.deps.first { it.groupArtifact == "com.example:lib" }
        assertFalse(direct.transitive)

        val transitive = resolved.deps.first { it.groupArtifact == "com.example:trans" }
        assertTrue(transitive.transitive)
        assertEquals("2.0.0", transitive.version)
    }

    @Test
    fun skipsKotlinStdlibFromTransitives() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val libPlatform = platformModuleJson(
            klibFileName = "lib-linuxx64-1.0.0.klib",
            klibSha256 = "h",
            dependencies = listOf(
                NativeDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0")
            )
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
    }

    @Test
    fun skipsKotlinStdlibCommonFromTransitives() {
        // kotlin-stdlib-common has no Gradle module metadata (pre-GMM artifact).
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val libPlatform = platformModuleJson(
            klibFileName = "lib-linuxx64-1.0.0.klib",
            klibSha256 = "h",
            dependencies = listOf(
                NativeDependency("org.jetbrains.kotlin", "kotlin-stdlib-common", "1.8.21")
            )
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
    }

    @Test
    fun skipsKotlinStdlibCommonAsDirectDependencyToo() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:lib" to "1.0.0",
                "org.jetbrains.kotlin:kotlin-stdlib-common" to "1.8.21"
            )
        )

        val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val libPlatform = platformModuleJson("lib-linuxx64-1.0.0.klib", "h", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
    }

    @Test
    fun skipsKotlinStdlibAsDirectDependencyToo() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:lib" to "1.0.0",
                "org.jetbrains.kotlin:kotlin-stdlib" to "2.0.0"
            )
        )

        val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val libPlatform = platformModuleJson("lib-linuxx64-1.0.0.klib", "h", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
    }

    @Test
    fun returnsEmptyWhenNoDependencies() {
        val config = testConfig(target = "linuxX64").copy(dependencies = emptyMap())
        val deps = fakeDeps()

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(0, resolved.deps.size)
        assertFalse(resolved.lockChanged)
    }

    @Test
    fun failsWhenNoNativeVariantAvailable() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:jvmonly" to "1.0.0")
        )

        val jvmOnlyModule = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "jvmApiElements-published",
              "attributes": {
                "org.jetbrains.kotlin.platform.type": "jvm"
              },
              "available-at": {
                "url": "../../jvmonly-jvm/1.0.0/jvmonly-jvm-1.0.0.module",
                "group": "com.example",
                "module": "jvmonly-jvm",
                "version": "1.0.0"
              }
            }
          ]
        }
        """.trimIndent()

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/jvmonly/1.0.0/jvmonly-1.0.0.module" to jvmOnlyModule
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.NoNativeVariant>(result.getError())
        assertEquals("com.example:jvmonly", error.groupArtifact)
        assertEquals("linux_x64", error.nativeTarget)
    }

    @Test
    fun failsOnSha256Mismatch() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val root = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val platform = platformModuleJson("lib-linuxx64-1.0.0.klib", "expected-hash", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to root,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to platform
            ),
            sha256 = mapOf(
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "actual-hash"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.Sha256Mismatch>(result.getError())
        assertEquals("com.example:lib", error.groupArtifact)
        assertEquals("expected-hash", error.expected)
        assertEquals("actual-hash", error.actual)
    }

    @Test
    fun diamondDependencyHighestVersionWins() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0"))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val sharedRoot20 = rootModuleJson("com.example", "shared-linuxx64", "2.0.0")
        val sharedPlatform20 = platformModuleJson("shared-linuxx64-2.0.0.klib", "h-s20", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/2.0.0/shared-2.0.0.module" to sharedRoot20,
                "/cache/com/example/shared-linuxx64/2.0.0/shared-linuxx64-2.0.0.module" to sharedPlatform20
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/2.0.0/shared-linuxx64-2.0.0.klib" to "h-s20"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(3, resolved.deps.size)

        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("2.0.0", shared.version)
        assertTrue(shared.transitive)
    }

    @Test
    fun dropsSupersededTransitiveChildren() {
        // a -> lib:1.0 -> old-helper:1.0
        // b -> c -> lib:2.0 -> new-helper:1.0
        // After lib is upgraded to 2.0, old-helper (pulled only by lib:1.0) must be dropped.
        //
        // BFS order matters: {a, b} dequeues lib:1.0 before lib:2.0 (via c), so
        // old-helper is enqueued before the stale-version guard can kick in.
        // The reverse direct-dep order would let the guard skip lib:1.0 and
        // mask the bug. If this test ever stops failing on main after an
        // unrelated refactor, check whether queue ordering still exercises
        // the dequeue-before-upgrade path.
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "lib", "1.0.0"))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "c", "1.0.0"))
        )
        val cRoot = rootModuleJson("com.example", "c-linuxx64", "1.0.0")
        val cPlatform = platformModuleJson(
            "c-linuxx64-1.0.0.klib", "h-c",
            listOf(NativeDependency("com.example", "lib", "2.0.0"))
        )
        val lib10Root = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
        val lib10Platform = platformModuleJson(
            "lib-linuxx64-1.0.0.klib", "h-lib10",
            listOf(NativeDependency("com.example", "old-helper", "1.0.0"))
        )
        val lib20Root = rootModuleJson("com.example", "lib-linuxx64", "2.0.0")
        val lib20Platform = platformModuleJson(
            "lib-linuxx64-2.0.0.klib", "h-lib20",
            listOf(NativeDependency("com.example", "new-helper", "1.0.0"))
        )
        val oldHelperRoot = rootModuleJson("com.example", "old-helper-linuxx64", "1.0.0")
        val oldHelperPlatform = platformModuleJson("old-helper-linuxx64-1.0.0.klib", "h-old", emptyList())
        val newHelperRoot = rootModuleJson("com.example", "new-helper-linuxx64", "1.0.0")
        val newHelperPlatform = platformModuleJson("new-helper-linuxx64-1.0.0.klib", "h-new", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/c/1.0.0/c-1.0.0.module" to cRoot,
                "/cache/com/example/c-linuxx64/1.0.0/c-linuxx64-1.0.0.module" to cPlatform,
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to lib10Root,
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to lib10Platform,
                "/cache/com/example/lib/2.0.0/lib-2.0.0.module" to lib20Root,
                "/cache/com/example/lib-linuxx64/2.0.0/lib-linuxx64-2.0.0.module" to lib20Platform,
                "/cache/com/example/old-helper/1.0.0/old-helper-1.0.0.module" to oldHelperRoot,
                "/cache/com/example/old-helper-linuxx64/1.0.0/old-helper-linuxx64-1.0.0.module" to oldHelperPlatform,
                "/cache/com/example/new-helper/1.0.0/new-helper-1.0.0.module" to newHelperRoot,
                "/cache/com/example/new-helper-linuxx64/1.0.0/new-helper-linuxx64-1.0.0.module" to newHelperPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/c-linuxx64/1.0.0/c-linuxx64-1.0.0.klib" to "h-c",
                "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h-lib10",
                "/cache/com/example/lib-linuxx64/2.0.0/lib-linuxx64-2.0.0.klib" to "h-lib20",
                "/cache/com/example/old-helper-linuxx64/1.0.0/old-helper-linuxx64-1.0.0.klib" to "h-old",
                "/cache/com/example/new-helper-linuxx64/1.0.0/new-helper-linuxx64-1.0.0.klib" to "h-new"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val names = resolved.deps.map { it.groupArtifact }.toSet()
        val lib = resolved.deps.first { it.groupArtifact == "com.example:lib" }
        assertEquals("2.0.0", lib.version)
        assertTrue("com.example:new-helper" in names)
        assertFalse("com.example:old-helper" in names)
    }

    @Test
    fun directDepVersionWinsOverTransitive() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:shared" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
        assertFalse(shared.transitive)
    }

    @Test
    fun rejectsExactVersionBlocksTransitiveUpgrade() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0", rejects = listOf("2.0.0")))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
    }

    @Test
    fun rejectsIntervalBlocksTransitiveUpgrade() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0", rejects = listOf("[2.0.0,)")))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.5.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
    }

    @Test
    fun rejectsFromLaterContributorRevokesAlreadyAcceptedVersion() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "1.0.0", rejects = listOf("2.0.0")))
        )
        val sharedRoot20 = rootModuleJson("com.example", "shared-linuxx64", "2.0.0")
        val sharedPlatform20 = platformModuleJson("shared-linuxx64-2.0.0.klib", "h-s20", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/2.0.0/shared-2.0.0.module" to sharedRoot20,
                "/cache/com/example/shared-linuxx64/2.0.0/shared-linuxx64-2.0.0.module" to sharedPlatform20
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/2.0.0/shared-linuxx64-2.0.0.klib" to "h-s20"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.RejectedVersionResolved>(result.getError())
        assertEquals("com.example:shared", error.groupArtifact)
        assertEquals("2.0.0", error.version)
        assertEquals("2.0.0", error.rejectPattern)
    }

    @Test
    fun rejectsMatchingDirectDepVersionFailsFast() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:shared" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val sharedRoot = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.0.0", rejects = listOf("1.0.0")))
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform
            ),
            sha256 = mapOf(
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.RejectedVersionResolved>(result.getError())
        assertEquals("com.example:shared", error.groupArtifact)
        assertEquals("1.0.0", error.version)
    }

    @Test
    fun strictlyPinSelectsLowerVersionOverTransitiveHighestWins() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0", strict = true))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "1.0.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
    }

    @Test
    fun strictlyConflictWithHigherTransitiveProposalFailsFast() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0", strict = true))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.StrictVersionConflict>(result.getError())
        assertEquals("com.example:shared", error.groupArtifact)
        assertEquals("1.0.0", error.strictVersion)
        assertEquals("2.0.0", error.otherVersion)
        assertFalse(error.otherIsStrict)
    }

    @Test
    fun conflictingStrictPinsFromDifferentTransitivesFailFast() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0", strict = true))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "1.5.0", strict = true))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.StrictVersionConflict>(result.getError())
        assertEquals("com.example:shared", error.groupArtifact)
        assertTrue(error.otherIsStrict)
        assertTrue(formatResolveError(error).contains("conflicting strict versions"))
    }

    @Test
    fun depWithBothStrictlyAndRejectsHonorsBothConstraints() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(
                NativeDependency(
                    "com.example", "shared", "1.0.0",
                    strict = true, rejects = listOf("2.0.0")
                )
            )
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "2.0.0"))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
    }

    @Test
    fun strictPinAgreeingWithEarlierTransitiveResolvesCleanly() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf(
                "com.example:a" to "1.0.0",
                "com.example:b" to "1.0.0"
            )
        )

        val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
        val aPlatform = platformModuleJson(
            "a-linuxx64-1.0.0.klib", "h-a",
            listOf(NativeDependency("com.example", "shared", "1.0.0"))
        )
        val bRoot = rootModuleJson("com.example", "b-linuxx64", "1.0.0")
        val bPlatform = platformModuleJson(
            "b-linuxx64-1.0.0.klib", "h-b",
            listOf(NativeDependency("com.example", "shared", "1.0.0", strict = true))
        )
        val sharedRoot10 = rootModuleJson("com.example", "shared-linuxx64", "1.0.0")
        val sharedPlatform10 = platformModuleJson("shared-linuxx64-1.0.0.klib", "h-s10", emptyList())

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
                "/cache/com/example/b/1.0.0/b-1.0.0.module" to bRoot,
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.module" to bPlatform,
                "/cache/com/example/shared/1.0.0/shared-1.0.0.module" to sharedRoot10,
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.module" to sharedPlatform10
            ),
            sha256 = mapOf(
                "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
                "/cache/com/example/b-linuxx64/1.0.0/b-linuxx64-1.0.0.klib" to "h-b",
                "/cache/com/example/shared-linuxx64/1.0.0/shared-linuxx64-1.0.0.klib" to "h-s10"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val resolved = assertNotNull(result.get())
        val shared = resolved.deps.first { it.groupArtifact == "com.example:shared" }
        assertEquals("1.0.0", shared.version)
    }

    @Test
    fun failsOnInvalidCoordinate() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("invalid-no-colon" to "1.0.0")
        )
        val deps = fakeDeps()

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.InvalidDependency>(result.getError())
        assertEquals("invalid-no-colon", error.input)
    }

    @Test
    fun failsOnInvalidJsonWithMetadataParseFailed() {
        val config = testConfig(target = "linuxX64").copy(
            dependencies = mapOf("com.example:lib" to "1.0.0")
        )

        val deps = fakeDeps(
            contents = mapOf(
                "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to "not json"
            )
        )

        val result = resolveNative(config, "/cache", deps)
        val error = assertIs<ResolveError.MetadataParseFailed>(result.getError())
        assertEquals("com.example:lib", error.groupArtifact)
    }

    private fun rootModuleJson(
        redirectGroup: String,
        redirectModule: String,
        redirectVersion: String
    ): String = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../$redirectModule/$redirectVersion/$redirectModule-$redirectVersion.module",
                "group": "$redirectGroup",
                "module": "$redirectModule",
                "version": "$redirectVersion"
              }
            }
          ]
        }
    """.trimIndent()

    private fun platformModuleJson(
        klibFileName: String,
        klibSha256: String,
        dependencies: List<NativeDependency>
    ): String {
        val depsJson = dependencies.joinToString(",\n") { d ->
            val versionParts = buildList {
                if (d.strict) add(""""strictly": "${d.version}"""")
                else add(""""requires": "${d.version}"""")
                if (d.rejects.isNotEmpty()) {
                    val list = d.rejects.joinToString(",") { "\"$it\"" }
                    add(""""rejects": [$list]""")
                }
            }
            """
              {
                "group": "${d.group}",
                "module": "${d.module}",
                "version": { ${versionParts.joinToString(", ")} }
              }
            """.trimIndent()
        }
        return """
            {
              "formatVersion": "1.1",
              "variants": [
                {
                  "name": "linuxX64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "linux_x64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "dependencies": [$depsJson],
                  "files": [
                    {
                      "name": "inner.klib",
                      "url": "$klibFileName",
                      "sha256": "$klibSha256"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun fakeDeps(
        cachedFiles: MutableSet<String> = mutableSetOf(),
        contents: Map<String, String> = emptyMap(),
        sha256: Map<String, String> = emptyMap()
    ): ResolverDeps {
        cachedFiles.addAll(contents.keys)
        return object : ResolverDeps {
            override fun fileExists(path: String): Boolean = path in cachedFiles

            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                cachedFiles.add(destPath)
                return Ok(Unit)
            }

            override fun computeSha256(filePath: String): Result<String, Sha256Error> {
                val hash = sha256[filePath] ?: return Err(Sha256Error(filePath))
                return Ok(hash)
            }

            override fun readFileContent(path: String): Result<String, OpenFailed> {
                val content = contents[path] ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }
    }
}
