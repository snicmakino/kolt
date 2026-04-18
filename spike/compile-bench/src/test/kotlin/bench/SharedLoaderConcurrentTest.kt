package bench

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedLoaderConcurrentTest {
    @Test
    fun `concurrent compiles on a shared URLClassLoader all succeed`() {
        val jars = classpathFromSystemProperty("kotlinc.classpath")
        val fixtureCp = classpathFromSystemProperty("fixture.classpath")
        val driver = SharedLoaderCompileDriver(jars, fixtureCp)

        val rotating = File("fixtures/rotating").absoluteFile
            .listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?: error("missing rotating fixtures dir")

        val threadCount = 2
        val perThread = 5
        val start = CountDownLatch(1)
        val firstError = AtomicReference<Throwable?>(null)
        val results = Array<CompileResult?>(threadCount * perThread) { null }

        val threads = (0 until threadCount).map { idx ->
            Thread({
                start.await()
                for (i in 0 until perThread) {
                    val src = listOf(rotating[(idx * perThread + i) % rotating.size])
                    try {
                        results[idx * perThread + i] = driver.compile(src, freshOutputDir())
                    } catch (e: Throwable) {
                        val unwrapped = if (e is InvocationTargetException) (e.cause ?: e) else e
                        firstError.compareAndSet(null, unwrapped)
                    }
                }
            }, "shared-loader-concurrent-$idx").apply { isDaemon = true; start() }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertNull(firstError.get(), "no thread should throw during concurrent compile")
        results.forEachIndexed { i, r ->
            assertEquals(CompileResult.Ok, r, "compile $i should succeed, got $r")
        }
    }

    private fun classpathFromSystemProperty(key: String): List<File> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set (run via Gradle)")
        return raw.split(File.pathSeparatorChar).map(::File)
    }

    private fun freshOutputDir(): File {
        val d = File.createTempFile("bench-concurrent-", "").apply { delete() }
        d.mkdirs()
        d.deleteOnExit()
        return d
    }
}
