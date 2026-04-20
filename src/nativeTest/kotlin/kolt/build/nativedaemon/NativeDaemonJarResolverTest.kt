package kolt.build.nativedaemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeDaemonJarResolverTest {

    private fun exists(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    @Test
    fun envOverrideWinsWithoutExistenceCheck() {
        val result = resolveNativeDaemonJarPure(
            envValue = "/nowhere/custom-native.jar",
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = { false },
        )
        val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
        assertEquals("/nowhere/custom-native.jar", resolved.path)
        assertEquals(NativeDaemonJarResolution.Source.Env, resolved.source)
    }

    @Test
    fun emptyEnvFallsThroughToLibexec() {
        val libexec = "/opt/kolt/libexec/kolt-native-daemon-all.jar"
        val result = resolveNativeDaemonJarPure(
            envValue = "",
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = exists(libexec),
        )
        val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
        assertEquals(NativeDaemonJarResolution.Source.Libexec, resolved.source)
    }

    @Test
    fun libexecLayoutIsPickedFromInstalledPrefix() {
        val libexec = "/usr/local/libexec/kolt-native-daemon-all.jar"
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = "/usr/local/bin/kolt",
            fileExists = exists(libexec),
        )
        val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
    }

    @Test
    fun devFallbackResolvesFromBuildBinLinuxX64() {
        val kolt = "/home/alice/src/kolt/build/bin/linuxX64/debugExecutable/kolt.kexe"
        val devJar = "/home/alice/src/kolt/kolt-native-daemon/build/libs/kolt-native-daemon-all.jar"
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = kolt,
            fileExists = exists(devJar),
        )
        val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
        assertEquals(devJar, resolved.path)
        assertEquals(NativeDaemonJarResolution.Source.DevFallback, resolved.source)
    }

    @Test
    fun libexecWinsOverDevFallbackWhenBothExist() {
        val libexec = "/opt/kolt/libexec/kolt-native-daemon-all.jar"
        val devJar = "/kolt-native-daemon/build/libs/kolt-native-daemon-all.jar"
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = exists(libexec, devJar),
        )
        val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
        assertEquals(libexec, resolved.path)
    }

    @Test
    fun noSelfExeAndNoEnvReturnsNotFound() {
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = null,
            fileExists = { true },
        )
        assertEquals(NativeDaemonJarResolution.NotFound, result)
    }

    @Test
    fun selfExePresentButNoFilesExistReturnsNotFound() {
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = { false },
        )
        assertEquals(NativeDaemonJarResolution.NotFound, result)
    }

    @Test
    fun jvmDaemonJarIsNotPickedUpAsNativeDaemonJar() {
        // Both daemons ship their jars under `libexec/`. If the native
        // resolver ever accidentally probed the JVM jar filename, it would
        // spawn a JVM daemon over --konanc-jar/--konan-home and explode at
        // parse time. This test pins the filename strictness.
        val jvmJar = "/opt/kolt/libexec/kolt-compiler-daemon-all.jar"
        val result = resolveNativeDaemonJarPure(
            envValue = null,
            selfExePath = "/opt/kolt/bin/kolt",
            fileExists = exists(jvmJar),
        )
        assertEquals(NativeDaemonJarResolution.NotFound, result)
    }
}
