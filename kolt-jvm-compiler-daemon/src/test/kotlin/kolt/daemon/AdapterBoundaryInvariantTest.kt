package kolt.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0019 §3 invariant: no file under `kolt-jvm-compiler-daemon/src/main/`
// may import a type from `org.jetbrains.kotlin.buildtools.*`. The
// entire @ExperimentalBuildToolsApi surface is fenced behind the
// `:ic` subproject, and a leak here would be a silent regression:
// daemon-core code would start compiling against the experimental
// surface and future compiler bumps would break the build at the
// wrong layer.
//
// Issue #112 made this a human-review gate (acceptance criterion 2).
// This test mechanises the gate so a contributor who adds a direct
// BTA import anywhere in daemon core gets a red test instead of a
// reviewer catching it weeks later. A non-test Gradle task would
// also work, but putting the check in JUnit means the invariant
// is enforced on every `./gradlew test`, shares its reporting with
// other regressions, and is easy to run in IDE.
class AdapterBoundaryInvariantTest {

  @Test
  fun `no daemon core file imports kotlin buildtools types`() {
    val sourceRoot =
      Path.of(
        System.getProperty("kolt.daemon.coreMainSourceRoot")
          ?: error(
            "kolt.daemon.coreMainSourceRoot system property not set — " +
              "check :kolt-jvm-compiler-daemon/build.gradle.kts `tasks.test`"
          )
      )
    assertTrue(Files.isDirectory(sourceRoot), "expected daemon core source root at $sourceRoot")

    val offenders = mutableListOf<Pair<Path, String>>()
    Files.walk(sourceRoot).use { stream ->
      stream
        .asSequence()
        .filter { it.toString().endsWith(".kt") }
        .forEach { file ->
          Files.readAllLines(file).forEachIndexed { idx, line ->
            // `import org.jetbrains.kotlin.buildtools.` is
            // the shape we are forbidding. Substring match
            // is sufficient — there is no legitimate alias
            // form in kolt's style (stdlib wildcards are
            // not permitted by IntelliJ's formatter).
            if (line.contains("import org.jetbrains.kotlin.buildtools.")) {
              offenders += file to "${idx + 1}: $line"
            }
          }
        }
    }

    assertEquals(
      emptyList<Pair<Path, String>>(),
      offenders,
      "ADR 0019 §3 violation: daemon core must not import kotlin.buildtools.* " +
        "— see the :ic subproject for the adapter layer. Offenders:\n" +
        offenders.joinToString("\n") { (p, l) -> "  $p:$l" },
    )
  }
}
