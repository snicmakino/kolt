package kolt.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// kolt-jvm-compiler-daemon now compiles root daemon and ic into the
// same kolt project, so the Gradle module boundary that previously
// kept ic test code from importing root daemon production packages
// is gone. With both source sets sharing one test compile classpath,
// nothing at compile time stops an ic test from reaching into
// `kolt.daemon.Main`, `kolt.daemon.server.*`, or `kolt.daemon.reaper.*`.
// This test re-encodes that boundary statically by walking ic test
// sources and failing on any forbidden import — pure source scan,
// no production dependency, no flake surface.
class IcModuleBoundaryInvariantTest {

  @Test
  fun `ic test sources do not import root daemon production packages`() {
    val sourceRoot =
      Path.of(
        System.getProperty("kolt.daemon.icTestSourceRoot")
          ?: error(
            "kolt.daemon.icTestSourceRoot system property not set — " +
              "declare it in kolt-jvm-compiler-daemon/kolt.toml under [test.sys_props] " +
              "as `\"kolt.daemon.icTestSourceRoot\" = { project_dir = \"ic/src/test/kotlin\" }`"
          )
      )
    assertTrue(Files.isDirectory(sourceRoot), "expected ic test source root at $sourceRoot")

    val forbiddenPrefixes =
      listOf("import kolt.daemon.Main", "import kolt.daemon.server.", "import kolt.daemon.reaper.")

    val offenders = mutableListOf<Pair<Path, String>>()
    Files.walk(sourceRoot).use { stream ->
      stream
        .asSequence()
        .filter { it.toString().endsWith(".kt") }
        .forEach { file ->
          Files.readAllLines(file).forEachIndexed { idx, line ->
            if (forbiddenPrefixes.any { line.contains(it) }) {
              offenders += file to "${idx + 1}: $line"
            }
          }
        }
    }

    assertEquals(
      emptyList<Pair<Path, String>>(),
      offenders,
      "ic test sources must not import kolt.daemon.{Main,server.*,reaper.*} — " +
        "those are root daemon production packages and the merged kolt project " +
        "no longer enforces this via Gradle module isolation. Offenders:\n" +
        offenders.joinToString("\n") { (p, l) -> "  $p:$l" },
    )
  }
}
