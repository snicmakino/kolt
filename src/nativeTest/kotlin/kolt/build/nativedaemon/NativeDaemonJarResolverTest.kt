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
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = "",
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(
            argfile to
              listOf(
                "-cp",
                "$LIBEXEC_PLACEHOLDER/$NATIVE_DAEMON_JAR_STEM/$NATIVE_DAEMON_JAR_STEM.jar",
                NATIVE_DAEMON_MAIN_CLASS,
              )
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf(
        "-cp",
        "$prefix/libexec/$NATIVE_DAEMON_JAR_STEM/$NATIVE_DAEMON_JAR_STEM.jar",
        NATIVE_DAEMON_MAIN_CLASS,
      ),
      resolved.launchArgs,
    )
    assertEquals(NativeDaemonJarResolution.Source.Libexec, resolved.source)
  }

  @Test
  fun libexecArgfileIsPickedFromInstalledPrefix() {
    val prefix = "/usr/local"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(
            argfile to listOf("-cp", "$LIBEXEC_PLACEHOLDER/x.jar", NATIVE_DAEMON_MAIN_CLASS)
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$prefix/libexec/x.jar", NATIVE_DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
  }

  // #336: an extracted-but-not-installed tarball ships argfiles still
  // containing `@KOLT_LIBEXEC@`. Without resolver-side substitution the
  // JVM dies with ClassNotFoundException and the daemon never binds.
  @Test
  fun libexecArgfileSubstitutesPlaceholderInsidePath() {
    val prefix = "/srv/kolt-extracted"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val classpathLine =
      "$LIBEXEC_PLACEHOLDER/$NATIVE_DAEMON_JAR_STEM/$NATIVE_DAEMON_JAR_STEM.jar:" +
        "$LIBEXEC_PLACEHOLDER/$NATIVE_DAEMON_JAR_STEM/deps/x.jar"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest = manifests(argfile to listOf("-cp", classpathLine, NATIVE_DAEMON_MAIN_CLASS)),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    val expectedClasspath =
      "$prefix/libexec/$NATIVE_DAEMON_JAR_STEM/$NATIVE_DAEMON_JAR_STEM.jar:" +
        "$prefix/libexec/$NATIVE_DAEMON_JAR_STEM/deps/x.jar"
    assertEquals(listOf("-cp", expectedClasspath, NATIVE_DAEMON_MAIN_CLASS), resolved.launchArgs)
  }

  // assemble-dist's `printf '%s\n' ...` writes a trailing newline; without
  // dropping it the JVM sees an empty argument token and aborts.
  @Test
  fun libexecArgfileDropsEmptyTrailingLines() {
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(
            argfile to listOf("-cp", "$LIBEXEC_PLACEHOLDER/x.jar", NATIVE_DAEMON_MAIN_CLASS, "")
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$prefix/libexec/x.jar", NATIVE_DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
  }

  @Test
  fun libexecArgfileUnreadableFallsThroughToNotFound() {
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    assertEquals(NativeDaemonJarResolution.NotFound, result)
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
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
    val devJar = "/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM.jar"
    val result =
      resolveNativeDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile, devJar),
        readManifest =
          manifests(
            argfile to listOf("-cp", "$LIBEXEC_PLACEHOLDER/x.jar", NATIVE_DAEMON_MAIN_CLASS),
            "/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM-runtime.classpath" to
              emptyList(),
          ),
      )
    val resolved = assertIs<NativeDaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$prefix/libexec/x.jar", NATIVE_DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(NativeDaemonJarResolution.Source.Libexec, resolved.source)
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
