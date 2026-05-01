package kolt.build.nativedaemon

// Shared with the JVM daemon client: `isRetryableConnectError` classifies
// errno codes (ENOENT / ECONNREFUSED) that are expected transients in the
// spawn window, and `monotonicMs` wraps `clock_gettime(CLOCK_MONOTONIC)`.
// Both are genuinely generic; the import is the path of least coupling until
// a third caller appears and a hoist to `kolt.infra` is warranted.
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.NativeCompileError
import kolt.build.NativeCompileOutcome
import kolt.build.NativeCompilerBackend
import kolt.build.daemon.isRetryableConnectError
import kolt.build.daemon.monotonicMs
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocket
import kolt.infra.net.UnixSocketError
import kolt.infra.spawnDetached
import kolt.nativedaemon.wire.FrameCodec
import kolt.nativedaemon.wire.FrameError
import kolt.nativedaemon.wire.Message
import platform.posix.usleep

// ADR 0024 §2/§3/§7. Parallel to `kolt.build.daemon.DaemonCompilerBackend`:
// spawn-on-demand, exponential backoff connect (10..200ms within 10s budget),
// wire protocol over a Unix domain socket. Not load-bearing for correctness —
// `FallbackNativeCompilerBackend` (ADR 0024 §7) retries eligible errors on
// `NativeSubprocessBackend`.
//
// Constructor asymmetry vs the JVM backend:
// - `konancJar` (single path) replaces `compilerJars` + `btaImplJars`: the
//   native daemon loads `kotlin-native-compiler-embeddable.jar` reflectively
//   (ADR 0024 §8); no BTA exists for native (§6).
// - `konanHome` is new — the daemon sets it as `-Dkonan.home` at JVM startup
//   so konanc can locate its distribution libs (ADR 0024 §8).
// - No `pluginJars` — compiler plugins are a JVM-path feature (ADR 0019 §9);
//   the native path has no structured plugin channel yet.
class NativeDaemonBackend
internal constructor(
  private val javaBin: String,
  private val daemonLaunchArgs: List<String>,
  private val konancJar: String,
  private val konanHome: String,
  private val socketPath: String,
  private val logPath: String? = null,
  private val connector: NativeDaemonConnector = defaultNativeDaemonConnector,
  private val spawner: NativeDaemonSpawner = defaultNativeDaemonSpawner,
  private val clockMs: () -> Long = ::monotonicMs,
  private val sleeper: (Int) -> Unit = defaultNativeDaemonSleeper,
  private val onSpawn: () -> Unit = {},
) : NativeCompilerBackend {

  constructor(
    javaBin: String,
    daemonLaunchArgs: List<String>,
    konancJar: String,
    konanHome: String,
    socketPath: String,
    logPath: String? = null,
    onSpawn: () -> Unit = {},
  ) : this(
    javaBin = javaBin,
    daemonLaunchArgs = daemonLaunchArgs,
    konancJar = konancJar,
    konanHome = konanHome,
    socketPath = socketPath,
    logPath = logPath,
    connector = defaultNativeDaemonConnector,
    spawner = defaultNativeDaemonSpawner,
    onSpawn = onSpawn,
  )

  override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
    val connection =
      connectOrSpawn().let { result ->
        result.getError()?.let {
          return Err(it)
        }
        result.get()!!
      }
    connection.use {
      val wire = Message.NativeCompile(args = args)
      val sendErr = it.sendRequest(wire).getError()
      if (sendErr != null) return Err(mapFrameErrorToSendError(sendErr))
      val reply = it.receiveReply()
      val replyErr = reply.getError()
      if (replyErr != null) return Err(mapFrameErrorToReceiveError(replyErr))
      return mapReplyToOutcome(reply.get()!!)
    }
  }

  private fun connectOrSpawn(): Result<NativeDaemonConnection, NativeCompileError> {
    val first = connector(socketPath)
    first.getError()?.let { err ->
      if (!isRetryableConnectError(err)) {
        return Err(mapFatalConnectError(err))
      }
    } ?: return Ok(first.get()!!)

    onSpawn()
    val spawnErr = spawner(spawnArgv(), logPath).getError()
    if (spawnErr != null) {
      return Err(
        NativeCompileError.BackendUnavailable.Other("native daemon spawn failed: $spawnErr")
      )
    }

    return retryConnect()
  }

  private fun retryConnect(): Result<NativeDaemonConnection, NativeCompileError> {
    val start = clockMs()
    var delayMs = INITIAL_BACKOFF_MS
    var lastErr: UnixSocketError? = null
    do {
      sleeper(delayMs)
      val attempt = connector(socketPath)
      attempt.getError()?.let { err ->
        lastErr = err
        if (!isRetryableConnectError(err)) {
          return Err(mapFatalConnectError(err))
        }
      } ?: return Ok(attempt.get()!!)
      delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    } while (clockMs() - start < CONNECT_TOTAL_BUDGET_MS)
    return Err(
      NativeCompileError.BackendUnavailable.Other(
        "native daemon did not accept a connection on $socketPath within " +
          "${CONNECT_TOTAL_BUDGET_MS}ms (last: ${describe(lastErr)})"
      )
    )
  }

  // ADR 0024 §8: the three daemon CLI flags.
  private fun spawnArgv(): List<String> = buildList {
    add(javaBin)
    add(HEAP_CEILING_XMX)
    addAll(daemonLaunchArgs)
    add("--socket")
    add(socketPath)
    add("--konanc-jar")
    add(konancJar)
    add("--konan-home")
    add(konanHome)
  }

  companion object {
    internal const val INITIAL_BACKOFF_MS = 10
    internal const val MAX_BACKOFF_MS = 200
    internal const val CONNECT_TOTAL_BUDGET_MS: Long = 10_000L

    // Heap ceiling SSoT — lockstep with ADR 0024 §3.
    // JVM daemon's spawnArgv omits `-Xmx` by design: BTA's steady-state
    // heap is modest; K2Native's LLVM backend needs an explicit ceiling.
    internal const val HEAP_CEILING_XMX = "-Xmx4G"
  }
}

