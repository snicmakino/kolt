@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Pins the ADR 0019 §5 scope-segment contract: a main compile followed by a
// test compile against the same project must not wipe the main outputDir's
// class files when the two compiles use scope-segregated workingDirs. The
// negative variant proves the symptom reproduces under a shared workingDir,
// which is what would happen if a future regression collapsed the segment.
//
// Surfaces faster than the kolt-jvm-compiler-daemon self-host smoke (#376),
// which spins up a real daemon process and a JUnit run; here BTA is driven
// in-process against a tiny fixture.
class BtaInputsCacheCrossScopeTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `per-scope workingDir keeps main outputDir intact after a test compile`() {
    val fixture = setupFixture(prefix = "bta-cross-scope-positive-")
    val mainOutput = fixture.workRoot.resolve("classes").apply { createDirectories() }
    val testOutput = fixture.workRoot.resolve("test-classes").apply { createDirectories() }
    val projectStateDir = fixture.workRoot.resolve("ic").apply { createDirectories() }
    val mainWorking = projectStateDir.resolve("main").apply { createDirectories() }
    val testWorking = projectStateDir.resolve("test").apply { createDirectories() }

    val compiler = newCompiler()

    compiler
      .compile(
        IcRequest(
          projectId = "cross-scope-positive",
          projectRoot = fixture.workRoot,
          sources = listOf(fixture.mainSource),
          classpath = fixtureClasspath,
          outputDir = mainOutput,
          workingDir = mainWorking,
        )
      )
      .getOrElse { fail("main compile failed: $it") }

    val mainClassFilesBefore = relativeClassFiles(mainOutput)
    assertTrue(
      mainClassFilesBefore.isNotEmpty(),
      "main compile must produce class files; got: $mainClassFilesBefore",
    )

    compiler
      .compile(
        IcRequest(
          projectId = "cross-scope-positive",
          projectRoot = fixture.workRoot,
          sources = listOf(fixture.testSource),
          classpath = fixtureClasspath + listOf(mainOutput),
          outputDir = testOutput,
          workingDir = testWorking,
        )
      )
      .getOrElse { fail("test compile failed: $it") }

    val mainClassFilesAfter = relativeClassFiles(mainOutput)
    assertEquals(
      mainClassFilesBefore.toSet(),
      mainClassFilesAfter.toSet(),
      "main outputDir's class files must survive a per-scope test compile",
    )
  }

  @Test
  fun `shared workingDir reproduces the inputsCache cross-scope wipe`() {
    val fixture = setupFixture(prefix = "bta-cross-scope-negative-")
    val mainOutput = fixture.workRoot.resolve("classes").apply { createDirectories() }
    val testOutput = fixture.workRoot.resolve("test-classes").apply { createDirectories() }
    val sharedWorking = fixture.workRoot.resolve("ic-shared").apply { createDirectories() }

    val compiler = newCompiler()

    compiler
      .compile(
        IcRequest(
          projectId = "cross-scope-negative",
          projectRoot = fixture.workRoot,
          sources = listOf(fixture.mainSource),
          classpath = fixtureClasspath,
          outputDir = mainOutput,
          workingDir = sharedWorking,
        )
      )
      .getOrElse { fail("main compile failed: $it") }

    val mainClassFilesBefore = relativeClassFiles(mainOutput)
    assertTrue(
      mainClassFilesBefore.isNotEmpty(),
      "main compile must produce class files; got: $mainClassFilesBefore",
    )

    // Compile result may be Ok or CompilationFailed: BTA's
    // removeOutputForSourceFiles can wipe Main.class before kotlinc runs,
    // making the test compile itself fail with `Unresolved reference 'Main'`.
    // Either outcome counts as the wipe symptom; the load-bearing assertion
    // below is on mainOutput state after the second compile.
    val testResult =
      compiler.compile(
        IcRequest(
          projectId = "cross-scope-negative",
          projectRoot = fixture.workRoot,
          sources = listOf(fixture.testSource),
          classpath = fixtureClasspath + listOf(mainOutput),
          outputDir = testOutput,
          workingDir = sharedWorking,
        )
      )

    val mainClassFilesAfter = relativeClassFiles(mainOutput)
    assertTrue(
      mainClassFilesAfter.size < mainClassFilesBefore.size,
      "shared workingDir must wipe at least one main class file; " +
        "testResult=$testResult before=$mainClassFilesBefore after=$mainClassFilesAfter",
    )
  }

  private data class Fixture(val workRoot: Path, val mainSource: Path, val testSource: Path)

  private fun setupFixture(prefix: String): Fixture {
    val workRoot = Files.createTempDirectory(prefix)
    val mainSource =
      workRoot.resolve("Main.kt").apply {
        writeText(
          """
                package fixture
                object Main {
                    fun greeting(): String = "hello"
                }
                """
            .trimIndent()
        )
      }
    val testSource =
      workRoot.resolve("MainTest.kt").apply {
        writeText(
          """
                package fixture
                fun usesMain(): String = Main.greeting()
                """
            .trimIndent()
        )
      }
    return Fixture(workRoot, mainSource, testSource)
  }

  private fun newCompiler(): IncrementalCompiler =
    BtaIncrementalCompiler.create(btaImplJars).getOrElse {
      fail("failed to load BTA toolchain: $it")
    }

  private fun relativeClassFiles(dir: Path): List<String> =
    dir.walk().filter { it.extension == "class" }.map { dir.relativize(it).toString() }.toList()

  private fun systemClasspath(key: String): List<Path> {
    val raw =
      System.getProperty(key)
        ?: error(
          "$key system property not set — check kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]"
        )
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
