@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Regression guard for the bimodal IC win that spike #104 measured: on
// an ABI-neutral edit inside a leaf function body, BTA must only
// recompile the touched file, not the whole source set. The spike
// measured 1-file recompile sets on its `linear-10` / `hub-10` fixtures
// (6.9×–7.5× speedup vs cold); this test replays the same shape against
// a 5-file linear chain so the number stays reachable from the unit
// test task without dragging spike fixtures into `:ic`.
//
// Detection strategy: hash every .class file cold, touch a single Int
// literal inside a leaf function body (no signature change, no ABI
// impact), compile warm, hash again, and count how many .class files
// differ. If the IC configuration attached in
// `BtaIncrementalCompiler.executeCompile` is not actually driving BTA
// incrementally, the warm compile will re-emit .class bytes for every
// file and the changed-set size will equal the total file count.
//
// Why this is NOT an assertion on "exactly 1" — BTA may legitimately
// rewrite one or two other .class files (synthetic inner classes,
// module-info, etc.) even on the happy path. The test asserts the
// changed set is strictly smaller than the full file count, which is
// the structural claim that matters: "IC reused prior state". A tighter
// "<= 2" bound pins the spike's 1-file-per-touch result as a bimodal
// floor.
class BtaIncrementalRecompileSetTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `warm compile after leaf body literal change recompiles at most 2 class files`() {
    val workRoot = Files.createTempDirectory("bta-recompile-set-")
    val sourcesDir = workRoot.resolve("src").apply { createDirectories() }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val projectStateDir = workRoot.resolve("ic").apply { createDirectories() }
    val workingDir = projectStateDir.resolve("main").apply { createDirectories() }

    // Linear chain: F1 calls F2 calls F3 calls F4 calls F5. Each file
    // holds one top-level function. F5 is the leaf whose body we
    // touch in the ABI-neutral edit below.
    val files =
      (1..5).map { i ->
        val path = sourcesDir.resolve("F$i.kt")
        path.writeText(
          if (i == 5) {
            """
                    package fixture
                    fun f5(): Int {
                        val x = 1
                        return x
                    }
                    """
              .trimIndent()
          } else {
            """
                    package fixture
                    fun f$i(): Int = f${i + 1}() + $i
                    """
              .trimIndent()
          }
        )
        path
      }

    val compiler =
      BtaIncrementalCompiler.create(btaImplJars).getOrElse {
        fail("failed to load BTA toolchain: $it")
      }

    val request =
      IcRequest(
        projectId = "recompile-set-smoke",
        projectRoot = workRoot,
        sources = files,
        classpath = fixtureClasspath,
        outputDir = outputDir,
        workingDir = workingDir,
      )

    // --- cold ---
    compiler.compile(request).getOrElse { fail("cold compile failed: $it") }
    val coldHashes = hashClassFiles(outputDir)
    val coldCount = coldHashes.size
    assertTrue(
      coldCount >= 5,
      "expected at least 5 class files after cold compile of the 5-file chain, got: $coldCount",
    )

    // --- ABI-neutral body literal edit on the leaf ---
    // `1` → `2` inside the body of `f5`. No signature change, no
    // visibility change, no new symbol. BTA must recognise this as
    // a body-only edit and avoid recompiling f1..f4.
    val leafSource = files.last()
    val originalText = Files.readString(leafSource)
    val touchedText = originalText.replace("val x = 1", "val x = 2")
    assertTrue(
      touchedText != originalText,
      "precondition: the leaf fixture must contain `val x = 1` so the touch below is observable",
    )
    Files.writeString(leafSource, touchedText)

    // --- warm ---
    compiler.compile(request).getOrElse { fail("warm compile failed: $it") }
    val warmHashes = hashClassFiles(outputDir)

    val changed = warmHashes.filter { (path, hash) -> coldHashes[path] != hash }
    val added = warmHashes.keys - coldHashes.keys
    val removed = coldHashes.keys - warmHashes.keys

    // Assert structural IC reuse: the changed-set is strictly smaller
    // than the full file count. Without IC attached, BTA would
    // re-emit every .class file and this check would fail.
    assertTrue(
      changed.size < coldCount,
      "expected a strict subset of class files to change, " +
        "got changed=${changed.size} of $coldCount " +
        "(changed=${changed.keys.sorted()})",
    )

    // Pin the spike's bimodal-1 bound. BTA in 2.3.20 may legitimately
    // touch a second .class file (synthetic / module artefact) on
    // body-only edits, so the tight bound is 2, not 1. A regression
    // here would mean the IC configuration on
    // BtaIncrementalCompiler.executeCompile has stopped reaching
    // BTA's incremental decision engine.
    assertTrue(
      changed.size <= 2,
      "ABI-neutral leaf-body edit should recompile at most 2 class files, " +
        "got changed=${changed.size} (${changed.keys.sorted()})",
    )

    assertEquals(emptySet<String>(), added, "no new class files expected from a body-only edit")
    assertEquals(
      emptySet<String>(),
      removed,
      "no class files should disappear from a body-only edit",
    )
  }

  private fun hashClassFiles(classesDir: Path): Map<String, String> {
    if (!Files.isDirectory(classesDir)) return emptyMap()
    val md = MessageDigest.getInstance("SHA-256")
    return classesDir
      .walk()
      .filter { it.extension == "class" }
      .associate { p ->
        val rel = p.relativeTo(classesDir).toString()
        md.reset()
        md.update(Files.readAllBytes(p))
        rel to md.digest().joinToString("") { "%02x".format(it) }
      }
  }

  private fun systemClasspath(key: String): List<Path> {
    val raw =
      System.getProperty(key)
        ?: error(
          "$key system property not set — check kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]"
        )
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
