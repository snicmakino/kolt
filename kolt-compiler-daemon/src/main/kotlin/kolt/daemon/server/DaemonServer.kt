package kolt.daemon.server

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import kolt.daemon.ic.IcError
import kolt.daemon.ic.IcRequest
import kolt.daemon.ic.IcResponse
import kolt.daemon.ic.IncrementalCompiler
import kolt.daemon.ic.Status
import kolt.daemon.protocol.Diagnostic
import kolt.daemon.protocol.FrameCodec
import kolt.daemon.protocol.FrameError
import kolt.daemon.protocol.Message
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface ExitReason {
    data object Shutdown : ExitReason
    data object MaxCompilesReached : ExitReason
    data object IdleTimeout : ExitReason
    data object HeapWatermarkReached : ExitReason
}

sealed interface DaemonError {
    data class BindFailed(val reason: String) : DaemonError
    data class SocketCleanupFailed(val reason: String) : DaemonError
}

class DaemonServer(
    private val socketPath: Path,
    // Daemon core talks to the incremental compiler adapter exclusively through
    // this interface (defined in the :ic subproject). ADR 0019 §3 requires that
    // no daemon-core file imports a type from `kotlin.build.tools.*`; the
    // IncrementalCompiler / IcRequest / IcResponse / IcError surface is the sole
    // contract we are permitted to see. If a future change adds a BTA-typed
    // method here, that is the signal that the adapter has leaked.
    private val compiler: IncrementalCompiler,
    private val config: DaemonConfig = DaemonConfig(),
) {
    private val stopRequested = AtomicBoolean(false)
    private val compilesServed = AtomicInteger(0)
    private val lastActivityNanos = AtomicLong(System.nanoTime())
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var exitReason: ExitReason? = null

    fun serve(): Result<ExitReason, DaemonError> {
        val parent = socketPath.parent
        if (parent != null) {
            try {
                Files.createDirectories(parent)
            } catch (e: IOException) {
                return Err(DaemonError.BindFailed("createDirectories($parent) failed: ${e.message}"))
            }
        }
        try {
            Files.deleteIfExists(socketPath)
        } catch (e: IOException) {
            return Err(DaemonError.BindFailed("deleteIfExists($socketPath) failed: ${e.message}"))
        }

        val server = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                it.bind(UnixDomainSocketAddress.of(socketPath))
            }
        } catch (e: IOException) {
            return Err(DaemonError.BindFailed("bind($socketPath) failed: ${e.message}"))
        }

        serverChannel = server
        val watchdog = startWatchdog()
        try {
            server.use {
                while (!stopRequested.get()) {
                    val client = try {
                        server.accept()
                    } catch (_: ClosedChannelException) {
                        break
                    }
                    lastActivityNanos.set(System.nanoTime())
                    client.use { handleConnection(it) }
                    lastActivityNanos.set(System.nanoTime())
                }
            }
        } finally {
            watchdog.interrupt()
            runCatching { Files.deleteIfExists(socketPath) }
        }
        return Ok(exitReason ?: ExitReason.Shutdown)
    }

    fun stop() {
        requestExit(ExitReason.Shutdown)
    }

    private fun requestExit(reason: ExitReason) {
        if (stopRequested.compareAndSet(false, true)) {
            exitReason = reason
        }
        runCatching { serverChannel?.close() }
    }

    private fun startWatchdog(): Thread =
        Thread({
            while (!stopRequested.get()) {
                val idleMs = (System.nanoTime() - lastActivityNanos.get()) / 1_000_000
                if (idleMs >= config.idleTimeoutMillis) {
                    requestExit(ExitReason.IdleTimeout)
                    return@Thread
                }
                val sleepMs = (config.idleTimeoutMillis - idleMs).coerceAtLeast(10).coerceAtMost(500)
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "daemon-watchdog").apply {
            isDaemon = true
            start()
        }

    private fun handleConnection(client: SocketChannel) {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)
        while (!stopRequested.get()) {
            val message = FrameCodec.readFrame(input).getOrElse { err ->
                if (err !is FrameError.Eof) {
                    writeProtocolError(output, "protocol error: $err")
                }
                return
            }
            when (message) {
                is Message.Ping -> {
                    if (FrameCodec.writeFrame(output, Message.Pong).isErr) return
                }
                is Message.Shutdown -> {
                    requestExit(ExitReason.Shutdown)
                    return
                }
                is Message.Compile -> {
                    if (handleCompile(message, output).isErr) return
                    val served = compilesServed.incrementAndGet()
                    if (served >= config.maxCompiles) {
                        requestExit(ExitReason.MaxCompilesReached)
                        return
                    }
                    if (heapUsedBytes() >= config.heapWatermarkBytes) {
                        requestExit(ExitReason.HeapWatermarkReached)
                        return
                    }
                }
                is Message.Pong, is Message.CompileResult -> {
                    writeProtocolError(
                        output,
                        "protocol error: client sent server-only message ${message::class.simpleName}",
                    )
                    return
                }
            }
        }
    }

    private fun writeProtocolError(output: OutputStream, stderr: String) {
        FrameCodec.writeFrame(
            output,
            Message.CompileResult(
                exitCode = 2,
                diagnostics = emptyList(),
                stdout = "",
                stderr = stderr,
            ),
        )
    }

    private fun handleCompile(
        request: Message.Compile,
        output: OutputStream,
    ): Result<Unit, FrameError> {
        val icRequest = toIcRequest(request)
        val reply: Message = compiler.compile(icRequest).mapBoth(
            success = { icResponse -> icResponseToReply(icResponse) },
            failure = { icError -> icErrorToReply(icError) },
        )
        return FrameCodec.writeFrame(output, reply)
    }

    private fun toIcRequest(request: Message.Compile): IcRequest {
        val projectRoot = Path.of(request.workingDir)
        return IcRequest(
            // ADR 0019 §5/§6: projectId is a stable sha256-of-projectRoot hash.
            // Deriving it here keeps the wire protocol unchanged (Message.Compile
            // stays the Phase A shape) while still letting the adapter key IC
            // state by project. B-2a does not use IC state yet, but the id flows
            // through into BTA's module name, so B-2b does not need to retrofit.
            projectId = projectIdFor(request.workingDir),
            projectRoot = projectRoot,
            sources = request.sources.map { Path.of(it) },
            classpath = request.classpath.map { Path.of(it) },
            // The native client's `outputPath` is the `build/classes` directory
            // (see `CLASSES_DIR` in BuildCommands.kt); BTA's jvmCompilationOperationBuilder
            // expects a directory Path. The test fixture `out.jar` string in
            // DaemonLifecycleTest was just a shape-only label and never reached
            // a real compile.
            outputDir = Path.of(request.outputPath),
            // B-2a does not materialise IC state under workingDir — see ADR 0019 §5.
            // A per-project directory under `~/.kolt/daemon/ic/<kotlin>/<hash>/`
            // arrives in B-2b; until then, pass a placeholder that BTA would
            // never touch because IC configuration is not attached (see
            // BtaIncrementalCompiler.executeCompile). The placeholder must
            // still be a valid Path to satisfy IcRequest's type, so we reuse
            // projectRoot as a harmless non-null value.
            workingDir = projectRoot,
        )
    }

    private fun icResponseToReply(response: IcResponse): Message.CompileResult =
        Message.CompileResult(
            exitCode = if (response.status == Status.SUCCESS) 0 else 1,
            diagnostics = emptyList<Diagnostic>(),
            stdout = "",
            stderr = "",
        )

    private fun icErrorToReply(error: IcError): Message.CompileResult = when (error) {
        is IcError.CompilationFailed -> Message.CompileResult(
            exitCode = 1,
            diagnostics = emptyList(),
            stdout = "",
            stderr = error.messages.joinToString(separator = "\n"),
        )
        // ADR 0019 §7: InternalError is a daemon-internal concern — the user
        // sees "failed to compile", not "the incremental cache was corrupt".
        // B-2b's self-heal wipe+retry fires on this variant; B-2a does not
        // retry, so we surface the cause message directly. The native client
        // has its own FallbackCompilerBackend (ADR 0016 §5) for the
        // "daemon JVM itself is broken" case.
        is IcError.InternalError -> Message.CompileResult(
            exitCode = 2,
            diagnostics = emptyList(),
            stdout = "",
            stderr = "compile adapter error: ${error.cause.message ?: error.cause.javaClass.name}",
        )
    }

    private fun projectIdFor(workingDir: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(workingDir.toByteArray(Charsets.UTF_8))
        // Short hex prefix is enough: Gradle's Kotlin plugin also uses a short
        // project id and the stability is what matters (same workingDir →
        // same IC state under `~/.kolt/daemon/ic/...` once B-2b wires it).
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun heapUsedBytes(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
