@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdtemp

/**
 * E2E for Did-you-mean output on unknown subcommand and unknown global flag (tasks.md 8.2,
 * requirements 5.2, 5.3, 5.4).
 *
 * Spawns the built kolt.kexe with intentional typos and asserts on the rendered stderr:
 * - `kolt --no-color buidl` → exit 127, `error: unknown command 'buidl'` plus `note: Did you mean
 *   `build`?`.
 * - `kolt --no-color --no-clor build` → exit 127, `error: unknown flag '--no-clor'` plus `note: Did
 *   you mean `--no-color`?`.
 * - `kolt --no-color xyzzy-totally-unrelated-blob` → exit 127, error line present, no `Did you
 *   mean` and no `note:` line because the typo is beyond the adaptive threshold.
 *
 * Each invocation passes `--no-color` so stderr is byte-deterministic for substring comparison
 * (R5.4); the unknown-flag case still resolves the leading `--no-color` before the unknown-flag
 * branch fires, so color is suppressed in the diagnostic.
 *
 * Gated behind `KOLT_DIDYOUMEAN_E2E=1` because the cases require a built `build/debug/kolt.kexe`
 * (or `build/release/kolt.kexe`); without the env var the methods return immediately so `kolt test`
 * stays fast.
 *
 * Shell harness scripts use a Kotlin-side D = "$" token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class DidYouMeanE2EIT {

  // R5.2: unknown subcommand within edit-distance threshold gets a `Did you mean` hint.
  @Test
  fun unknownSubcommandWithNearMatchSuggestsBuild() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-dym-cmd-")
    val script =
      """
            set -u
            cd "$workdir"
            "$kolt" --no-color buidl > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertEquals(EXIT_COMMAND_NOT_FOUND, exit, "unknown command must exit 127; stderr=$stderr")
    assertTrue(
      stderr.contains("error: unknown command 'buidl'"),
      "stderr must contain unknown-command error; got:\n$stderr",
    )
    assertTrue(
      stderr.contains("note: Did you mean `build`?"),
      "stderr must contain Did-you-mean note for build; got:\n$stderr",
    )
  }

  // R5.3: unknown global flag within edit-distance threshold gets a `Did you mean` hint.
  // The leading `--no-color` is the *known* flag so color is off; the trailing `--no-clor`
  // is the typo under test, exercising parseKoltArgs's unknown-flag boundary.
  @Test
  fun unknownGlobalFlagWithNearMatchSuggestsNoColor() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-dym-flag-")
    val script =
      """
            set -u
            cd "$workdir"
            "$kolt" --no-color --no-clor build > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertEquals(EXIT_COMMAND_NOT_FOUND, exit, "unknown flag must exit 127; stderr=$stderr")
    assertTrue(
      stderr.contains("error: unknown flag '--no-clor'"),
      "stderr must contain unknown-flag error; got:\n$stderr",
    )
    assertTrue(
      stderr.contains("note: Did you mean `--no-color`?"),
      "stderr must contain Did-you-mean note for --no-color; got:\n$stderr",
    )
  }

  // R5.2 negative arm + R5.4: a far-off typo must not produce a hint, and the absence
  // must be deterministic (no `note:` line, no `Did you mean` substring anywhere in
  // stderr).
  @Test
  fun unknownSubcommandBeyondThresholdHasNoSuggestion() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val workdir = createTempDir("kolt-it-dym-far-")
    val script =
      """
            set -u
            cd "$workdir"
            "$kolt" --no-color xyzzy-totally-unrelated-blob > out.stdout 2> out.stderr
            echo $D? > out.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(workdir, "out.exit")
    val stderr = readOptional(workdir, "out.stderr") ?: ""
    assertNotEquals(0, exit, "unknown command must fail; stderr=$stderr")
    assertTrue(
      stderr.contains("error: unknown command 'xyzzy-totally-unrelated-blob'"),
      "stderr must contain unknown-command error; got:\n$stderr",
    )
    assertFalse(
      stderr.contains("Did you mean"),
      "stderr must not contain Did-you-mean for far-off typo; got:\n$stderr",
    )
    assertFalse(
      stderr.contains("note:"),
      "stderr must not contain note: line when there is no suggestion; got:\n$stderr",
    )
  }

  // The IT cases need a kolt-built kolt.kexe. `kolt test` does not produce
  // the executable on its own; if the user opted into the gate the binary
  // must already be present.
  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_DIDYOUMEAN_E2E=1 but kolt.kexe is not built. Run " +
          "`kolt build` first. Looked under: $candidates"
      )
    }
    return found
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }

  private fun currentWorkingDir(): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    getcwd(buf, PATH_MAX.toULong())?.toKString()
  }

  private fun runHarness(script: String) {
    executeCommand(listOf("bash", "-c", script)).getOrElse { err ->
      error("harness bash failed: $err — script was:\n$script")
    }
  }

  private fun readExit(dir: String, name: String): Int {
    val raw =
      readFileAsString("$dir/$name").getOrElse {
        error("missing $dir/$name — harness did not record an exit code")
      }
    return raw.trim().toIntOrNull() ?: error("could not parse exit code from $dir/$name: '$raw'")
  }

  private fun readOptional(dir: String, name: String): String? {
    val path = "$dir/$name"
    if (!fileExists(path)) return null
    return readFileAsString(path).getOrElse {
      return null
    }
  }

  private fun enabled(): Boolean {
    val on = getenv("KOLT_DIDYOUMEAN_E2E")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "DidYouMeanE2EIT: skipped (set KOLT_DIDYOUMEAN_E2E=1 and run `kolt build` to enable)"
      )
    }
    return on
  }

  companion object {
    private const val D = "$"

    private var skipNoticePrinted = false
  }
}
