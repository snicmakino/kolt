package kolt.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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
    // Skip when run outside kolt (e.g. cross-check via `./gradlew check` while
    // the orphan Gradle config is still around): the Gradle test task does not
    // declare this sysprop, and adding it would mean editing config that #316
    // is about to delete. The kolt path always sets the sysprop via
    // [test.sys_props.kolt.daemon.icTestSourceRoot] in kolt.toml.
    val sourceRootProp = System.getProperty("kolt.daemon.icTestSourceRoot")
    assumeTrue(
      sourceRootProp != null,
      "kolt.daemon.icTestSourceRoot not set — invariant only enforced under `kolt test`",
    )
    val sourceRoot = Path.of(sourceRootProp!!)
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
