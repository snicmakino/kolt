package kolt.build.nativedaemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeDaemonJarResolverTest {

  private fun exists(vararg paths: String): (String) -> Boolean {
    val set = paths.toSet()
    return { it in set }
  }

  private fun manifests(vararg entries: Pair<String, List<String>>): (String) -> List<String>? {
    val map = entries.toMap()
    return { map[it] }
  }

  @Test
  fun envOverrideReadsSiblingManifestAndEmitsClasspathLaunch() {
    val envJar = "/nowhere/custom-native.jar"
    val result =
      resolveNativeDaemonJarPure(
        envValue = envJar,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = { false },
        readManifest =
          manifests(
            "/nowhere/custom-native-runtime.classpath" to listOf("/cache/a.jar", "/cache/b.jar")
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$envJar:/cache/a.jar:/cache/b.jar", NATIVE_DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(NativeDaemonJarResolution.Source.Env, resolved.source)
  }

  @Test
  fun emptyEnvFallsThroughToLibexecArgfile() {
    val argfile = "/opt/kolt/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = "",
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
    assertEquals(NativeDaemonJarResolution.Source.Libexec, resolved.source)
  }

  @Test
  fun libexecArgfileIsPickedFromInstalledPrefix() {
    val argfile = "/usr/local/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "/usr/local/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
  }

  @Test
  fun devFallbackBuildsClasspathFromKoltBuildManifest() {
    val repo = "/home/alice/src/kolt"
    val kolt = "$repo/build/bin/linuxX64/debugExecutable/kolt.kexe"
    val devJar = "$repo/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM.jar"
    val manifestPath =
      "$repo/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM-runtime.classpath"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = kolt,
        fileExists = exists(devJar),
        readManifest = manifests(manifestPath to listOf("/cache/dep.jar")),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$devJar:/cache/dep.jar", NATIVE_DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(NativeDaemonJarResolution.Source.DevFallback, resolved.source)
  }

  @Test
  fun libexecWinsOverDevFallbackWhenBothExist() {
    val argfile = "/opt/kolt/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val devJar = "/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM.jar"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = exists(argfile, devJar),
        readManifest =
          manifests(
            "/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM-runtime.classpath" to
              emptyList()
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
  }

  @Test
  fun noSelfExeAndNoEnvReturnsNotFound() {
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = null,
        fileExists = { true },
        readManifest = { null },
      )
    assertEquals(NativeDaemonJarResolution.NotFound, result)
  }

  @Test
  fun selfExePresentButNoFilesExistReturnsNotFound() {
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = { false },
        readManifest = { null },
      )
    assertEquals(NativeDaemonJarResolution.NotFound, result)
  }

  @Test
  fun jvmDaemonArgfileIsNotPickedUpAsNativeDaemonLaunch() {
    // Both daemons ship their argfiles under `libexec/classpath/`. If the
    // native resolver ever probed the JVM daemon's argfile name, it would
    // launch a JVM daemon over --konanc-jar/--konan-home and explode at
    // parse time. This test pins the filename strictness.
    val jvmArgfile = "/opt/kolt/libexec/classpath/kolt-jvm-compiler-daemon.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = exists(jvmArgfile),
        readManifest = { null },
      )
    assertEquals(NativeDaemonJarResolution.NotFound, result)
  }
}
