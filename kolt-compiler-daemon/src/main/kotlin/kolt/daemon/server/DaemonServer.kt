package kolt.daemon.server

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import kolt.daemon.host.CompileRequest
import kolt.daemon.host.CompilerHost
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
    private val host: CompilerHost,
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
        val result = host.compile(
            CompileRequest(
                sources = request.sources,
                classpath = request.classpath,
                outputPath = request.outputPath,
                moduleName = request.moduleName,
                extraArgs = request.extraArgs,
            ),
        )
        val reply: Message = result.mapBoth(
            success = { outcome ->
                Message.CompileResult(
                    exitCode = outcome.exitCode,
                    diagnostics = emptyList<Diagnostic>(),
                    stdout = outcome.stdout,
                    stderr = outcome.stderr,
                )
            },
            failure = { err ->
                Message.CompileResult(
                    exitCode = 2,
                    diagnostics = emptyList(),
                    stdout = "",
                    stderr = "compile host error: $err",
                )
            },
        )
        return FrameCodec.writeFrame(output, reply)
    }

    private fun heapUsedBytes(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
