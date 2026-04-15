package kolt.daemon.server

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import kolt.daemon.ic.IcError
import kolt.daemon.ic.IcRequest
import kolt.daemon.ic.IcResponse
import kolt.daemon.ic.IcStateLayout
import kolt.daemon.ic.IncrementalCompiler
import kolt.daemon.protocol.Diagnostic
import kolt.daemon.protocol.DiagnosticParser
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
    // Daemon core talks to the incremental compiler adapter exclusively through
    // this interface (defined in the :ic subproject). ADR 0019 §3 requires that
    // no daemon-core file imports a type from `kotlin.build.tools.*`; the
    // IncrementalCompiler / IcRequest / IcResponse / IcError surface is the sole
    // contract we are permitted to see. If a future change adds a BTA-typed
    // method here, that is the signal that the adapter has leaked.
    private val compiler: IncrementalCompiler,
    // Root of daemon-owned IC state, per ADR 0019 §5. Typically
    // `~/.kolt/daemon/ic/`. Wired in by Main.kt so tests can redirect it
    // to a temp dir. The daemon is the sole writer under this root.
    private val icRoot: Path,
    // Kotlin compiler version segment under `icRoot`. Must move in
    // lockstep with the BTA artifact pin (ADR 0019 §1); Main.kt holds
    // the single source of truth.
    private val kotlinVersion: String,
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
        // ADR 0019 §5 / §6: projectId and workingDir are both derived from
        // projectRoot through `IcStateLayout`, so they stay consistent
        // regardless of how the wire `Message.Compile` is shaped. B-2a
        // held a `workingDir = projectRoot` placeholder here; B-2b retires
        // that by pointing workingDir at daemon-owned state under
        // `icRoot / kotlinVersion / sha256(projectRoot)`.
        //
        // ADR 0019 §9 + #65: `request.extraArgs` is intentionally NOT
        // forwarded. The native client packs `-jvm-target N` and the
        // legacy `-Xplugin=...` plugin args into `extraArgs` for the
        // subprocess fallback path, which execs raw kotlinc and needs
        // them. The daemon path uses structured channels instead:
        // - plugins flow through `--plugin-jars` → `pluginJarResolver` →
        //   `PluginTranslator` → `COMPILER_PLUGINS` (BTA's structured
        //   plugin list).
        // - jvm target is hard-coded by the daemon's BTA setup.
        // Honouring `extraArgs` here would re-attach `-Xplugin=` as a
        // free-text compiler arg on top of the structured `CompilerPlugin`
        // list, double-loading the plugin classpath. If a future change
        // wants to forward subset args, it MUST filter `-Xplugin=` first
        // (or the native client must stop emitting it on the daemon path).
        return IcRequest(
            projectId = IcStateLayout.projectIdFor(projectRoot),
            projectRoot = projectRoot,
            sources = request.sources.map { Path.of(it) },
            classpath = request.classpath.map { Path.of(it) },
            // The native client's `outputPath` is the `build/classes` directory
            // (see `CLASSES_DIR` in BuildCommands.kt); BTA's jvmCompilationOperationBuilder
            // expects a directory Path. The test fixture `out.jar` string in
            // DaemonLifecycleTest was just a shape-only label and never reached
            // a real compile.
            outputDir = Path.of(request.outputPath),
            workingDir = IcStateLayout.workingDirFor(icRoot, kotlinVersion, projectRoot),
        )
    }

    private fun icResponseToReply(@Suppress("UNUSED_PARAMETER") response: IcResponse): Message.CompileResult =
        // `IcResponse` is success-only (see IncrementalCompiler.kt). All
        // failure variants arrive as `IcError` and are handled by
        // `icErrorToReply`. The parameter is retained so the `mapBoth`
        // caller stays symmetric with `icErrorToReply(IcError)`.
        Message.CompileResult(
            exitCode = 0,
            diagnostics = emptyList<Diagnostic>(),
            stdout = "",
            stderr = "",
        )

    private fun icErrorToReply(error: IcError): Message.CompileResult = when (error) {
        is IcError.CompilationFailed -> {
            // ADR 0019 §7 + B-2c: split BTA's flat error list into structured
            // `Diagnostic`s where the `path:L:C: severity: msg` shape matches,
            // and keep anything else as free-text stderr. The native client
            // renders `diagnostics` as an IDE-style error list and still
            // surfaces `stderr` for unparsable lines (e.g. BTA-internal stack
            // frames appended by CapturingKotlinLogger).
            val (diagnostics, plain) = DiagnosticParser.parseMessages(error.messages)
            Message.CompileResult(
                exitCode = 1,
                diagnostics = diagnostics,
                stdout = "",
                stderr = plain.joinToString(separator = "\n"),
            )
        }
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

    private fun heapUsedBytes(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
