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

// ADR 0016, #14. Not load-bearing for correctness — all failures are fallback-eligible
// except CompilationFailed. Retry policy: exponential backoff (10..200ms) within 3s budget.
class DaemonCompilerBackend
internal constructor(
  private val javaBin: String,
  private val daemonLaunchArgs: List<String>,
  private val compilerJars: List<String>,
  private val btaImplJars: List<String>,
  private val socketPath: String,
  private val logPath: String? = null,
  private val pluginJars: Map<String, List<String>> = emptyMap(),
  private val connector: DaemonConnector = defaultDaemonConnector,
  private val spawner: DaemonSpawner = defaultDaemonSpawner,
  private val clockMs: () -> Long = ::monotonicMs,
  private val sleeper: (Int) -> Unit = defaultSleeper,
  private val onSpawn: () -> Unit = {},
) : CompilerBackend {

  constructor(
    javaBin: String,
    daemonLaunchArgs: List<String>,
    compilerJars: List<String>,
    btaImplJars: List<String>,
    socketPath: String,
    logPath: String? = null,
    pluginJars: Map<String, List<String>> = emptyMap(),
    onSpawn: () -> Unit = {},
  ) : this(
    javaBin = javaBin,
    daemonLaunchArgs = daemonLaunchArgs,
    compilerJars = compilerJars,
    btaImplJars = btaImplJars,
    socketPath = socketPath,
    logPath = logPath,
    pluginJars = pluginJars,
    connector = defaultDaemonConnector,
    spawner = defaultDaemonSpawner,
    onSpawn = onSpawn,
  )

  override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
    val connection =
      connectOrSpawn().let { result ->
        result.getError()?.let {
          return Err(it)
        }
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
    val first = connector(socketPath)
    first.getError()?.let { err ->
      if (!isRetryableConnectError(err)) {
        return Err(mapFatalConnectError(err))
      }
    } ?: return Ok(first.get()!!)

    onSpawn()
    val spawnErr = spawner(spawnArgv(), logPath).getError()
    if (spawnErr != null) {
      return Err(CompileError.BackendUnavailable.Other("daemon spawn failed: $spawnErr"))
    }

    return retryConnect()
  }

  private fun retryConnect(): Result<DaemonConnection, CompileError> {
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
      CompileError.BackendUnavailable.Other(
        "daemon did not accept a connection on $socketPath within " +
          "${CONNECT_TOTAL_BUDGET_MS}ms (last: ${describe(lastErr)})"
      )
    )
  }

  private fun spawnArgv(): List<String> = buildList {
    add(javaBin)
    addAll(daemonLaunchArgs)
    add("--socket")
    add(socketPath)
    add("--compiler-jars")
    add(compilerJars.joinToString(":"))
    add("--bta-impl-jars")
    add(btaImplJars.joinToString(":"))
    if (pluginJars.isNotEmpty()) {
      add("--plugin-jars")
      add(pluginJars.entries.joinToString(";") { (alias, cp) -> "$alias=${cp.joinToString(":")}" })
    }
  }

  companion object {
    internal const val INITIAL_BACKOFF_MS = 10
    internal const val MAX_BACKOFF_MS = 200
    internal const val CONNECT_TOTAL_BUDGET_MS: Long = 3000L
  }
}

internal interface DaemonConnection : AutoCloseable {
  fun sendRequest(message: Message): Result<Unit, FrameError>

  fun receiveReply(): Result<Message, FrameError>
}

internal class SocketDaemonConnection(private val socket: UnixSocket) : DaemonConnection {
  override fun sendRequest(message: Message): Result<Unit, FrameError> =
    FrameCodec.writeFrame(socket, message)

