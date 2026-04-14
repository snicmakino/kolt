@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getError
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

        val response = compiler.compile(
            IcRequest(
                projectId = "cold-path-smoke",
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        ).getOrElse { err ->
            fail("expected success, got Err($err)")
        }

        assertEquals(Status.SUCCESS, response.status)
        val classFiles = outputDir.walk().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "expected at least one .class under $outputDir")
        assertTrue(classFiles.any { it.fileName.toString() == "Main.class" }, "expected fixture.Main.class in output: $classFiles")
    }

    @Test
    fun `compilation error is reported as CompilationFailed not InternalError`() {
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
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
            ),
        ).getError() ?: fail("expected Err for broken source")

        assertTrue(err is IcError.CompilationFailed, "expected CompilationFailed, got $err")
    }

    private fun systemClasspath(key: String): List<Path> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set — check :ic/build.gradle.kts test task config")
        return raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
    }
}
