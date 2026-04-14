package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.CompileError
import kolt.build.CompileOutcome
import kolt.build.CompileRequest
import kolt.build.CompilerBackend
import kolt.daemon.wire.FrameCodec
import kolt.daemon.wire.FrameError
import kolt.daemon.wire.Message
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocket
import kolt.infra.net.UnixSocketError
import kolt.infra.spawnDetached
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.ECONNREFUSED
import platform.posix.ENOENT
import platform.posix.clock_gettime
import platform.posix.timespec
import platform.posix.usleep

/**
 * Warm JVM daemon path for compilation (see ADR 0016, issue #14).
 *
 * The first `compile()` call after a cold boot connects to (or spawns
 * and then connects to) a long-lived helper JVM that keeps the Kotlin
 * compiler's classloader warm. Subsequent compiles reuse the same
 * daemon and pay microseconds of IPC instead of seconds of JVM startup.
 *
 * This backend is **not** load-bearing for correctness. Every failure
 * mode — daemon spawn fails, daemon crashes mid-compile, socket path
 * is unreachable, reply is malformed — is mapped to a [CompileError]
 * that `FallbackCompilerBackend` is free to translate into a
 * subprocess fallback (S6/S7). The only error that is returned as
 * "don't fall back" is [CompileError.CompilationFailed]: that means
 * the daemon ran kotlinc and the user's Kotlin source did not
 * compile, and retrying with a subprocess kotlinc would produce the
 * same failure.
 *
 * ### Connect / spawn / retry policy
 *
 * 1. One optimistic `connect()`. If it succeeds, the daemon was
 *    already running — send the frame and return.
 * 2. If `connect()` fails with `ENOENT` (socket file absent) or
 *    `ECONNREFUSED` (no listener yet), the daemon is assumed to be
 *    down. Call [spawner] and enter the retry loop.
 * 3. If `connect()` fails with any other errno, the failure is fatal
 *    for this build — `EACCES`, `ENAMETOOLONG`, `EADDRNOTAVAIL` etc.
 *    all indicate environment or path problems that more retries
 *    will not resolve. [UnixSocketError.InvalidArgument] from path
 *    length validation is mapped to [CompileError.InternalMisuse]
 *    because it means kolt itself built a bad path.
 * 4. The retry loop uses exponential backoff (10 / 20 / 40 / ... ms,
 *    capped at 200 ms per iteration) inside a fixed total budget of
 *    [connectTotalBudgetMs] ms. The budget is wall-clock, not
 *    attempt-counted, so a laggy spawn does not burn the budget in
 *    one iteration and a fast spawn is not limited by a minimum
 *    retry count.
 *
 * ### Seams
 *
 * The constructor takes a [connector] and a [spawner] as defaulted
 * function parameters. In production both default to real
 * implementations ([UnixSocket.connect] wrapped in a
 * [SocketDaemonConnection] and [spawnDetached]); in tests they are
 * substituted with fakes so retry policy, spawn behaviour, and reply
 * mapping can be exercised without bringing up a real JVM daemon.
 * The same seam lets a future integration test swap in a real daemon
 * as a second configuration without duplicating the compile pipeline.
 */
