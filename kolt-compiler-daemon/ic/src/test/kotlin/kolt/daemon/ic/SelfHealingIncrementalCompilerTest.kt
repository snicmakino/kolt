package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SelfHealingIncrementalCompilerTest {

    private lateinit var tmpRoot: Path
    private lateinit var workingDir: Path

    @BeforeTest
    fun setUp() {
        tmpRoot = Files.createTempDirectory("kolt-ic-selfheal-")
        workingDir = tmpRoot.resolve("ic-state").also { Files.createDirectories(it) }
        Files.writeString(workingDir.resolve("marker.txt"), "stale")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tmpRoot.toFile().deleteRecursively() }
    }

    private fun request(): IcRequest = IcRequest(
        projectId = "deadbeef",
        projectRoot = tmpRoot,
        sources = emptyList(),
        classpath = emptyList(),
        outputDir = tmpRoot.resolve("out"),
        workingDir = workingDir,
    )

    @Test
    fun `success on first attempt passes through without retry or wipe`() {
        val calls = AtomicInteger(0)
        val wipes = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            calls.incrementAndGet()
            Ok(IcResponse(wallMillis = 5, compiledFileCount = 3))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { wipes.incrementAndGet() },
        )

        val result = wrapper.compile(request())

        val response = result.get() ?: error("expected Ok, got ${result.getError()}")
        assertEquals(5, response.wallMillis)
        assertEquals(1, calls.get())
        assertEquals(0, wipes.get())
        assertTrue(Files.exists(workingDir.resolve("marker.txt")), "marker.txt must survive when no wipe fires")
    }

    @Test
    fun `CompilationFailed is surfaced without retry or wipe`() {
        val calls = AtomicInteger(0)
        val wipes = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            calls.incrementAndGet()
            Err(IcError.CompilationFailed(listOf("Main.kt: error: unresolved reference")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { wipes.incrementAndGet() },
        )

        val result = wrapper.compile(request())

        val error = result.getError() ?: error("expected Err, got ${result.get()}")
        assertTrue(error is IcError.CompilationFailed)
        assertEquals(1, calls.get(), "CompilationFailed is a user error — no retry")
        assertEquals(0, wipes.get(), "CompilationFailed must not wipe the workingDir")
        assertTrue(Files.exists(workingDir.resolve("marker.txt")))
    }

    @Test
    fun `InternalError then SUCCESS wipes workingDir and retries transparently`() {
        val calls = AtomicInteger(0)
        val wipedPaths = mutableListOf<Path>()
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> Err(IcError.InternalError(RuntimeException("corrupt cache")))
                else -> Ok(IcResponse(wallMillis = 42, compiledFileCount = 7))
            }
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { path -> wipedPaths.add(path) },
        )

        val result = wrapper.compile(request())

        val response = result.getOrElse { error("expected Ok, got $it") }
        assertEquals(42, response.wallMillis)
        assertEquals(2, calls.get())
        assertEquals(listOf(workingDir), wipedPaths)
    }

    @Test
    fun `InternalError twice surfaces the retry's error, not the original`() {
        val calls = AtomicInteger(0)
        val wipes = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            val n = calls.incrementAndGet()
            Err(IcError.InternalError(RuntimeException("attempt-$n")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { wipes.incrementAndGet() },
        )

        val result = wrapper.compile(request())

        val error = result.getError() ?: error("expected Err, got ${result.get()}")
        assertTrue(error is IcError.InternalError)
        assertEquals("attempt-2", error.cause.message, "retry's error must overwrite the first attempt's error")
        assertEquals(2, calls.get())
        assertEquals(1, wipes.get(), "wipe fires exactly once regardless of retry outcome")
    }

    @Test
    fun `InternalError then CompilationFailed surfaces the retry's CompilationFailed`() {
        val calls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> Err(IcError.InternalError(RuntimeException("cache corrupt")))
                else -> Err(IcError.CompilationFailed(listOf("Retry.kt: error: type mismatch")))
            }
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { },
        )

        val result = wrapper.compile(request())

        val error = result.getError() ?: error("expected Err, got ${result.get()}")
        assertTrue(error is IcError.CompilationFailed)
        assertTrue(error.messages.single().contains("type mismatch"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `VirtualMachineError from the delegate propagates without wipe or retry`() {
        val calls = AtomicInteger(0)
        val wipes = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            calls.incrementAndGet()
            throw OutOfMemoryError("synthetic OOM")
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { wipes.incrementAndGet() },
        )

        val thrown = assertFailsWith<OutOfMemoryError> { wrapper.compile(request()) }
        assertEquals("synthetic OOM", thrown.message)
        assertEquals(1, calls.get(), "VirtualMachineError must not trigger retry")
        assertEquals(0, wipes.get(), "VirtualMachineError must not trigger wipe")
    }

    @Test
    fun `default filesystem wipe deletes the workingDir tree`() {
        val nested = workingDir.resolve("sub/nested")
        Files.createDirectories(nested)
        Files.writeString(nested.resolve("file.txt"), "junk")

        val calls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> Err(IcError.InternalError(RuntimeException("boom")))
                else -> Ok(IcResponse(wallMillis = 1, compiledFileCount = null))
            }
        }
        val wrapper = SelfHealingIncrementalCompiler(delegate)

        val result = wrapper.compile(request())

        val response = result.get() ?: error("expected Ok, got ${result.getError()}")
        assertEquals(1, response.wallMillis)
        assertEquals(2, calls.get())
        assertTrue(!Files.exists(workingDir.resolve("marker.txt")), "default wipe must delete the tree")
        assertTrue(!Files.exists(nested.resolve("file.txt")))
    }

    @Test
    fun `self-heal emits ic_self_heal metric and a single stderr warning`() {
        val metrics = RecordingMetricsSink()
        val warnings = mutableListOf<String>()
        val calls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt ic state")))
                else -> Ok(IcResponse(wallMillis = 1, compiledFileCount = 1))
            }
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { /* no-op wipe for metric-only focus */ },
            metrics = metrics,
            stderrWarn = { warnings.add(it) },
        )

        wrapper.compile(request()).get() ?: error("expected success")

        assertEquals(listOf("ic.self_heal" to 1L), metrics.events)
        assertEquals(1, warnings.size, "self-heal must emit exactly one stderr warning")
        assertTrue(warnings.single().contains("self-heal fired"))
        assertTrue(
            warnings.single().contains("corrupt ic state"),
            "warning must surface the underlying cause so a dogfood user can correlate it",
        )
    }

    @Test
    fun `no self-heal events when the first attempt succeeds`() {
        val metrics = RecordingMetricsSink()
        val warnings = mutableListOf<String>()
        val delegate = FakeCompiler { _ -> Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)) }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            metrics = metrics,
            stderrWarn = { warnings.add(it) },
        )

        wrapper.compile(request()).get() ?: error("expected success")

        assertTrue(metrics.events.isEmpty(), "no retry → no ic.self_heal metric")
        assertTrue(warnings.isEmpty(), "no retry → no stderr warning")
    }

    @Test
    fun `no self-heal events when the failure is a CompilationFailed`() {
        val metrics = RecordingMetricsSink()
        val delegate = FakeCompiler { _ ->
            com.github.michaelbull.result.Err(IcError.CompilationFailed(listOf("Main.kt: error")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            metrics = metrics,
        )

        wrapper.compile(request())

        assertTrue(metrics.events.isEmpty(), "CompilationFailed is a user error — no self-heal")
    }

    private class FakeCompiler(
        private val onCompile: (IcRequest) -> Result<IcResponse, IcError>,
    ) : IncrementalCompiler {
        override fun compile(request: IcRequest): Result<IcResponse, IcError> = onCompile(request)
    }

    private class RecordingMetricsSink : IcMetricsSink {
        val events: MutableList<Pair<String, Long>> = mutableListOf()
        override fun record(name: String, value: Long) {
            events += name to value
        }
    }
}
