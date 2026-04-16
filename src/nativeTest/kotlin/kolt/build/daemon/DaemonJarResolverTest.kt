package kolt.build.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DaemonJarResolverTest {

    private fun exists(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    @Test
    fun envOverrideWinsWithoutExistenceCheck() {
        val result = resolveDaemonJarPure(
            envValue = "/nowhere/custom.jar",
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = { false },
        )
        val resolved = assertIs<DaemonJarResolution.Resolved>(result)
        assertEquals("/nowhere/custom.jar", resolved.path)
        assertEquals(DaemonJarResolution.Source.Env, resolved.source)
    }

    @Test
    fun emptyEnvFallsThroughToNextCandidate() {
        val libexec = "/opt/kolt/libexec/kolt-compiler-daemon-all.jar"
        val result = resolveDaemonJarPure(
            envValue = "",
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = exists(libexec),
        )
        val resolved = assertIs<DaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
        assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
    }

    @Test
    fun libexecLayoutIsPickedFromInstalledPrefix() {
        val libexec = "/usr/local/libexec/kolt-compiler-daemon-all.jar"
        val result = resolveDaemonJarPure(
            envValue = null,
            selfExePath = "/usr/local/bin/kolt",
            fileExists = exists(libexec),
        )
        val resolved = assertIs<DaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
        assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
    }

    @Test
    fun devFallbackResolvesFromBinaryInsideBuildBinLinuxX64() {
        val kolt = "/home/alice/src/kolt/build/bin/linuxX64/debugExecutable/kolt.kexe"
        val devJar = "/home/alice/src/kolt/kolt-compiler-daemon/build/libs/kolt-compiler-daemon-all.jar"
        val result = resolveDaemonJarPure(
            envValue = null,
            selfExePath = kolt,
            fileExists = exists(devJar),
        )
        val resolved = assertIs<DaemonJarResolution.Resolved>(result)
        assertEquals(devJar, resolved.path)
        assertEquals(DaemonJarResolution.Source.DevFallback, resolved.source)
    }

    @Test
    fun libexecWinsOverDevFallbackWhenBothExist() {
        val libexec = "/opt/kolt/libexec/kolt-compiler-daemon-all.jar"
        val devJar = "/kolt-compiler-daemon/build/libs/kolt-compiler-daemon-all.jar"
        val result = resolveDaemonJarPure(
            envValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = exists(libexec, devJar),
        )
        val resolved = assertIs<DaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
    }

    @Test
    fun noSelfExeAndNoEnvReturnsNotFound() {
        val result = resolveDaemonJarPure(
            envValue = null,
            selfExePath = null,
            fileExists = { true },
        )
        assertEquals(DaemonJarResolution.NotFound, result)
    }

    @Test
    fun selfExePresentButNoFilesExistReturnsNotFound() {
        val result = resolveDaemonJarPure(
            envValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = { false },
        )
        assertEquals(DaemonJarResolution.NotFound, result)
    }

    @Test
    fun parentDirHandlesEdgeCases() {
        assertEquals("/opt/kolt", parentDir("/opt/kolt/bin"))
        assertEquals("/", parentDir("/bin"))
        assertEquals(null, parentDir("/"))
        assertEquals(null, parentDir(""))
        assertEquals(null, parentDir("kolt"))
    }
}
