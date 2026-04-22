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
            wipe = { wipes.incrementAndGet(); emptyList() },
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
            wipe = { wipes.incrementAndGet(); emptyList() },
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
            wipe = { path -> wipedPaths.add(path); emptyList() },
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
            wipe = { wipes.incrementAndGet(); emptyList() },
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
            wipe = { emptyList() },
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
            wipe = { wipes.incrementAndGet(); emptyList() },
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
            wipe = { emptyList() },
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
    fun `partial wipe records ic_self_heal_wipe_failed but still runs the retry`() {
        val metrics = RecordingMetricsSink()
        val calls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt")))
                else -> Ok(IcResponse(wallMillis = 1, compiledFileCount = 0))
            }
        }
        val stuckPaths = listOf(
            workingDir.resolve("locked-1.bin"),
            workingDir.resolve("locked-2.bin"),
        )
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { stuckPaths },  // simulate two files that could not be deleted
            metrics = metrics,
            stderrWarn = { },
        )

        // Retry still runs and succeeds even though the wipe reported leftovers.
        wrapper.compile(request()).get() ?: error("expected success after partial-wipe retry")

        assertEquals(2, calls.get(), "delegate must still be retried after a partial wipe")
        val selfHealCount = metrics.events.count { it.first == "ic.self_heal" }
        val wipeFailedCount = metrics.events.count { it.first == "ic.self_heal_wipe_failed" }
        assertEquals(1, selfHealCount, "ic.self_heal still fires once per retry cycle")
        assertEquals(1, wipeFailedCount, "ic.self_heal_wipe_failed is emitted once per partial wipe")
        // The value of the `ic.self_heal_wipe_failed` metric is the
        // leftover count so `kolt doctor` can distinguish "one stuck
        // file" from "wipe was completely ineffective".
        val wipeFailedEvent = metrics.events.single { it.first == "ic.self_heal_wipe_failed" }
        assertEquals(stuckPaths.size.toLong(), wipeFailedEvent.second)
    }

    @Test
    fun `thrown wipe is absorbed into ic_self_heal_wipe_failed and retry still runs`() {
        val metrics = RecordingMetricsSink()
        val calls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            when (calls.incrementAndGet()) {
                1 -> com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt")))
                else -> Ok(IcResponse(wallMillis = 1, compiledFileCount = 0))
            }
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { throw java.io.UncheckedIOException(java.io.IOException("wipe blew up")) },
            metrics = metrics,
            stderrWarn = { },
        )

        wrapper.compile(request()).get() ?: error("expected success after wipe-throw retry")

        assertEquals(2, calls.get(), "retry must still run even when the wipe throws")
        val wipeFailedEvent = metrics.events.single { it.first == "ic.self_heal_wipe_failed" }
        assertEquals(
            1L,
            wipeFailedEvent.second,
            "a thrown wipe is recorded as a single leftover (the workingDir root)",
        )
    }

    @Test
    fun `VirtualMachineError thrown by wipe still propagates past the self-heal path`() {
        val delegate = FakeCompiler { _ ->
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { throw OutOfMemoryError("synthetic OOM during wipe") },
            metrics = RecordingMetricsSink(),
            stderrWarn = { },
        )

        val thrown = kotlin.test.assertFailsWith<OutOfMemoryError> { wrapper.compile(request()) }
        assertEquals("synthetic OOM during wipe", thrown.message)
    }

    @Test
    fun `consecutive InternalError on the same project skips the retry and emits skipped metric`() {
        // Regression guard for issue #117 part 2: when the underlying
        // bug is persistent (e.g. a client-side source-path mistake),
        // every request to the same project should NOT burn a fresh
        // wipe+retry cycle. The first request pays the self-heal; the
        // second surfaces the error directly without touching the
        // workingDir.
        val metrics = RecordingMetricsSink()
        val calls = AtomicInteger(0)
        val wipeCalls = AtomicInteger(0)
        val delegate = FakeCompiler { _ ->
            calls.incrementAndGet()
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("persistent failure")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { wipeCalls.incrementAndGet(); emptyList() },
            metrics = metrics,
            stderrWarn = { },
        )
        val req = request()

        // Request 1: first failure fires a self-heal (wipe + retry).
        wrapper.compile(req)
        assertEquals(2, calls.get(), "first request: one initial attempt + one retry")
        assertEquals(1, wipeCalls.get())
        assertEquals(1, metrics.events.count { it.first == "ic.self_heal" })
        assertEquals(0, metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" })

        // Request 2: same project, same error. The latch is set from
        // request 1, so no wipe, no retry, no ic.self_heal — just the
        // skipped counter.
        wrapper.compile(req)
        assertEquals(3, calls.get(), "second request: one attempt, no retry")
        assertEquals(1, wipeCalls.get(), "wipe must NOT fire on the consecutive failure")
        assertEquals(1, metrics.events.count { it.first == "ic.self_heal" })
        assertEquals(1, metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" })

        // Request 3: same project, same error. Latch stays set, skip again.
        wrapper.compile(req)
        assertEquals(4, calls.get())
        assertEquals(1, wipeCalls.get())
        assertEquals(2, metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" })
    }

    @Test
    fun `a successful compile clears the self-heal latch so a later InternalError still self-heals`() {
        val metrics = RecordingMetricsSink()
        val sequence = listOf<Result<IcResponse, IcError>>(
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt 1"))),
            // retry of request 1 — succeeds
            Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)),
            // request 2 — healthy
            Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)),
            // request 3 — InternalError again; latch was cleared by
            // the prior success so this one should self-heal again
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt 2"))),
            // retry of request 3 — succeeds
            Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)),
        )
        val idx = AtomicInteger(0)
        val delegate = FakeCompiler { _ -> sequence[idx.getAndIncrement()] }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { emptyList() },
            metrics = metrics,
            stderrWarn = { },
        )
        val req = request()

        wrapper.compile(req)  // request 1 — self-heal fires
        wrapper.compile(req)  // request 2 — success, clears latch
        wrapper.compile(req)  // request 3 — self-heal fires again

        assertEquals(2, metrics.events.count { it.first == "ic.self_heal" })
        assertEquals(
            0,
            metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" },
            "a successful compile between the two failures must reset the latch",
        )
    }

    @Test
    fun `CompilationFailed between two InternalErrors also clears the latch`() {
        // User type errors are unrelated to cache corruption, so a
        // CompilationFailed should not keep the latch set from a
        // prior self-heal. Otherwise a developer who hits an
        // InternalError, then a syntax error, then another
        // InternalError (e.g. intermittent BTA bug) would lose the
        // self-heal on the third request.
        val metrics = RecordingMetricsSink()
        val sequence = listOf<Result<IcResponse, IcError>>(
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt 1"))),
            Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)),  // retry succeeds
            com.github.michaelbull.result.Err(IcError.CompilationFailed(listOf("Main.kt: error"))),
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt 2"))),
            Ok(IcResponse(wallMillis = 1, compiledFileCount = 0)),  // retry succeeds
        )
        val idx = AtomicInteger(0)
        val delegate = FakeCompiler { _ -> sequence[idx.getAndIncrement()] }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { emptyList() },
            metrics = metrics,
            stderrWarn = { },
        )
        val req = request()

        wrapper.compile(req)  // InternalError → self-heal → SUCCESS
        wrapper.compile(req)  // CompilationFailed — clears latch
        wrapper.compile(req)  // InternalError → self-heal fires again

        assertEquals(2, metrics.events.count { it.first == "ic.self_heal" })
        assertEquals(
            0,
            metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" },
        )
    }

    @Test
    fun `different projects keep independent self-heal latches`() {
        val metrics = RecordingMetricsSink()
        val delegate = FakeCompiler { _ ->
            com.github.michaelbull.result.Err(IcError.InternalError(RuntimeException("corrupt")))
        }
        val wrapper = SelfHealingIncrementalCompiler(
            delegate = delegate,
            wipe = { emptyList() },
            metrics = metrics,
            stderrWarn = { },
        )
        val projectA = IcRequest(
            projectId = "project-a",
            projectRoot = tmpRoot,
            sources = emptyList(),
            classpath = emptyList(),
            outputDir = tmpRoot.resolve("out-a"),
            workingDir = workingDir,
        )
        val projectB = projectA.copy(projectId = "project-b")

        wrapper.compile(projectA)  // A self-heals
        wrapper.compile(projectB)  // B independently self-heals
        wrapper.compile(projectA)  // A skipped
        wrapper.compile(projectB)  // B skipped

        assertEquals(
            2,
            metrics.events.count { it.first == "ic.self_heal" },
            "each project gets exactly one self-heal on first InternalError",
        )
        assertEquals(
            2,
            metrics.events.count { it.first == "ic.self_heal_skipped_consecutive" },
            "consecutive failure on each project is skipped independently",
        )
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
