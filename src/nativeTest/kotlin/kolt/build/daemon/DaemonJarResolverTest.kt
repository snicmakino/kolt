package kolt.build.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DaemonJarResolverTest {

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
    val envJar = "/nowhere/custom.jar"
    val result =
      resolveDaemonJarPure(
        envValue = envJar,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = { false },
        readManifest =
          manifests("/nowhere/custom-runtime.classpath" to listOf("/cache/a.jar", "/cache/b.jar")),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$envJar:/cache/a.jar:/cache/b.jar", DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(DaemonJarResolution.Source.Env, resolved.source)
  }

  @Test
  fun envWithoutSiblingManifestFallsThroughToNotFound() {
    val result =
      resolveDaemonJarPure(
        envValue = "/nowhere/custom.jar",
        selfExePath = null,
        fileExists = { false },
        readManifest = { null },
      )
    assertEquals(DaemonJarResolution.NotFound, result)
  }

  @Test
  fun emptyEnvFallsThroughToLibexecArgfile() {
    val argfile = "/opt/kolt/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = "",
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
    assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
  }

  @Test
  fun libexecArgfileResolvesFromInstalledPrefix() {
    val argfile = "/usr/local/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "/usr/local/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
    assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
  }

  @Test
  fun devFallbackBuildsClasspathFromKoltBuildManifest() {
    val repo = "/home/alice/src/kolt"
    val kolt = "$repo/build/bin/linuxX64/debugExecutable/kolt.kexe"
    val devJar = "$repo/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM.jar"
    val manifestPath = "$repo/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM-runtime.classpath"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = kolt,
        fileExists = exists(devJar),
        readManifest = manifests(manifestPath to listOf("/cache/dep1.jar", "/cache/dep2.jar", "")),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$devJar:/cache/dep1.jar:/cache/dep2.jar", DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(DaemonJarResolution.Source.DevFallback, resolved.source)
  }

  @Test
  fun libexecArgfileWinsOverDevFallbackWhenBothExist() {
    val argfile = "/opt/kolt/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val devJar = "/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM.jar"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = exists(argfile, devJar),
        readManifest =
          manifests("/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM-runtime.classpath" to emptyList()),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("@$argfile"), resolved.launchArgs)
  }

  @Test
  fun devFallbackWithoutManifestFallsThroughToNotFound() {
    val repo = "/home/alice/src/kolt"
    val kolt = "$repo/build/bin/linuxX64/debugExecutable/kolt.kexe"
    val devJar = "$repo/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM.jar"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = kolt,
        fileExists = exists(devJar),
        readManifest = { null },
      )
    assertEquals(DaemonJarResolution.NotFound, result)
  }

  @Test
  fun noSelfExeAndNoEnvReturnsNotFound() {
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = null,
        fileExists = { true },
        readManifest = { null },
      )
    assertEquals(DaemonJarResolution.NotFound, result)
  }

  @Test
  fun selfExePresentButNoFilesExistReturnsNotFound() {
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "/opt/kolt/bin/kolt",
        fileExists = { false },
        readManifest = { null },
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
