package bench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsolatingCompileDriverTest {
    @Test
    fun `compiles a single source file in an isolating classloader`() {
        val jars = classpathFromSystemProperty("kotlinc.classpath")
        val fixtureCp = classpathFromSystemProperty("fixture.classpath")
        val driver = IsolatingCompileDriver(jars, fixtureCp)

        // Relative path: Gradle runs tests with working dir = spike/compile-bench/.
        val source = File("fixtures/Hello.kt").absoluteFile
        assertTrue(source.exists(), "fixture missing: ${source.path}")

        val outputDir = createTempOutputDir()
        val result = driver.compile(listOf(source), outputDir)

        assertEquals(CompileResult.Ok, result, "compile should succeed")
        val expected = outputDir.resolve("fixtures/HelloKt.class")
        assertTrue(expected.exists(), "expected class file at ${expected.path}")
    }

    private fun classpathFromSystemProperty(key: String): List<File> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set (run via Gradle)")
        return raw.split(File.pathSeparatorChar).map(::File)
    }

    private fun createTempOutputDir(): File {
        val dir = File.createTempFile("bench-out-", "").apply { delete() }
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
