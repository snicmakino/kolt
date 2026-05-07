package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutdatedArgsTest {

  @Test
  fun defaultsToAllSeveritiesAndTextFormat() {
    val opts = parseOutdatedArgs(emptyList()).getOrElse { error("expected Ok, got Err: $it") }
    assertEquals(setOf(Severity.Major, Severity.Minor, Severity.Patch), opts.severities)
    assertEquals(OutdatedFormat.Text, opts.format)
  }

  @Test
  fun jsonFlagSwitchesFormat() {
    val opts = parseOutdatedArgs(listOf("--json")).getOrElse { error("expected Ok, got Err: $it") }
    assertEquals(OutdatedFormat.Json, opts.format)
  }

  @Test
  fun filterMajorOnly() {
    val opts =
      parseOutdatedArgs(listOf("--filter", "major")).getOrElse {
        error("expected Ok, got Err: $it")
      }
    assertEquals(setOf(Severity.Major), opts.severities)
  }

  @Test
  fun filterCommaSeparated() {
    val opts =
      parseOutdatedArgs(listOf("--filter", "major,minor")).getOrElse {
        error("expected Ok, got Err: $it")
      }
    assertEquals(setOf(Severity.Major, Severity.Minor), opts.severities)
  }

  @Test
  fun filterRejectsUnknownToken() {
    val err = parseOutdatedArgs(listOf("--filter", "huge")).getError()
    assertTrue(err is OutdatedArgsError.InvalidFilter, "got $err")
    assertEquals("huge", (err as OutdatedArgsError.InvalidFilter).token)
  }

  @Test
  fun filterRequiresValueArgument() {
    val err = parseOutdatedArgs(listOf("--filter")).getError()
    assertTrue(err is OutdatedArgsError.MissingFilterValue, "got $err")
  }

  @Test
  fun unknownFlagRejected() {
    val err = parseOutdatedArgs(listOf("--surprise")).getError()
    assertTrue(err is OutdatedArgsError.UnknownFlag, "got $err")
    assertEquals("--surprise", (err as OutdatedArgsError.UnknownFlag).flag)
  }

  @Test
  fun applyFilterDropsRowsWhoseSeverityIsNotInTheSet() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow("a:b", "1.0.0", "2.0.0", Severity.Major, null),
            OutdatedRow("c:d", "1.0.0", "1.1.0", Severity.Minor, null),
            OutdatedRow("e:f", "1.0.0", "1.0.1", Severity.Patch, null),
          ),
        test = emptyList(),
      )
    val filtered = applyOutdatedFilter(report, setOf(Severity.Major))
    assertEquals(listOf("a:b"), filtered.main.map { it.groupArtifact })
  }

  @Test
  fun applyFilterAlwaysKeepsErrorRows() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow("a:b", "1.0.0", "2.0.0", Severity.Major, null),
            OutdatedRow("c:d", "1.0.0", null, null, "network down"),
          ),
        test = emptyList(),
      )
    val filtered = applyOutdatedFilter(report, setOf(Severity.Patch))
    // No Patch matches but errors must still surface.
    assertEquals(listOf("c:d"), filtered.main.map { it.groupArtifact })
  }
}
