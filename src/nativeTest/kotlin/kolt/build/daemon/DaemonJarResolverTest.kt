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
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = "",
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(
            argfile to
              listOf(
                "-cp",
                "$LIBEXEC_PLACEHOLDER/$DAEMON_JAR_STEM/$DAEMON_JAR_STEM.jar",
                DAEMON_MAIN_CLASS,
              )
          ),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$prefix/libexec/$DAEMON_JAR_STEM/$DAEMON_JAR_STEM.jar", DAEMON_MAIN_CLASS),
      resolved.launchArgs,
    )
    assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
  }

  @Test
  fun libexecArgfileResolvesFromInstalledPrefix() {
    val prefix = "/usr/local"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(
            argfile to
              listOf("-cp", "$LIBEXEC_PLACEHOLDER/some.jar:$LIBEXEC_PLACEHOLDER/other.jar", "Main")
          ),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(
      listOf("-cp", "$prefix/libexec/some.jar:$prefix/libexec/other.jar", "Main"),
      resolved.launchArgs,
    )
    assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
  }

  // #336: an extracted-but-not-installed tarball ships argfiles still
  // containing `@KOLT_LIBEXEC@`. Without resolver-side substitution the
  // JVM dies with ClassNotFoundException and the daemon never binds.
  @Test
  fun libexecArgfileSubstitutesPlaceholderInsidePath() {
    val prefix = "/srv/kolt-extracted"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val classpathLine =
      "$LIBEXEC_PLACEHOLDER/$DAEMON_JAR_STEM/$DAEMON_JAR_STEM.jar:" +
        "$LIBEXEC_PLACEHOLDER/$DAEMON_JAR_STEM/deps/x.jar:" +
        "$LIBEXEC_PLACEHOLDER/kolt-bta-impl/y.jar"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest = manifests(argfile to listOf("-cp", classpathLine, DAEMON_MAIN_CLASS)),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    val expectedClasspath =
      "$prefix/libexec/$DAEMON_JAR_STEM/$DAEMON_JAR_STEM.jar:" +
        "$prefix/libexec/$DAEMON_JAR_STEM/deps/x.jar:" +
        "$prefix/libexec/kolt-bta-impl/y.jar"
    assertEquals(listOf("-cp", expectedClasspath, DAEMON_MAIN_CLASS), resolved.launchArgs)
  }

  // install.sh-installed kolts have already had their argfiles sed-
  // substituted, so the resolver sees absolute paths only. Verify the
  // substitution-by-replace is a no-op when no placeholder is present.
  @Test
  fun libexecArgfileWithoutPlaceholderIsPassedThrough() {
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(argfile to listOf("-cp", "/opt/kolt/libexec/x.jar", DAEMON_MAIN_CLASS)),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("-cp", "/opt/kolt/libexec/x.jar", DAEMON_MAIN_CLASS), resolved.launchArgs)
  }

  // assemble-dist's `printf '%s\n' ...` writes a trailing newline, which
  // surfaces as a final empty element after `String.lines()`. The
  // resolver must drop it; otherwise the JVM sees an empty argument
  // token and aborts with `Error: Could not find or load main class`.
  @Test
  fun libexecArgfileDropsEmptyTrailingLines() {
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest =
          manifests(argfile to listOf("-cp", "$LIBEXEC_PLACEHOLDER/x.jar", DAEMON_MAIN_CLASS, "")),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("-cp", "$prefix/libexec/x.jar", DAEMON_MAIN_CLASS), resolved.launchArgs)
  }

  @Test
  fun libexecArgfileUnreadableFallsThroughToNotFound() {
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile),
        readManifest = { null },
      )
    assertEquals(DaemonJarResolution.NotFound, result)
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
    val prefix = "/opt/kolt"
    val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
    val devJar = "/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM.jar"
    val result =
      resolveDaemonJarPure(
        envValue = null,
        selfExePath = "$prefix/bin/kolt",
        fileExists = exists(argfile, devJar),
        readManifest =
          manifests(
            argfile to listOf("-cp", "$LIBEXEC_PLACEHOLDER/x.jar", DAEMON_MAIN_CLASS),
            "/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM-runtime.classpath" to emptyList(),
          ),
      )
    val resolved = assertIs<DaemonJarResolution.Resolved>(result)
    assertEquals(listOf("-cp", "$prefix/libexec/x.jar", DAEMON_MAIN_CLASS), resolved.launchArgs)
    assertEquals(DaemonJarResolution.Source.Libexec, resolved.source)
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
