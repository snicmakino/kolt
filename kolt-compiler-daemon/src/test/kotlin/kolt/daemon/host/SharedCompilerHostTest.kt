package kolt.daemon.host

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedCompilerHostTest {

    private lateinit var workDir: File

    private val compilerJars: List<File> =
        System.getProperty("kolt.daemon.compilerJars")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }

    private val stdlibJars: List<String> =
        System.getProperty("kolt.daemon.stdlibJars")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("shared-compiler-host-").toFile()
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun `compiles a trivial source file and produces class output`() {
        val src = File(workDir, "Hello.kt").apply {
            writeText(
                """
                package demo
                object Hello {
                    fun greet(): String = "hi"
                }
                """.trimIndent(),
            )
        }
        val outDir = File(workDir, "out").apply { mkdirs() }

        val host = SharedCompilerHost.create(compilerJars).get()
        assertNotNull(host)
        val result = host.compile(
            CompileRequest(
                sources = listOf(src.absolutePath),
                classpath = stdlibJars,
                outputPath = outDir.absolutePath,
                moduleName = "test",
                extraArgs = listOf("-no-stdlib", "-no-reflect"),
            ),
        )

        val outcome = result.get()
        assertNotNull(outcome, "expected Ok, got ${result.getError()}")
        assertEquals(0, outcome.exitCode)
        val classFile = File(outDir, "demo/Hello.class")
        assertTrue(classFile.exists(), "expected generated class file at ${classFile.absolutePath}")
    }

    @Test
    fun `reports non-zero exit code for a source with type errors`() {
        val src = File(workDir, "Broken.kt").apply {
            writeText(
                """
                package demo
                object Broken {
                    fun bad(): String = nonExistent()
                }
                """.trimIndent(),
            )
        }
        val outDir = File(workDir, "out").apply { mkdirs() }

        val host = SharedCompilerHost.create(compilerJars).get()
        assertNotNull(host)
        val result = host.compile(
            CompileRequest(
                sources = listOf(src.absolutePath),
                classpath = stdlibJars,
                outputPath = outDir.absolutePath,
                moduleName = "test",
                extraArgs = listOf("-no-stdlib", "-no-reflect"),
            ),
        )

        val outcome = result.get()
        assertNotNull(outcome)
        assertTrue(outcome.exitCode != 0, "expected non-zero exit code, got ${outcome.exitCode}")
    }

    @Test
    fun `create returns LoaderInitFailed when compiler jars are empty`() {
        val err = SharedCompilerHost.create(emptyList()).getError()
        assertNotNull(err)
        assertIs<CompileHostError.LoaderInitFailed>(err)
    }

    @Test
    fun `reuses a single URLClassLoader across multiple compiles`() {
        val host = SharedCompilerHost.create(compilerJars).get()
        assertNotNull(host)

        repeat(3) { i ->
            val src = File(workDir, "Loop$i.kt").apply {
                writeText(
                    """
                    package demo
                    object Loop$i { fun v(): Int = $i }
                    """.trimIndent(),
                )
            }
            val outDir = File(workDir, "out$i").apply { mkdirs() }

            val outcome = host.compile(
                CompileRequest(
                    sources = listOf(src.absolutePath),
                    classpath = stdlibJars,
                    outputPath = outDir.absolutePath,
                    moduleName = "test",
                    extraArgs = listOf("-no-stdlib", "-no-reflect"),
                ),
            ).get()
            assertNotNull(outcome)
            assertEquals(0, outcome.exitCode, "iteration $i failed")
        }
    }
}