internal interface NativeDaemonConnection : AutoCloseable {
  fun sendRequest(message: Message): Result<Unit, FrameError>

  fun receiveReply(): Result<Message, FrameError>
}

internal class SocketNativeDaemonConnection(private val socket: UnixSocket) :
  NativeDaemonConnection {
  override fun sendRequest(message: Message): Result<Unit, FrameError> =
    FrameCodec.writeFrame(socket, message)

  override fun receiveReply(): Result<Message, FrameError> = FrameCodec.readFrame(socket)

  override fun close() {
    socket.close()
  }
}

internal typealias NativeDaemonConnector =
  (String) -> Result<NativeDaemonConnection, UnixSocketError>

internal typealias NativeDaemonSpawner = (List<String>, String?) -> Result<Unit, ProcessError>

internal val defaultNativeDaemonConnector: NativeDaemonConnector = { path ->
  val socketResult = UnixSocket.connect(path)
  val err = socketResult.getError()
  if (err != null) Err(err) else Ok(SocketNativeDaemonConnection(socketResult.get()!!))
}

internal val defaultNativeDaemonSpawner: NativeDaemonSpawner = { argv, logPath ->
  spawnDetached(argv, logPath)
}

internal val defaultNativeDaemonSleeper: (Int) -> Unit = { ms -> usleep((ms * 1000).toUInt()) }

internal fun mapFatalConnectError(err: UnixSocketError): NativeCompileError =
  when (err) {
    is UnixSocketError.InvalidArgument -> NativeCompileError.InternalMisuse(err.detail)
    is UnixSocketError.ConnectFailed ->
      NativeCompileError.BackendUnavailable.Other(
        "connect(${err.path}) failed: errno=${err.errno} ${err.message}"
      )
    is UnixSocketError.SendFailed,
    is UnixSocketError.RecvFailed,
    is UnixSocketError.UnexpectedEof,
    is UnixSocketError.ShutdownFailed ->
      NativeCompileError.BackendUnavailable.Other("unexpected connect error: $err")
  }

