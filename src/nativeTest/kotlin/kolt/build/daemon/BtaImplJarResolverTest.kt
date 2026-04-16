package kolt.build.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BtaImplJarResolverTest {

    @Test
    fun envOverrideWinsWhenDirectoryHasJars() {
        val fakeJars = listOf("/fake/env/dir/a.jar", "/fake/env/dir/b.jar")
        val result = resolveBtaImplJarsPure(
            envDirValue = "/fake/env/dir",
            selfExePath = "/opt/kolt/bin/kolt",
            listJarFiles = { dir ->
                if (dir == "/fake/env/dir") fakeJars else error("must not probe other paths when env override is set")
            },
        )
        val resolved = assertIs<BtaImplJarsResolution.Resolved>(result)
        assertEquals(fakeJars, resolved.jars)
        assertEquals(BtaImplJarsResolution.Source.Env, resolved.source)
    }

    @Test
    fun envOverrideEmptyDirectorySurfacesAsNotFound() {
        val result = resolveBtaImplJarsPure(
            envDirValue = "/fake/empty",
            selfExePath = "/opt/kolt/bin/kolt",
            listJarFiles = { dir -> if (dir == "/fake/empty") emptyList() else error("must not probe fallback when env override is set") },
        )
        val notFound = assertIs<BtaImplJarsResolution.NotFound>(result)
        assertEquals("/fake/empty", notFound.probedDir)
    }

    @Test
    fun libexecLayoutResolvesRelativeToSelfExe() {
        val fakeJars = listOf("/opt/kolt/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar")
        val result = resolveBtaImplJarsPure(
            envDirValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            listJarFiles = { dir ->
                if (dir == "/opt/kolt/libexec/kolt-bta-impl") fakeJars else null
            },
        )
        val resolved = assertIs<BtaImplJarsResolution.Resolved>(result)
        assertEquals(fakeJars, resolved.jars)
        assertEquals(BtaImplJarsResolution.Source.Libexec, resolved.source)
    }

    @Test
    fun devFallbackResolvesFiveParentsUpFromTestBinary() {
        val fakeJars = listOf("/repo/kolt-compiler-daemon/build/bta-impl-jars/a.jar")
        val result = resolveBtaImplJarsPure(
            envDirValue = null,
            selfExePath = "/repo/build/bin/linuxX64/debugTest/test.kexe",
            listJarFiles = { dir ->
                when (dir) {
                    "/repo/build/bin/linuxX64/debugTest/libexec/kolt-bta-impl" -> null
                    "/repo/kolt-compiler-daemon/build/bta-impl-jars" -> fakeJars
                    else -> null
                }
            },
        )
        val resolved = assertIs<BtaImplJarsResolution.Resolved>(result)
        assertEquals(fakeJars, resolved.jars)
        assertEquals(BtaImplJarsResolution.Source.DevFallback, resolved.source)
    }

    @Test
    fun nothingResolvedYieldsNotFoundWithLastProbedPath() {
        val result = resolveBtaImplJarsPure(
            envDirValue = null,
            selfExePath = "/repo/build/bin/linuxX64/debugTest/test.kexe",
            listJarFiles = { null },
        )
        val notFound = assertIs<BtaImplJarsResolution.NotFound>(result)
        assertEquals("/repo/kolt-compiler-daemon/build/bta-impl-jars", notFound.probedDir)
    }

    @Test
    fun noSelfExeSurfacesAsNotFoundWithSentinel() {
        val result = resolveBtaImplJarsPure(
            envDirValue = null,
            selfExePath = null,
            listJarFiles = { error("must not probe without a selfExe") },
        )
        val notFound = assertIs<BtaImplJarsResolution.NotFound>(result)
        assertEquals("<no selfExe>", notFound.probedDir)
    }
}
