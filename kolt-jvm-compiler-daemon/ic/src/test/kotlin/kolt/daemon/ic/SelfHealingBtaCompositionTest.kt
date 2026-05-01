@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Pins the `Main.kt` wiring (SelfHealingIncrementalCompiler wrapping a
// real BtaIncrementalCompiler) with a live BTA toolchain. The unit
// tests for each class in isolation cover the happy and error paths,
// but the one code path that wires them together — the two-line
// construction in Main.kt — was unexercised. A future signature break
// on either class would have slipped through until runtime otherwise.
//
// This test is a smoke test, not a corruption replay: driving a real
// BTA failure remains deferred to B-2c (per ADR 0019's spike residual
// risk note on corruption testing). The assertions here are purely
// structural: the composed stack returns Ok for a healthy compile and
// returns the adapter's CompilationFailed for a broken source, with
// the new KotlinLogger diagnostic plumbing surviving the wrap.
class SelfHealingBtaCompositionTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `composed stack returns Ok for a healthy compile`() {
    val workRoot = Files.createTempDirectory("composition-ok-")
    val sourceFile =
      workRoot.resolve("Main.kt").also {
        it.writeText(
          """
                package fixture
                object Main { fun ping(): String = "pong" }
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val workingDir = workRoot.resolve("ic-state")

    val metrics = RecordingMetricsSink()
    val adapter =
      BtaIncrementalCompiler.create(btaImplJars, metrics = metrics).getOrElse {
        fail("adapter construction failed: $it")
      }
    val composed: IncrementalCompiler =
      SelfHealingIncrementalCompiler(delegate = adapter, metrics = metrics)

    val response =
      composed
        .compile(
          IcRequest(
            projectId = "composition-ok",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
          )
        )
        .get() ?: fail("expected Ok from the composed stack")

    assertTrue(response.wallMillis >= 0)
    // No self-heal event should fire on a healthy first compile.
    assertTrue(
      metrics.events.none { it.first == "ic.self_heal" },
      "healthy compile must not fire ic.self_heal, got: ${metrics.events}",
    )
  }

  @Test
  fun `composed stack returns CompilationFailed with real kotlinc diagnostic`() {
    val workRoot = Files.createTempDirectory("composition-err-")
    val sourceFile =
      workRoot.resolve("Broken.kt").also {
        it.writeText(
          """
                package fixture
                fun broken(): Int = "not an int"
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val workingDir = workRoot.resolve("ic-state")

    val metrics = RecordingMetricsSink()
    val adapter =
      BtaIncrementalCompiler.create(btaImplJars, metrics = metrics).getOrElse {
        fail("adapter construction failed: $it")
      }
    val composed: IncrementalCompiler =
      SelfHealingIncrementalCompiler(delegate = adapter, metrics = metrics)

    val err =
      composed
        .compile(
          IcRequest(
            projectId = "composition-err",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
          )
        )
        .getError() ?: fail("expected Err from the composed stack")

    assertTrue(
      err is IcError.CompilationFailed,
      "CompilationFailed must pass through the self-heal wrapper unchanged, got: $err",
    )
    assertTrue(err.messages.isNotEmpty(), "diagnostic capture must survive the composition")
    val joined = err.messages.joinToString("\n")
    assertTrue(
      joined.contains("Broken.kt", ignoreCase = true) ||
        joined.contains("type mismatch", ignoreCase = true),
      "composed stack must surface real kotlinc diagnostic, got:\n$joined",
    )

    // The wrapper must NOT fire self-heal on a CompilationFailed —
    // that would loop on every type error.
    assertEquals(
      0,
      metrics.events.count { it.first == "ic.self_heal" },
      "CompilationFailed path must not fire ic.self_heal",
    )
  }

  private class RecordingMetricsSink : IcMetricsSink {
    val events: MutableList<Pair<String, Long>> = mutableListOf()

    override fun record(name: String, value: Long) {
      events += name to value
    }
  }

  private fun systemClasspath(key: String): List<Path> {
    val raw =
      System.getProperty(key)
        ?: error("$key system property not set — check :ic/build.gradle.kts test task config")
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