internal fun mapFrameErrorToSendError(err: FrameError): NativeCompileError =
  when (err) {
    is FrameError.Malformed ->
      NativeCompileError.InternalMisuse("failed to serialise NativeCompile: ${err.reason}")
    is FrameError.Transport ->
      NativeCompileError.BackendUnavailable.Other("send failed: ${describe(err.cause)}")
    is FrameError.Eof ->
      NativeCompileError.BackendUnavailable.Other(
        "native daemon closed connection before request was sent"
      )
    is FrameError.Truncated ->
      NativeCompileError.BackendUnavailable.Other(
        "truncated send: wanted=${err.wantedBytes} got=${err.gotBytes}"
      )
  }

internal fun mapFrameErrorToReceiveError(err: FrameError): NativeCompileError =
  when (err) {
    is FrameError.Eof ->
      NativeCompileError.BackendUnavailable.Other("native daemon closed connection before replying")
    is FrameError.Truncated ->
      NativeCompileError.BackendUnavailable.Other(
        "truncated reply: wanted=${err.wantedBytes} got=${err.gotBytes}"
      )
    is FrameError.Malformed ->
      NativeCompileError.BackendUnavailable.Other("malformed reply: ${err.reason}")
    is FrameError.Transport ->
      NativeCompileError.BackendUnavailable.Other("receive failed: ${describe(err.cause)}")
  }

internal fun mapReplyToOutcome(reply: Message): Result<NativeCompileOutcome, NativeCompileError> =
  when (reply) {
    is Message.NativeCompileResult ->
      when {
        reply.exitCode == 0 -> Ok(NativeCompileOutcome(stderr = reply.stderr))
        isDaemonSideFailure(reply) ->
          Err(NativeCompileError.BackendUnavailable.Other("native daemon failure: ${reply.stderr}"))
        else ->
          Err(
            NativeCompileError.CompilationFailed(exitCode = reply.exitCode, stderr = reply.stderr)
          )
      }
    is Message.NativeCompile,
    is Message.Ping,
    is Message.Pong,
    is Message.Shutdown ->
      Err(
        NativeCompileError.BackendUnavailable.Other(
          "unexpected reply type: ${reply::class.simpleName}"
        )
      )
  }

// Server emits these prefixes for `writeProtocolError` + `InvocationFailed`
// replies (both exitCode=2). Other exitCode=2 replies come from konanc
// itself (ExitCode.INTERNAL_ERROR=2) and ride the CompilationFailed path.
// Prefix drift is pinned by two paired tests:
//   - server: DaemonServerTest `InvocationFailed...` / `rejects server-only...`
//   - client: NativeDaemonBackendTest `exitCode2With{Protocol,InvocationFailed}Prefix...`
// Rename on either side without updating the other and one of the two fails.
private const val DAEMON_PROTOCOL_ERROR_PREFIX = "protocol error: "
private const val DAEMON_INVOCATION_FAILED_PREFIX = "native compiler invocation failed: "

private fun isDaemonSideFailure(reply: Message.NativeCompileResult): Boolean =
  reply.exitCode == 2 &&
    (reply.stderr.startsWith(DAEMON_PROTOCOL_ERROR_PREFIX) ||
      reply.stderr.startsWith(DAEMON_INVOCATION_FAILED_PREFIX))

// Duplicated from kolt.build.daemon.DaemonCompilerBackend.describe for the
// same reason as parentDir in NativeDaemonJarResolver: keep the new package
// free of cross-package `private` imports. If a third backend ever needs
// the same formatter, hoist to `kolt.infra.net`.
private fun describe(err: UnixSocketError?): String =
  when (err) {
    null -> "(none)"
    is UnixSocketError.ConnectFailed -> "ConnectFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.SendFailed -> "SendFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.RecvFailed -> "RecvFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.UnexpectedEof ->
      "UnexpectedEof(received=${err.received}, expected=${err.expected})"
    is UnixSocketError.ShutdownFailed -> "ShutdownFailed(errno=${err.errno}, ${err.message})"
    is UnixSocketError.InvalidArgument -> "InvalidArgument(${err.detail})"
  }
