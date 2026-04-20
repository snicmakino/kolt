package kolt.nativedaemon.server

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import kolt.nativedaemon.compiler.NativeCompileError
import kolt.nativedaemon.compiler.NativeCompileOutcome
import kolt.nativedaemon.compiler.NativeCompiler
import kolt.nativedaemon.protocol.FrameCodec
import kolt.nativedaemon.protocol.FrameError
import kolt.nativedaemon.protocol.Message
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
}

// Native compiler daemon server. Structurally parallels
// `kolt.daemon.server.DaemonServer` (ADR 0016): bind a Unix domain socket,
// run a single-threaded accept loop, dispatch one frame at a time, enforce
// idle / max-compiles / heap-watermark thresholds via a watchdog thread.
//
// ADR 0024 departure points from the JVM daemon:
// - No IC state management / preServeHook (§6 — konanc owns its own IC
//   cache on disk; there is no daemon-owned state to reap).
// - NativeCompile carries a flat args list; the server does not decompose
//   into structured fields because K2Native.exec's API is CLI-shaped (§4).
// - NativeCompileResult returns { exitCode, stderr } only — no stdout or
//   structured diagnostics (§4, "stderr blob").
class DaemonServer(
    private val socketPath: Path,
    private val compiler: NativeCompiler,
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
        }, "native-daemon-watchdog").apply {
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
                is Message.NativeCompile -> {
                    if (handleNativeCompile(message, output).isErr) return
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
                is Message.Pong, is Message.NativeCompileResult -> {
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
            Message.NativeCompileResult(exitCode = 2, stderr = stderr),
        )
    }

    private fun handleNativeCompile(
        request: Message.NativeCompile,
        output: OutputStream,
    ): Result<Unit, FrameError> {
        val reply: Message = compiler.compile(request.args).mapBoth(
            success = { outcome -> outcomeToReply(outcome) },
            failure = { error -> errorToReply(error) },
        )
        return FrameCodec.writeFrame(output, reply)
    }

    private fun outcomeToReply(outcome: NativeCompileOutcome): Message.NativeCompileResult =
        Message.NativeCompileResult(exitCode = outcome.exitCode, stderr = outcome.stderr)

    // ADR 0024 §7: genuine reflective failures (the URLClassLoader cracked,
    // K2Native.exec threw an uncaught exception) are surfaced to the client
    // as exitCode=2 with a descriptive stderr. This keeps the wire contract
    // uniform — every request gets a NativeCompileResult. Client-side fallback
    // (ADR 0024 §7, implemented in PR 3) keys off whether the daemon replied
    // at all, not off the exitCode; a reflective-error reply does NOT trigger
    // the subprocess fallback because the daemon itself is healthy.
    private fun errorToReply(error: NativeCompileError): Message.NativeCompileResult = when (error) {
        is NativeCompileError.InvocationFailed -> Message.NativeCompileResult(
            exitCode = 2,
            stderr = "native compiler invocation failed: ${error.cause.message ?: error.cause.javaClass.name}",
        )
    }

    private fun heapUsedBytes(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
