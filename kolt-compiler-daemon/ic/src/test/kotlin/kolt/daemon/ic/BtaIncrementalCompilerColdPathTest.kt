@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import java.io.File
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
// Both jar classpaths come from system properties that :ic/build.gradle.kts
// injects into the Gradle test task from resolvable configurations:
//   kolt.ic.btaImplClasspath  — kotlin-build-tools-impl:2.3.20 transitive
//   kolt.ic.fixtureClasspath  — kotlin-stdlib:2.3.20 (compile classpath for the fixture)
class BtaIncrementalCompilerColdPathTest {

    private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
    private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

    @Test
    fun `cold compile of a single-file fixture produces a class file`() {
        val workRoot = Files.createTempDirectory("bta-cold-")
        val sourceFile = workRoot.resolve("Main.kt").also {
            it.writeText(
                """
                package fixture
                object Main {
                    fun greeting(): String = "hello"
                }
                """.trimIndent(),
            )
        }
        val outputDir = workRoot.resolve("classes").apply { createDirectories() }
        val workingDir = workRoot.resolve("ic").apply { createDirectories() }

        val compiler = BtaIncrementalCompiler.create(btaImplJars).getOrElse {
            fail("failed to load BTA toolchain: $it")
        }

        compiler.compile(
            IcRequest(
                projectId = "cold-path-smoke",
                projectRoot = workRoot,
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        ).getOrElse { err ->
            fail("expected success, got Err($err)")
        }

        val classFiles = outputDir.walk().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "expected at least one .class under $outputDir")
        assertTrue(classFiles.any { it.fileName.toString() == "Main.class" }, "expected fixture.Main.class in output: $classFiles")

        // ADR 0019 §Negative follow-up — IC reaper coordination: the
        // cold path must drop a `project.path` breadcrumb inside
        // workingDir pointing at the request's projectRoot, so the
        // reaper can distinguish live projectId dirs from stale ones
        // after a project is moved or deleted.
        val breadcrumb = workingDir.resolve("project.path")
        assertTrue(breadcrumb.exists(), "expected project.path breadcrumb at $breadcrumb")
        assertEquals(workRoot.toString(), breadcrumb.readText().trim())

        // #199: LOCK must exist under workingDir after compile and its
        // mtime must be no later than the breadcrumb's — a literal
        // swap of the write order back to breadcrumb-first would flip
        // this. Strict `<` would be flaky on WSL2 9p (1s mtime
        // granularity); `<=` catches the swap without false positives
        // on fast filesystems.
        val lock = workingDir.resolve("LOCK")
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
        val sourceFile = workRoot.resolve("Broken.kt").also {
            it.writeText(
                """
                package fixture
                fun broken(): Int = "not an int"
                """.trimIndent(),
            )
        }
        val outputDir = workRoot.resolve("classes").apply { createDirectories() }
        val workingDir = workRoot.resolve("ic").apply { createDirectories() }

        val compiler = BtaIncrementalCompiler.create(btaImplJars).getOrElse {
            fail("failed to load BTA toolchain: $it")
        }

        val err = compiler.compile(
            IcRequest(
                projectId = "cold-path-err",
                projectRoot = workRoot,
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        ).getError() ?: fail("expected Err for broken source")

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
            joined.contains("Broken.kt", ignoreCase = true) || joined.contains("type mismatch", ignoreCase = true),
            "expected the captured message to reference the broken source or the type error, got:\n$joined",
        )
        assertTrue(
            !joined.contains("kotlinc reported COMPILATION_ERROR"),
            "stub fallback must not fire when real diagnostics were captured, got:\n$joined",
        )
    }

    // Acceptance criterion 4 of issue #112: a kolt.toml with one enabled plugin
    // entry must drive the translation path all the way through to the BTA layer,
    // even if the actual plugin jars cannot be resolved or the compile fails at
    // plugin load. This test proves that (a) the injected resolver is consulted,
    // and (b) the adapter still reaches BtaIncrementalCompiler.executeCompile's
    // session.executeOperation() call — i.e. the translation is wired through
    // to the compile operation, not short-circuited before BTA sees it.
    //
    // A fake plugin alias is used so the test stays independent of whether a
    // real kotlinx-serialization-compiler-plugin jar is resolvable in the Gradle
    // test task's environment. Real plugin validation (actually compiling a
    // @Serializable class) is B-2c's scope.
    @Test
    fun `kotlin_plugins section in kolt_toml reaches the compile path via the resolver`() {
        val workRoot = Files.createTempDirectory("bta-plugins-")
        workRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"

            [kotlin]
            version = "2.3.20"

            [kotlin.plugins]
            serialization = true

            [build]
            target = "jvm"
            main = "fixture.Main"
            sources = ["."]
            """.trimIndent(),
        )
        workRoot.resolve("Main.kt").writeText(
            """
            package fixture
            object Main {
                fun greeting(): String = "hi"
            }
            """.trimIndent(),
        )
        val outputDir = workRoot.resolve("classes").apply { createDirectories() }
        val workingDir = workRoot.resolve("ic").apply { createDirectories() }

        val resolverCalls = mutableListOf<String>()
        val compiler = BtaIncrementalCompiler.create(
            btaImplJars = btaImplJars,
            pluginJarResolver = { alias ->
                resolverCalls += alias
                // Return an empty classpath on purpose: the point of this test is
                // to prove the translation path is reached, not to successfully
                // load a real plugin. Post-#148 the translator collapses an
                // empty resolution to zero `-Xplugin=` freeArgs, so the compile
                // simply proceeds without the plugin attached. The assertion
                // that matters here is that the resolver was consulted at all.
                emptyList()
            },
        ).getOrElse { fail("failed to load BTA toolchain: $it") }

        val result = compiler.compile(
            IcRequest(
                projectId = "plugin-path-smoke",
                projectRoot = workRoot,
                sources = listOf(workRoot.resolve("Main.kt")),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        )

        // Primary assertion: the injected resolver was consulted for the
        // `serialization` alias. Without this, the translation path never
        // reached BTA; the compile outcome is a secondary signal.
        assertEquals(
            listOf("serialization"),
            resolverCalls,
            "expected PluginTranslator to consult the resolver for the `serialization` alias",
        )

        // Secondary assertion: the compile reaches a well-formed Result<,>
        // (Ok or Err) — i.e., the adapter did not crash on a thrown exception
        // when handed an empty-classpath plugin. Either outcome is acceptable
        // because B-2a only requires that the translation path is exercised.
        val outcome = result.mapBoth(
            success = { "SUCCESS" },
            failure = { err -> "Err(${err::class.simpleName})" },
        )
        // This assert is documentation for a future reader: both outcomes are
        // legal. A test failure would mean `compile` threw past the adapter,
        // which IS an ADR 0019 §7 invariant violation.
        assertTrue(
            outcome == "SUCCESS" ||
                outcome == "Err(CompilationFailed)" ||
                outcome == "Err(InternalError)",
            "unexpected compile outcome: $outcome",
        )
    }

    private fun systemClasspath(key: String): List<Path> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set — check :ic/build.gradle.kts test task config")
        return raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
    }
}
