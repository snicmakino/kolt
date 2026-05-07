package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse

enum class OutdatedFormat {
  Text,
  Json,
}

data class OutdatedOptions(val severities: Set<Severity>, val format: OutdatedFormat)

sealed class OutdatedArgsError {
  data class UnknownFlag(val flag: String) : OutdatedArgsError()

  object MissingFilterValue : OutdatedArgsError()

  data class InvalidFilter(val token: String) : OutdatedArgsError()
}

private val ALL_SEVERITIES = setOf(Severity.Major, Severity.Minor, Severity.Patch)

fun parseOutdatedArgs(args: List<String>): Result<OutdatedOptions, OutdatedArgsError> {
  var format = OutdatedFormat.Text
  var severities: Set<Severity> = ALL_SEVERITIES
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg == "--json" -> format = OutdatedFormat.Json
      arg == "--filter" -> {
        if (i + 1 >= args.size) return Err(OutdatedArgsError.MissingFilterValue)
        severities =
          parseFilter(args[i + 1]).getOrElse {
            return Err(it)
          }
        i++
      }
      else -> return Err(OutdatedArgsError.UnknownFlag(arg))
    }
    i++
  }
  return Ok(OutdatedOptions(severities = severities, format = format))
}

private fun parseFilter(value: String): Result<Set<Severity>, OutdatedArgsError> {
  val out = mutableSetOf<Severity>()
  for (token in value.split(',')) {
    val trimmed = token.trim()
    val severity =
      when (trimmed.lowercase()) {
        "major" -> Severity.Major
        "minor" -> Severity.Minor
        "patch" -> Severity.Patch
        else -> return Err(OutdatedArgsError.InvalidFilter(trimmed))
      }
    out.add(severity)
  }
  return Ok(out)
}

// `applyOutdatedFilter` keeps every error row regardless of severity filter:
// errors carry no severity, so dropping them would silently hide fetch
// failures whenever the user narrowed the filter.
fun applyOutdatedFilter(report: OutdatedReport, severities: Set<Severity>): OutdatedReport =
  OutdatedReport(
    main = report.main.filter { it.error != null || it.severity in severities },
    test = report.test.filter { it.error != null || it.severity in severities },
  )