class DaemonCompilerBackend internal constructor(
    private val javaBin: String,
    private val daemonJarPath: String,
    private val compilerJars: List<String>,
    private val socketPath: String,
    private val logPath: String? = null,
    private val connector: DaemonConnector = defaultDaemonConnector,
    private val spawner: DaemonSpawner = defaultDaemonSpawner,
    private val clockMs: () -> Long = ::monotonicMs,
    private val sleeper: (Int) -> Unit = defaultSleeper,
) : CompilerBackend {

    constructor(
        javaBin: String,
        daemonJarPath: String,
        compilerJars: List<String>,
        socketPath: String,
        logPath: String? = null,
    ) : this(
        javaBin = javaBin,
        daemonJarPath = daemonJarPath,
        compilerJars = compilerJars,
        socketPath = socketPath,
        logPath = logPath,
        connector = defaultDaemonConnector,
        spawner = defaultDaemonSpawner,
    )

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        val connection = connectOrSpawn().let { result ->
            result.getError()?.let { return Err(it) }
            result.get()!!
        }
        connection.use {
            val wire = request.toWireMessage()
            val sendErr = it.sendRequest(wire).getError()
            if (sendErr != null) return Err(mapFrameErrorToSendError(sendErr))
            val reply = it.receiveReply()
            val replyErr = reply.getError()
            if (replyErr != null) return Err(mapFrameErrorToReceiveError(replyErr))
            return mapReplyToOutcome(reply.get()!!)
        }
    }

    private fun connectOrSpawn(): Result<DaemonConnection, CompileError> {
        // Fast path: a daemon is already listening from a previous build.
        val first = connector(socketPath)
        first.getError()?.let { err ->
            if (!isRetryableConnectError(err)) {
                return Err(mapFatalConnectError(err))
            }
        } ?: return Ok(first.get()!!)

        // Daemon is (probably) not up — double-fork-exec and then
        // wait for it to `bind()` the socket. [spawnDetached] returns
        // as soon as the intermediate child exits, so we still need
        // the retry loop before the first real connect succeeds.
        val spawnErr = spawner(
            spawnArgv(),
            logPath,
        ).getError()
        if (spawnErr != null) {
            return Err(
                CompileError.BackendUnavailable.Other(
                    "daemon spawn failed: $spawnErr",
                ),
            )
        }

        return retryConnect()
    }

    private fun retryConnect(): Result<DaemonConnection, CompileError> {
        val start = clockMs()
        var delayMs = INITIAL_BACKOFF_MS
        var lastErr: UnixSocketError? = null
        while (true) {
            sleeper(delayMs)
            val attempt = connector(socketPath)
            attempt.getError()?.let { err ->
                lastErr = err
                if (!isRetryableConnectError(err)) {
                    return Err(mapFatalConnectError(err))
                }
            } ?: return Ok(attempt.get()!!)
            if (clockMs() - start >= connectTotalBudgetMs) {
                return Err(
                    CompileError.BackendUnavailable.Other(
                        "daemon did not accept a connection on $socketPath within " +
                            "${connectTotalBudgetMs}ms (last: ${describe(lastErr)})",
                    ),
                )
            }
            delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private fun spawnArgv(): List<String> = buildList {
        add(javaBin)
        add("-jar")
        add(daemonJarPath)
        add("--socket")
        add(socketPath)
        add("--compiler-jars")
        // JVM `kolt-compiler-daemon` CLI splits this on `File.pathSeparator`,
        // which on Linux is ':'. Hard-code rather than pull in a platform
        // detect: kolt is linuxX64-only today (see CLAUDE.md non-goals).
        add(compilerJars.joinToString(":"))
    }

    companion object {
        internal const val INITIAL_BACKOFF_MS = 10
        internal const val MAX_BACKOFF_MS = 200
        internal const val connectTotalBudgetMs = 3000L
    }
}

/**
 * Frame-level daemon endpoint. Extracted as an internal seam so tests
 * can replace real socket I/O with a fake that returns pre-canned
 * [Message] responses, and so the compile pipeline does not need to
 * know about [UnixSocket] directly.
 */
internal interface DaemonConnection : AutoCloseable {
    fun sendRequest(message: Message): Result<Unit, FrameError>
    fun receiveReply(): Result<Message, FrameError>
}

internal class SocketDaemonConnection(private val socket: UnixSocket) : DaemonConnection {
    override fun sendRequest(message: Message): Result<Unit, FrameError> =
        FrameCodec.writeFrame(socket, message)

    override fun receiveReply(): Result<Message, FrameError> =
        FrameCodec.readFrame(socket)

    override fun close() {
        socket.close()
    }
}

internal typealias DaemonConnector = (String) -> Result<DaemonConnection, UnixSocketError>
internal typealias DaemonSpawner = (List<String>, String?) -> Result<Unit, ProcessError>

internal val defaultDaemonConnector: DaemonConnector = { path ->
    val socketResult = UnixSocket.connect(path)
    val err = socketResult.getError()
    if (err != null) Err(err) else Ok(SocketDaemonConnection(socketResult.get()!!))
}

internal val defaultDaemonSpawner: DaemonSpawner = { argv, logPath -> spawnDetached(argv, logPath) }

internal val defaultSleeper: (Int) -> Unit = { ms -> usleep((ms * 1000).toUInt()) }

// Monotonic milliseconds, used as the retry-budget clock. TimeSource
// would have required a captured TimeMark and could not be swapped in
// tests without an extra indirection; clock_gettime returns an
// absolute monotonic value that is trivial to fake.
@OptIn(ExperimentalForeignApi::class)
internal fun monotonicMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

// ENOENT: socket file does not exist yet (daemon has not bound).
// ECONNREFUSED: socket file exists but nobody is listening (daemon is
// shutting down mid-bind, or crashed between cleanup and rebind). Both
// are expected transients in the spawn window; all other errnos are
// treated as environment failures and short-circuit.
internal fun isRetryableConnectError(err: UnixSocketError): Boolean {
    if (err !is UnixSocketError.ConnectFailed) return false
    return err.errno == ENOENT || err.errno == ECONNREFUSED
}

internal fun mapFatalConnectError(err: UnixSocketError): CompileError = when (err) {
    is UnixSocketError.InvalidArgument -> CompileError.InternalMisuse(err.detail)
    is UnixSocketError.ConnectFailed ->
        CompileError.BackendUnavailable.Other(
            "connect(${err.path}) failed: errno=${err.errno} ${err.message}",
        )
    // Non-connect variants cannot reach this path (connector returns
    // only ConnectFailed / InvalidArgument today), but the exhaustive
    // when keeps the compiler honest if a future UnixSocketError
    // variant is added without updating this mapping.
    is UnixSocketError.SendFailed,
    is UnixSocketError.RecvFailed,
    is UnixSocketError.UnexpectedEof,
    is UnixSocketError.ShutdownFailed,
    -> CompileError.BackendUnavailable.Other("unexpected connect error: $err")
}

internal fun mapFrameErrorToSendError(err: FrameError): CompileError = when (err) {
    is FrameError.Malformed -> CompileError.InternalMisuse("failed to serialise Compile: ${err.reason}")
    is FrameError.Transport -> CompileError.BackendUnavailable.Other("send failed: ${describe(err.cause)}")
    is FrameError.Eof -> CompileError.BackendUnavailable.Other("daemon closed connection before request was sent")
    is FrameError.Truncated -> CompileError.BackendUnavailable.Other("truncated send: wanted=${err.wantedBytes} got=${err.gotBytes}")
}

internal fun mapFrameErrorToReceiveError(err: FrameError): CompileError = when (err) {
    is FrameError.Eof -> CompileError.BackendUnavailable.Other("daemon closed connection before replying")
    is FrameError.Truncated -> CompileError.BackendUnavailable.Other("truncated reply: wanted=${err.wantedBytes} got=${err.gotBytes}")
    is FrameError.Malformed -> CompileError.BackendUnavailable.Other("malformed reply: ${err.reason}")
    is FrameError.Transport -> CompileError.BackendUnavailable.Other("receive failed: ${describe(err.cause)}")
}

internal fun mapReplyToOutcome(reply: Message): Result<CompileOutcome, CompileError> = when (reply) {
    is Message.CompileResult ->
        if (reply.exitCode == 0) {
            Ok(CompileOutcome(stdout = reply.stdout, stderr = reply.stderr))
        } else {
            Err(
                CompileError.CompilationFailed(
                    exitCode = reply.exitCode,
                    stdout = reply.stdout,
                    stderr = reply.stderr,
                ),
            )
        }
    is Message.Compile,
    is Message.Ping,
    is Message.Pong,
    is Message.Shutdown,
    -> Err(
        CompileError.BackendUnavailable.Other(
            "unexpected reply type: ${reply::class.simpleName}",
        ),
    )
}

// Wire contract: the fields of Message.Compile must stay aligned with
// CompileRequest so the native client and the JVM server can talk
// through FrameCodec without a lossy adapter. See ADR 0016 §3 and
// the note on CompileRequest in kolt.build.CompilerBackend.kt.
internal fun CompileRequest.toWireMessage(): Message.Compile = Message.Compile(
    workingDir = workingDir,
    classpath = classpath,
    sources = sources,
    outputPath = outputPath,
    moduleName = moduleName,
    extraArgs = extraArgs,
)

private fun describe(err: UnixSocketError?): String = when (err) {
    null -> "(none)"
    is UnixSocketError.ConnectFailed -> "ConnectFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.SendFailed -> "SendFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.RecvFailed -> "RecvFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.UnexpectedEof -> "UnexpectedEof(received=${err.received}, expected=${err.expected})"
    is UnixSocketError.ShutdownFailed -> "ShutdownFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.InvalidArgument -> "InvalidArgument(${err.detail})"
}
