package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KOLT_VERSION
import kolt.infra.downloadFile
import kolt.infra.eprintln
import kolt.infra.homeDirectory
import kolt.selfupdate.CheckOutcome
import kolt.selfupdate.Downloader
import kolt.selfupdate.GithubReleasesClient
import kolt.selfupdate.SelfUpdateError
import kolt.selfupdate.SelfUpdater
import kolt.selfupdate.UpdateOutcome
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid

// The slice of SelfUpdater that doSelf actually drives. Narrowing to this
// interface lets the dispatch/format/exit-code logic be unit-tested with a
// canned stub instead of a real network + filesystem round-trip; production
// wires the concrete SelfUpdater via the defaulted runnerFactory below.
interface SelfRunner {
  fun check(): Result<CheckOutcome, SelfUpdateError>

  fun update(): Result<UpdateOutcome, SelfUpdateError>
}

private class SelfUpdaterRunner(private val updater: SelfUpdater) : SelfRunner {
  override fun check(): Result<CheckOutcome, SelfUpdateError> = updater.check()

  override fun update(): Result<UpdateOutcome, SelfUpdateError> = updater.update()
}

internal sealed interface SelfInvocation {
  data object EmptyHelp : SelfInvocation

  data object Help : SelfInvocation

  data class Update(val checkOnly: Boolean) : SelfInvocation

  data class UnknownFlag(val flag: String) : SelfInvocation

  data class UnknownSubcommand(val sub: String) : SelfInvocation
}

internal fun parseSelfArgs(args: List<String>): SelfInvocation {
  if (args.isEmpty()) return SelfInvocation.EmptyHelp
  if (args.any { it == "--help" || it == "-h" }) return SelfInvocation.Help
  if (args[0] != "update") return SelfInvocation.UnknownSubcommand(args[0])

  var checkOnly = false
  for (arg in args.drop(1)) {
    when (arg) {
      "--check" -> checkOnly = true
      else -> return SelfInvocation.UnknownFlag(arg)
    }
  }
  return SelfInvocation.Update(checkOnly)
}

// Builds the production SelfUpdater. `$HOME` is resolved here (before the
// updater exists) so a missing HOME is reported as a Home-domain failure
// instead of crashing — every doSelf exit path keeps a message.
@OptIn(ExperimentalForeignApi::class)
private fun productionRunner(): Result<SelfRunner, SelfUpdateError> {
  val home =
    homeDirectory().getOrElse {
      return Err(SelfUpdateError.Home(it.message))
    }
  val client =
    GithubReleasesClient(
      downloader = Downloader(::downloadFile),
      userAgent = "kolt/$KOLT_VERSION",
      // --check needs a temp path but no staging dir; a HOME-rooted dotfile
      // keeps it off /tmp while staying predictable for the single fetch.
      tempPathFactory = { "$home/.local/share/kolt/.self-update-meta-${getpid()}.json" },
    )
  return Ok(SelfUpdaterRunner(SelfUpdater(releases = client, home = home)))
}

internal fun doSelf(
  args: List<String>,
  out: (String) -> Unit = ::println,
  err: (String) -> Unit = ::eprintln,
  runnerFactory: () -> Result<SelfRunner, SelfUpdateError> = ::productionRunner,
): Result<Unit, Int> {
  when (val invocation = parseSelfArgs(args)) {
    SelfInvocation.EmptyHelp,
    SelfInvocation.Help -> {
      for (line in selfUsageLines()) out(line)
      return Ok(Unit)
    }
    is SelfInvocation.UnknownFlag -> {
      err("error: unknown flag '${invocation.flag}'")
      return Err(EXIT_CONFIG_ERROR)
    }
    is SelfInvocation.UnknownSubcommand -> {
      err("error: unknown self subcommand '${invocation.sub}'")
      for (line in selfUsageLines()) err(line)
      return Err(EXIT_COMMAND_NOT_FOUND)
    }
    is SelfInvocation.Update -> {
      val runner =
        runnerFactory().getOrElse {
          err(it.toMessage())
          return Err(EXIT_BUILD_ERROR)
        }
      return if (invocation.checkOnly) {
        runCheck(runner, out, err)
      } else {
        runUpdate(runner, out, err)
      }
    }
  }
}

// --check always exits 0 on success regardless of whether an update is
// available; only a fetch/metadata/platform failure is non-zero.
private fun runCheck(
  runner: SelfRunner,
  out: (String) -> Unit,
  err: (String) -> Unit,
): Result<Unit, Int> {
  val outcome =
    runner.check().getOrElse {
      err(it.toMessage())
      return Err(EXIT_BUILD_ERROR)
    }
  when (outcome) {
    is CheckOutcome.UpdateAvailable ->
      out("Update available: ${outcome.current} → ${outcome.latest}")
    is CheckOutcome.AlreadyLatest -> out("Already at latest version (${outcome.current})")
  }
  return Ok(Unit)
}

private fun runUpdate(
  runner: SelfRunner,
  out: (String) -> Unit,
  err: (String) -> Unit,
): Result<Unit, Int> {
  val outcome =
    runner.update().getOrElse {
      err(it.toMessage())
      return Err(EXIT_BUILD_ERROR)
    }
  // SelfUpdater.update() already announces the already-latest state for the
  // no-op branch via its own `out`; re-printing here would duplicate the
  // line. Only the success arrow is this layer's to emit.
  when (outcome) {
    is UpdateOutcome.Switched -> out("${outcome.from} → ${outcome.to}")
    is UpdateOutcome.NoOp -> Unit
  }
  return Ok(Unit)
}

// Each top-level variant collapses to one human-readable line so the user can
// tell network from metadata from asset from layout from platform at a glance.
private fun SelfUpdateError.toMessage(): String =
  when (this) {
    is SelfUpdateError.Network -> "error: failed to reach ${hostOf(url)}: $detail"
    is SelfUpdateError.Metadata -> "error: GitHub releases/latest response was invalid: $detail"
    is SelfUpdateError.Asset -> "error: release asset '$name': $detail"
    is SelfUpdateError.Extract -> "error: failed to extract update package: $detail"
    is SelfUpdateError.Layout -> "error: $detail (detected $detectedPath)"
    is SelfUpdateError.Platform ->
      "error: kolt self update supports linuxX64 only (detected $sysname/$machine)"
    is SelfUpdateError.Home -> "error: $detail"
  }

// Best-effort host extraction for the network message; falls back to the raw
// URL so the message is never empty even on an unparseable value.
private fun hostOf(url: String): String {
  val afterScheme = url.substringAfter("://", url)
  val host = afterScheme.substringBefore('/').substringBefore('?')
  return host.ifEmpty { url }
}

private fun selfUsageLines(): List<String> =
  listOf(
    "usage: kolt self <subcommand>",
    "",
    "subcommands:",
    "  update           Replace this kolt binary with the latest stable release",
    "  update --check    Report whether a newer stable release is available (no writes)",
  )
