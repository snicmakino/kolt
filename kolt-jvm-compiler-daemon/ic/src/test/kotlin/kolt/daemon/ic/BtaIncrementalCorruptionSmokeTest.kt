@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// ADR 0019 §7 + B-2c required validation: end-to-end replay of the
// cache-corruption scenario the ADR's self-heal path exists to handle.
// B-2b landed the wipe+retry wrapper and unit-tested it against a fake
// delegate; the spike REPORT flagged "smoke test for IC cache
// corruption" as the residual validation, and this test closes it.
//
// Method: drive a cold compile through the real composed stack, then
// corrupt every regular file BTA wrote under `workingDir` by truncating
// them to zero bytes. Truncation is chosen over "overwrite one specific
// file name" because BTA's state layout is version-dependent — the test
// would rot silently the moment JetBrains renamed a cache file. Blindly
// truncating everything is layout-agnostic and guarantees the next BTA
// invocation either throws or returns a non-SUCCESS `CompilationResult`,
// either of which is the `IcError.InternalError` path that self-heal
// keys off.
//
// Expected outcome: the second compile returns `Ok(IcResponse)` because
// self-heal wipes the broken state and retries from scratch; the
// `ic.self_heal` counter is recorded so a future `kolt doctor` can
// observe the event.
class BtaIncrementalCorruptionSmokeTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `truncated working dir triggers self-heal and second compile succeeds`() {
    val workRoot = Files.createTempDirectory("bta-corrupt-")
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

    val request =
      IcRequest(
        projectId = "corruption-smoke",
        projectRoot = workRoot,
        sources = listOf(sourceFile),
        classpath = fixtureClasspath,
        outputDir = outputDir,
        workingDir = workingDir,
      )

    // Cold compile — establishes BTA state under workingDir.
    composed.compile(request).get() ?: fail("expected Ok from initial cold compile")
    assertTrue(
      Files.exists(workingDir) && workingDir.toFile().listFiles()?.isNotEmpty() == true,
      "cold compile should have populated workingDir: $workingDir",
    )

    // Corrupt every regular file under workingDir. Directory structure
    // is preserved so the retry path can still enumerate entries;
    // BTA's on-disk deserialisation either throws or yields a
    // non-SUCCESS CompilationResult against zero-byte state files.
    val truncated = mutableListOf<Path>()
    workingDir
      .walk()
      .filter { it.isRegularFile() }
      .forEach { path ->
        Files.newByteChannel(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          .close()
        truncated.add(path)
      }
    assertTrue(
      truncated.isNotEmpty(),
      "expected BTA to have written at least one state file under $workingDir",
    )

    // Clear pre-corruption events so the assertion below only sees
    // what the second compile emits.
    metrics.events.clear()

    // Warm compile — self-heal must fire, wipe, retry, and ultimately
    // return Ok because the retry is a clean cold recompile.
    val response =
      composed.compile(request).get()
        ?: fail("expected self-heal to recover the second compile, " + "metrics=${metrics.events}")
    assertTrue(response.wallMillis >= 0)

    assertTrue(
      metrics.events.any { it.first == SelfHealingIncrementalCompiler.METRIC_SELF_HEAL },
      "ic.self_heal must have fired; metrics=${metrics.events}",
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
        ?: error(
          "$key system property not set — check kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]"
        )
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
