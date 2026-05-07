@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Drives BtaIncrementalCompiler through a cold full-recompile against a tiny
// single-file fixture. B-2a does not enable IC configuration — this test proves
// the classloader topology + JvmCompilationOperation wiring produces .class
// output end-to-end, which is the structural claim ADR 0019 §3 needs before
// B-2b layers state management on top.
//
// Both jar classpaths come from system properties declared in
// kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]:
//   kolt.ic.btaImplClasspath  — kotlin-build-tools-impl:2.3.20 transitive
//   kolt.ic.fixtureClasspath  — kotlin-stdlib:2.3.20 (compile classpath for the fixture)
class BtaIncrementalCompilerColdPathTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `cold compile of a single-file fixture produces a class file`() {
    val workRoot = Files.createTempDirectory("bta-cold-")
    val sourceFile =
      workRoot.resolve("Main.kt").also {
        it.writeText(
          """
                package fixture
                object Main {
                    fun greeting(): String = "hello"
                }
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    // #376: workingDir is `<projectStateDir>/<scope>`. LOCK and breadcrumb
    // live at projectStateDir so the reaper sees one set per project.
    val projectStateDir = workRoot.resolve("ic").apply { createDirectories() }
    val workingDir = projectStateDir.resolve("main").apply { createDirectories() }

    val compiler =
      BtaIncrementalCompiler.create(btaImplJars).getOrElse {
        fail("failed to load BTA toolchain: $it")
      }

    compiler
      .compile(
        IcRequest(
          projectId = "cold-path-smoke",
          projectRoot = workRoot,
          sources = listOf(sourceFile),
          classpath = fixtureClasspath,
          outputDir = outputDir,
          workingDir = workingDir,
        )
      )
      .getOrElse { err -> fail("expected success, got Err($err)") }

    val classFiles = outputDir.walk().filter { it.extension == "class" }.toList()
    assertTrue(classFiles.isNotEmpty(), "expected at least one .class under $outputDir")
    assertTrue(
      classFiles.any { it.fileName.toString() == "Main.class" },
      "expected fixture.Main.class in output: $classFiles",
    )

    // ADR 0019 §Negative follow-up — IC reaper coordination: the
    // cold path must drop a `project.path` breadcrumb at the
    // projectStateDir level (one per project, not per scope) so the
    // reaper can distinguish live projectId dirs from stale ones
    // after a project is moved or deleted.
    val breadcrumb = projectStateDir.resolve("project.path")
    assertTrue(breadcrumb.exists(), "expected project.path breadcrumb at $breadcrumb")
    assertEquals(workRoot.toString(), breadcrumb.readText().trim())

    // #199: LOCK must exist under projectStateDir after compile and its
    // mtime must be no later than the breadcrumb's — a literal
    // swap of the write order back to breadcrumb-first would flip
    // this. Strict `<` would be flaky on WSL2 9p (1s mtime
    // granularity); `<=` catches the swap without false positives
    // on fast filesystems.
    val lock = projectStateDir.resolve("LOCK")
    assertTrue(lock.exists(), "expected LOCK at $lock")
    val lockMtime = Files.getLastModifiedTime(lock)
    val breadcrumbMtime = Files.getLastModifiedTime(breadcrumb)
    assertTrue(
      lockMtime <= breadcrumbMtime,
      "expected LOCK mtime ($lockMtime) <= breadcrumb mtime ($breadcrumbMtime) — ordering regressed",
    )
  }

  // A user type error is the only BTA outcome that maps to CompilationFailed.
  // COMPILER_INTERNAL_ERROR and COMPILATION_OOM_ERROR are compiler-infrastructure
  // failures and map to InternalError so B-2b's self-heal retry path can fire on
  // them; see BtaIncrementalCompiler.executeCompile and ADR 0019 §7.
  @Test
  fun `user type error is reported as CompilationFailed not InternalError`() {
    val workRoot = Files.createTempDirectory("bta-err-")
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
    val projectStateDir = workRoot.resolve("ic").apply { createDirectories() }
    val workingDir = projectStateDir.resolve("main").apply { createDirectories() }

    val compiler =
      BtaIncrementalCompiler.create(btaImplJars).getOrElse {
        fail("failed to load BTA toolchain: $it")
      }

    val err =
      compiler
        .compile(
          IcRequest(
            projectId = "cold-path-err",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
          )
        )
        .getError() ?: fail("expected Err for broken source")

    assertTrue(err is IcError.CompilationFailed, "expected CompilationFailed, got $err")
    // ADR 0019 §7 diagnostics: the captured KotlinLogger error lines
    // must surface the actual kotlinc message (source file + error
    // text), not the synthetic "kotlinc reported COMPILATION_ERROR"
    // stub. The stub is a last-resort fallback for the pathological
    // case where BTA returns COMPILATION_ERROR without emitting any
    // logger line; in practice a real type error produces at least
    // one. A regression on this assertion would take dogfood users
    // back to the "what did I break?" experience.
    assertTrue(
      err.messages.isNotEmpty(),
      "expected at least one captured diagnostic, got: ${err.messages}",
    )
    val joined = err.messages.joinToString("\n")
    assertTrue(
      joined.contains("Broken.kt", ignoreCase = true) ||
        joined.contains("type mismatch", ignoreCase = true),
      "expected the captured message to reference the broken source or the type error, got:\n$joined",
    )
    assertTrue(
      !joined.contains("kotlinc reported COMPILATION_ERROR"),
      "stub fallback must not fire when real diagnostics were captured, got:\n$joined",
    )
  }

  // A second compile against the same classpath must observe a
  // `shrunk_cache.lookup=hit` log line, proving the global cache hooks
  // (lookupAndPlace before BTA, storeIfNew after a successful BTA call)
  // are wired end-to-end. The first compile populates the cache; only
  // the second compile's stderr is captured so the assertion does not
  // have to disambiguate the first-compile miss from the second-compile
  // hit.
  @Test
  fun `second compile with same classpath emits shrunk_cache lookup hit`() {
    val workRoot = Files.createTempDirectory("bta-shrunk-cache-")
    val sourceFile =
      workRoot.resolve("Main.kt").also {
        it.writeText(
          """
                package fixture
                object Main { fun greeting(): String = "hello" }
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val cacheDir = workRoot.resolve("shrunk-snapshots").apply { createDirectories() }
    val cache = ShrunkClasspathSnapshotCache(cacheDir = cacheDir)

    val compiler =
      BtaIncrementalCompiler.create(btaImplJars = btaImplJars, shrunkSnapshotCache = cache)
        .getOrElse { fail("failed to load BTA toolchain: $it") }

    fun runCompile(scope: String) {
      val projectStateDir = workRoot.resolve("ic-$scope").apply { createDirectories() }
      val workingDir = projectStateDir.resolve(scope).apply { createDirectories() }
      compiler
        .compile(
          IcRequest(
            projectId = "shrunk-cache-smoke-$scope",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
          )
        )
        .getOrElse { fail("expected success on $scope compile, got Err($it)") }
    }

    runCompile("first")

    val captured = ByteArrayOutputStream()
    val originalErr = System.err
    System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    try {
      runCompile("second")
    } finally {
      System.setErr(originalErr)
    }
    val log = captured.toString(Charsets.UTF_8)

    assertTrue(
      log.contains("shrunk_cache.lookup=hit"),
      "expected `shrunk_cache.lookup=hit` on the second compile; captured stderr was:\n$log",
    )
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
