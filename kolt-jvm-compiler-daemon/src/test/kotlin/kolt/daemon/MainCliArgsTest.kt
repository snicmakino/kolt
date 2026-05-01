package kolt.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Pins the daemon's CLI parser shape so a future contributor cannot silently
// drop `--compiler-jars` or `--bta-impl-jars`. Both flags are load-bearing at
// the native-client spawn boundary: `DaemonCompilerBackend.spawnArgv()` passes
// them on every spawn, and removing either at the daemon-side parser without
// updating the spawn argv would turn every fresh daemon launch into a hard
// CliError.MissingXyz — silent on unit tests, visible only as a field regression.
//
// `--compiler-jars` is intentionally retained after the B-2a refactor even
// though daemon-core code no longer loads kotlin-compiler-embeddable itself
// (the reflective `SharedCompilerHost` was removed in commit ad05de8). B-2c
// chose to introduce a separate `--plugin-jars` flag rather than overload
// `--compiler-jars`, so the two channels stay decoupled. Dropping
// `--compiler-jars` remains a back-incompatible change because the native
// client still passes it on every spawn; this test is the explicit record
// of that intent.
class MainCliArgsTest {

  @Test
  fun parseArgsAcceptsAllThreeRequiredFlags() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/kolt-daemon.sock",
          "--compiler-jars",
          "/kt/lib/a.jar:/kt/lib/b.jar",
          "--bta-impl-jars",
          "/bta/impl/kotlin-build-tools-impl.jar",
        )
      )
    val cli = assertNotNull(result.get())
    assertEquals("/tmp/kolt-daemon.sock", cli.socketPath.toString())
    assertEquals(2, cli.compilerJars.size)
    assertEquals(1, cli.btaImplJars.size)
  }

  @Test
  fun compilerJarsFlagIsRequiredEvenThoughDaemonDoesNotLoadItDirectly() {
    // Regression guard for the "drop --compiler-jars because it is dead"
    // temptation. The field is dead inside daemon core after ad05de8 but
    // the flag must stay on the wire so the native client does not break.
    val result =
      parseArgs(arrayOf("--socket", "/tmp/kolt-daemon.sock", "--bta-impl-jars", "/bta/impl/x.jar"))
    assertEquals(CliError.MissingCompilerJars, result.getError())
  }

  @Test
  fun btaImplJarsFlagIsRequired() {
    val result =
      parseArgs(arrayOf("--socket", "/tmp/kolt-daemon.sock", "--compiler-jars", "/kt/lib/a.jar"))
    assertEquals(CliError.MissingBtaImplJars, result.getError())
  }

  @Test
  fun emptyCompilerJarsValueRejected() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/kolt-daemon.sock",
          "--compiler-jars",
          "",
          "--bta-impl-jars",
          "/bta/impl/x.jar",
        )
      )
    assertEquals(CliError.EmptyCompilerJars, result.getError())
  }

  @Test
  fun emptyBtaImplJarsValueRejected() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/kolt-daemon.sock",
          "--compiler-jars",
          "/kt/lib/a.jar",
          "--bta-impl-jars",
          "",
        )
      )
    assertEquals(CliError.EmptyBtaImplJars, result.getError())
  }

  @Test
  fun icRootDefaultsToUserHomeKoltDaemonIc() {
    // ADR 0019 §5: when `--ic-root` is omitted the daemon defaults to
    // `$HOME/.kolt/daemon/ic`. Tests and integration harnesses override
    // via `--ic-root <path>`.
    val result =
      parseArgs(
        arrayOf("--socket", "/tmp/s", "--compiler-jars", "/a.jar", "--bta-impl-jars", "/b.jar")
      )
    val cli = assertNotNull(result.get())
    assertEquals(Path.of(System.getProperty("user.home"), ".kolt", "daemon", "ic"), cli.icRoot)
  }

  @Test
  fun icRootFlagOverridesDefault() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--ic-root",
          "/tmp/custom-ic",
        )
      )
    val cli = assertNotNull(result.get())
    assertEquals(Path.of("/tmp/custom-ic"), cli.icRoot)
  }

  @Test
  fun pluginJarsOptionalDefaultsToEmptyMap() {
    // ADR 0019 §9 + B-2c: `--plugin-jars` is optional. A project that uses
    // no compiler plugins (kolt.toml has no `[kotlin.plugins]` section or all
    // entries are `false`) must not be forced to pass the flag.
    val result =
      parseArgs(
        arrayOf("--socket", "/tmp/s", "--compiler-jars", "/a.jar", "--bta-impl-jars", "/b.jar")
      )
    val cli = assertNotNull(result.get())
    assertEquals(emptyMap(), cli.pluginJars)
  }

  @Test
  fun pluginJarsParsesAliasToClasspath() {
    // Format: `<alias>=<cp>[;<alias>=<cp>...]`. Inside each `<cp>` the
    // usual File.pathSeparator splits entries. The `;` outer separator
    // avoids colliding with `:` (pathSeparator) on Linux.
    val sep = java.io.File.pathSeparator
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--plugin-jars",
          "serialization=/plugins/ser1.jar${sep}/plugins/ser2.jar",
        )
      )
    val cli = assertNotNull(result.get())
    assertEquals(
      mapOf("serialization" to listOf(Path.of("/plugins/ser1.jar"), Path.of("/plugins/ser2.jar"))),
      cli.pluginJars,
    )
  }

  @Test
  fun pluginJarsParsesMultipleAliases() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--plugin-jars",
          "serialization=/p/ser.jar;allopen=/p/open.jar",
        )
      )
    val cli = assertNotNull(result.get())
    assertEquals(
      mapOf(
        "serialization" to listOf(Path.of("/p/ser.jar")),
        "allopen" to listOf(Path.of("/p/open.jar")),
      ),
      cli.pluginJars,
    )
  }

  @Test
  fun pluginJarsMalformedEntryRejected() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--plugin-jars",
          "serialization",
        )
      )
    assertEquals(CliError.MalformedPluginJars("serialization"), result.getError())
  }

  @Test
  fun pluginJarsEmptyAliasRejected() {
    // `=foo.jar` has no alias; `eq <= 0` catches it inside parsePluginJars.
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--plugin-jars",
          "=foo.jar",
        )
      )
    assertEquals(CliError.MalformedPluginJars("=foo.jar"), result.getError())
  }

  @Test
  fun pluginJarsEmptyClasspathRejected() {
    // `alias=` has a trailing `=` with no classpath; `eq == entry.length - 1`
    // catches it before the pathSeparator split even runs.
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--plugin-jars",
          "serialization=",
        )
      )
    assertEquals(CliError.MalformedPluginJars("serialization="), result.getError())
  }

  @Test
  fun unknownFlagRejected() {
    val result =
      parseArgs(
        arrayOf(
          "--socket",
          "/tmp/s",
          "--compiler-jars",
          "/a.jar",
          "--bta-impl-jars",
          "/b.jar",
          "--frobnicate",
        )
      )
    val err = assertNotNull(result.getError()) as CliError.UnknownFlag
    assertEquals("--frobnicate", err.flag)
  }
}
