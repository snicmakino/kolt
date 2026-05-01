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
import kotlin.test.assertTrue
import kotlin.test.fail

// ADR 0019 Follow-ups + B-2c: complements BtaIncrementalRecompileSetTest
// (which pins the bimodal-1 ABI-neutral win) by exercising the other side
// of the distribution — an ABI-affecting signature change. Spike #104's
// residual note (O.Q. 8 caveat) flagged this scenario as required B-2
// validation: "linear chain with signature change in mid-chain function
// + matching call-site edits, to observe the cascade depth".
//
// Fixture: linear-5 (same shape BtaIncrementalRecompileSetTest uses) so
// the test stays fast and self-contained. The ABI edit changes `f3`'s
// signature from `f3(): Int` to `f3(bonus: Int): Int`, and updates `f2`'s
// call site to pass an explicit argument. F1 / F4 / F5 are ABI-neutral
// to the edit, so a correct IC configuration recompiles the changed
// files (at minimum f3.class and f2.class) but stops short of the full
// file count — proving BTA's ABI snapshot is keeping IC smarter than a
// "recompile everything downstream of the touched file" fallback.
//
// The assertion is deliberately loose: `2 <= changed < coldCount`. BTA
// 2.3.x may legitimately recompile additional downstream files if its
// ABI-diff logic decides they transitively observe the signature change,
// and tightening the bound would bind the test to a compiler-internal
// heuristic that shifts with every minor version. The structural claim
// the test cares about is "cascade happened AND cascade stopped short of
// full".
class BtaIncrementalAbiCascadeTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `signature change on mid chain function cascades to caller but stops short of full`() {
    val workRoot = Files.createTempDirectory("bta-abi-cascade-")
    val sourcesDir = workRoot.resolve("src").apply { createDirectories() }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val workingDir = workRoot.resolve("ic-state")

    // Each file owns exactly one top-level `fun` so .class files map
    // 1:1 to source files (modulo synthetic artefacts BTA may emit).
    // F3 is mid-chain; it is the one we mutate.
    val f1 =
      sourcesDir.resolve("F1.kt").also {
        it.writeText(
          """
                package fixture
                fun f1(): Int = f2() + 1
                """
            .trimIndent()
        )
      }
    val f2 =
      sourcesDir.resolve("F2.kt").also {
        it.writeText(
          """
                package fixture
                fun f2(): Int = f3() + 2
                """
            .trimIndent()
        )
      }
    val f3 =
      sourcesDir.resolve("F3.kt").also {
        it.writeText(
          """
                package fixture
                fun f3(): Int = f4() + 3
                """
            .trimIndent()
        )
      }
    val f4 =
      sourcesDir.resolve("F4.kt").also {
        it.writeText(
          """
                package fixture
                fun f4(): Int = f5() + 4
                """
            .trimIndent()
        )
      }
    val f5 =
      sourcesDir.resolve("F5.kt").also {
        it.writeText(
          """
                package fixture
                fun f5(): Int = 5
                """
            .trimIndent()
        )
      }
    val files = listOf(f1, f2, f3, f4, f5)

    val compiler =
      BtaIncrementalCompiler.create(btaImplJars).getOrElse {
        fail("failed to load BTA toolchain: $it")
      }

    val request =
      IcRequest(
        projectId = "abi-cascade-smoke",
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

    // --- ABI-affecting signature change on f3 + matching f2 call site ---
    Files.writeString(
      f3,
      """
            package fixture
            fun f3(bonus: Int): Int = f4() + 3 + bonus
            """
        .trimIndent(),
    )
    Files.writeString(
      f2,
      """
            package fixture
            fun f2(): Int = f3(10) + 2
            """
        .trimIndent(),
    )

    // --- warm ---
    compiler.compile(request).getOrElse { fail("warm compile failed: $it") }
    val warmHashes = hashClassFiles(outputDir)

    val changed = warmHashes.filter { (path, hash) -> coldHashes[path] != hash }

    // Cascade happened: f3 changed its ABI, f2 had to re-link against
    // the new signature, so at minimum two .class files differ between
    // cold and warm. A single-file change here would mean BTA did not
    // observe the call-site edit, which the test is specifically
    // guarding against.
    assertTrue(
      changed.size >= 2,
      "expected signature change to cascade to at least the caller " +
        "(f3 + f2 = 2 class files), got changed=${changed.size} " +
        "(${changed.keys.sorted()})",
    )

    // Cascade stopped short of full: IC is still smarter than "recompile
    // everything". A regression producing `changed.size == coldCount`
    // would mean the ABI snapshot failed to spare f1 / f4 / f5 from
    // recompilation — the inverse of the bimodal floor, and the
    // signal that the #103 ceiling has been hit.
    assertTrue(
      changed.size < coldCount,
      "expected ABI cascade to stop short of full recompile, " +
        "got changed=${changed.size} of $coldCount " +
        "(${changed.keys.sorted()})",
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
