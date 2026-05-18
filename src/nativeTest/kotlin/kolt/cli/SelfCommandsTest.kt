package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kolt.selfupdate.CheckOutcome
import kolt.selfupdate.SelfUpdateError
import kolt.selfupdate.UpdateOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfCommandsTest {

  private val out = mutableListOf<String>()
  private val err = mutableListOf<String>()

  // Canned updater: doSelf only ever calls check()/update(), so a tiny stub
  // that returns a fixed Result is enough to drive every dispatch + formatting
  // + exit-code branch without touching the network or filesystem.
  private fun runner(
    checkResult: Result<CheckOutcome, SelfUpdateError>? = null,
    updateResult: Result<UpdateOutcome, SelfUpdateError>? = null,
  ): SelfRunner =
    object : SelfRunner {
      override fun check(): Result<CheckOutcome, SelfUpdateError> =
        checkResult ?: error("check() not expected in this scenario")

      override fun update(): Result<UpdateOutcome, SelfUpdateError> =
        updateResult ?: error("update() not expected in this scenario")
    }

  private fun run(
    args: List<String>,
    checkResult: Result<CheckOutcome, SelfUpdateError>? = null,
    updateResult: Result<UpdateOutcome, SelfUpdateError>? = null,
  ): Result<Unit, Int> =
    doSelf(
      args,
      out = { out.add(it) },
      err = { err.add(it) },
      runnerFactory = { Ok(runner(checkResult, updateResult)) },
    )

  // Req 1.3: bare `kolt self` prints a usage string and exits 0.
  @Test
  fun emptyArgsPrintsUsageExitsZero() {
    val result = run(emptyList())
    assertNull(result.getError(), "bare `kolt self` must exit 0")
    assertTrue(out.any { it.contains("kolt self") }, "usage should be printed")
    assertTrue(out.any { it.contains("update") }, "usage must mention the update subcommand")
  }

  // Req 1.4: `--help` prints usage and exits 0.
  @Test
  fun helpFlagPrintsUsageExitsZero() {
    val result = run(listOf("--help"))
    assertNull(result.getError(), "--help must exit 0")
    assertTrue(out.any { it.contains("kolt self") }, "usage should be printed for --help")
  }

  // Req 1.4: `-h` is the short alias for help.
  @Test
  fun shortHelpFlagPrintsUsageExitsZero() {
    val result = run(listOf("-h"))
    assertNull(result.getError(), "-h must exit 0")
    assertTrue(out.any { it.contains("kolt self") }, "usage should be printed for -h")
  }

  // Req 2.3: `--check` with a newer release prints the available-update line.
  @Test
  fun checkUpdateAvailablePrintsArrowExitsZero() {
    val result =
      run(
        listOf("update", "--check"),
        checkResult = Ok(CheckOutcome.UpdateAvailable(current = "0.20.0", latest = "0.21.0")),
      )
    assertNull(result.getError(), "--check success is always exit 0")
    assertTrue(
      out.any { it.contains("Update available: 0.20.0 → 0.21.0") },
      "expected the update-available line, got: $out",
    )
  }

  // Req 2.4 / 2.6: `--check` when already current prints the latest line, exit 0.
  @Test
  fun checkAlreadyLatestExitsZero() {
    val result =
      run(
        listOf("update", "--check"),
        checkResult = Ok(CheckOutcome.AlreadyLatest(current = "0.20.0")),
      )
    assertNull(result.getError(), "--check success is always exit 0")
    assertTrue(
      out.any { it.contains("Already at latest version (0.20.0)") },
      "expected the already-latest line, got: $out",
    )
  }

  // Req 3.3: a successful update prints `<old> → <new>` as the final line, exit 0.
  @Test
  fun updateSwitchedPrintsArrowExitsZero() {
    val result =
      run(
        listOf("update"),
        updateResult = Ok(UpdateOutcome.Switched(from = "0.20.0", to = "0.21.0")),
      )
    assertNull(result.getError(), "a successful update exits 0")
    assertEquals("0.20.0 → 0.21.0", out.last(), "final line must be the version arrow")
  }

  // Req 3.2: update no-op prints the already-latest line, exit 0.
  @Test
  fun updateNoOpPrintsAlreadyLatestExitsZero() {
    val result = run(listOf("update"), updateResult = Ok(UpdateOutcome.NoOp(current = "0.20.0")))
    assertNull(result.getError(), "a no-op update exits 0")
    assertTrue(
      out.any { it.contains("Already at latest version (0.20.0)") },
      "expected the already-latest line, got: $out",
    )
  }

  // Req 1.5: an unknown flag on `update` is rejected, the flag is named, exit != 0.
  @Test
  fun unknownFlagIsRejectedAndNamed() {
    val result = run(listOf("update", "--bogus"))
    val code = result.getError()
    assertNotNull(code, "an unknown flag must exit non-zero")
    assertTrue(code != 0, "exit code must be non-zero")
    assertTrue(
      err.any { it.contains("--bogus") },
      "the error must name the offending flag, got: $err",
    )
  }

  // Req 1.5: an unknown subcommand under `self` is rejected, exit != 0.
  @Test
  fun unknownSubcommandIsRejected() {
    val result = run(listOf("frobnicate"))
    val code = result.getError()
    assertNotNull(code, "an unknown subcommand must exit non-zero")
    assertTrue(code != 0, "exit code must be non-zero")
    assertTrue(
      err.any { it.contains("frobnicate") },
      "the error must name the unknown subcommand, got: $err",
    )
  }

  // Req 7.4: every SelfUpdateError variant maps to a non-empty human-readable
  // stderr line and a non-zero exit; the messages are variant-distinct so the
  // user can tell network from asset from layout from platform failures.
  @Test
  fun everyErrorVariantPrintsDistinctNonEmptyMessageAndNonZero() {
    val variants: List<Pair<String, SelfUpdateError>> =
      listOf(
        "network" to SelfUpdateError.Network("https://api.github.com", "connection refused"),
        "metadata" to SelfUpdateError.Metadata("tag 'foo' is not vX.Y.Z"),
        "asset" to SelfUpdateError.Asset("kolt-0.21.0-linux-x64.tar.gz", "checksum mismatch"),
        "extract" to SelfUpdateError.Extract("libarchive rejected entry"),
        "layout" to SelfUpdateError.Layout("/usr/local/bin/kolt", "not an installer symlink"),
        "platform" to SelfUpdateError.Platform("Darwin", "arm64"),
        "home" to SelfUpdateError.Home("HOME is not set"),
      )

    val messages = mutableListOf<String>()
    for ((label, variant) in variants) {
      out.clear()
      err.clear()
      val result = run(listOf("update"), updateResult = Err(variant))
      val code = result.getError()
      assertNotNull(code, "$label must exit non-zero")
      assertTrue(code != 0, "$label exit code must be non-zero")
      val printed = err.joinToString("\n")
      assertTrue(printed.isNotBlank(), "$label must print a non-empty error message")
      messages.add(printed)
    }

    // At least Network / Asset / Layout / Platform must be mutually distinct
    // so the failure category is readable from the message alone (Req 7.*).
    val network = messages[0]
    val asset = messages[2]
    val layout = messages[4]
    val platform = messages[5]
    val distinct = setOf(network, asset, layout, platform)
    assertEquals(
      4,
      distinct.size,
      "network/asset/layout/platform messages must be variant-distinct",
    )
    assertTrue(network.contains("api.github.com"), "network message should name the host")
    assertTrue(
      asset.contains("kolt-0.21.0-linux-x64.tar.gz"),
      "asset message should name the asset",
    )
    assertTrue(layout.contains("/usr/local/bin/kolt"), "layout message should show the path")
    assertTrue(platform.contains("Darwin"), "platform message should name the detected platform")
  }

  // Req 1.5 / 7.4: `--check` failures also surface a non-empty message + non-zero.
  @Test
  fun checkErrorPrintsMessageAndNonZero() {
    val result =
      run(
        listOf("update", "--check"),
        checkResult = Err(SelfUpdateError.Network("https://api.github.com", "timeout")),
      )
    val code = result.getError()
    assertNotNull(code, "a --check network failure must exit non-zero")
    assertTrue(code != 0, "exit code must be non-zero")
    assertTrue(err.joinToString("\n").isNotBlank(), "a failure must print a message")
  }
}