  override fun receiveReply(): Result<Message, FrameError> = FrameCodec.readFrame(socket)

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

@OptIn(ExperimentalForeignApi::class)
internal fun monotonicMs(): Long = memScoped {
  val ts = alloc<timespec>()
  clock_gettime(CLOCK_MONOTONIC, ts.ptr)
  ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

// ENOENT / ECONNREFUSED are expected transients in the spawn window.
internal fun isRetryableConnectError(err: UnixSocketError): Boolean {
  if (err !is UnixSocketError.ConnectFailed) return false
  return err.errno == ENOENT || err.errno == ECONNREFUSED
}

internal fun mapFatalConnectError(err: UnixSocketError): CompileError =
  when (err) {
    is UnixSocketError.InvalidArgument -> CompileError.InternalMisuse(err.detail)
    is UnixSocketError.ConnectFailed ->
      CompileError.BackendUnavailable.Other(
        "connect(${err.path}) failed: errno=${err.errno} ${err.message}"
      )
    is UnixSocketError.SendFailed,
    is UnixSocketError.RecvFailed,
    is UnixSocketError.UnexpectedEof,
    is UnixSocketError.ShutdownFailed ->
      CompileError.BackendUnavailable.Other("unexpected connect error: $err")
  }

internal fun mapFrameErrorToSendError(err: FrameError): CompileError =
  when (err) {
    is FrameError.Malformed ->
      CompileError.InternalMisuse("failed to serialise Compile: ${err.reason}")
    is FrameError.Transport ->
      CompileError.BackendUnavailable.Other("send failed: ${describe(err.cause)}")
    is FrameError.Eof ->
      CompileError.BackendUnavailable.Other("daemon closed connection before request was sent")
    is FrameError.Truncated ->
      CompileError.BackendUnavailable.Other(
        "truncated send: wanted=${err.wantedBytes} got=${err.gotBytes}"
      )
  }

internal fun mapFrameErrorToReceiveError(err: FrameError): CompileError =
  when (err) {
    is FrameError.Eof ->
      CompileError.BackendUnavailable.Other("daemon closed connection before replying")
    is FrameError.Truncated ->
      CompileError.BackendUnavailable.Other(
        "truncated reply: wanted=${err.wantedBytes} got=${err.gotBytes}"
      )
    is FrameError.Malformed ->
      CompileError.BackendUnavailable.Other("malformed reply: ${err.reason}")
    is FrameError.Transport ->
      CompileError.BackendUnavailable.Other("receive failed: ${describe(err.cause)}")
  }

internal fun mapReplyToOutcome(reply: Message): Result<CompileOutcome, CompileError> =
  when (reply) {
    is Message.CompileResult ->
      when {
        reply.exitCode == 0 -> Ok(CompileOutcome(stdout = reply.stdout, stderr = reply.stderr))
        isDaemonProtocolError(reply) ->
          Err(CompileError.BackendUnavailable.Other("daemon protocol error: ${reply.stderr}"))
        else ->
          Err(
            CompileError.CompilationFailed(
              exitCode = reply.exitCode,
              stdout = reply.stdout,
              stderr = reply.stderr,
              diagnostics = reply.diagnostics,
            )
          )
      }
    is Message.Compile,
    is Message.Ping,
    is Message.Pong,
    is Message.Shutdown ->
      Err(
        CompileError.BackendUnavailable.Other("unexpected reply type: ${reply::class.simpleName}")
      )
  }

private const val DAEMON_PROTOCOL_ERROR_PREFIX = "protocol error: "

private fun isDaemonProtocolError(reply: Message.CompileResult): Boolean =
  reply.exitCode == 2 &&
    reply.diagnostics.isEmpty() &&
    reply.stdout.isEmpty() &&
    reply.stderr.startsWith(DAEMON_PROTOCOL_ERROR_PREFIX)

internal fun CompileRequest.toWireMessage(): Message.Compile =
  Message.Compile(
    workingDir = workingDir,
    classpath = classpath,
    sources = sources,
    outputPath = outputPath,
    moduleName = moduleName,
    extraArgs = extraArgs,
  )

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
