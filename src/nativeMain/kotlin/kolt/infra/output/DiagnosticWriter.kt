package kolt.infra.output

import kolt.infra.eprintln

// Severity → ANSI color: error red, warning yellow, note (and hint) cyan.
private fun colorFor(severity: Severity): String =
  when (severity) {
    Severity.Error -> AnsiCodes.RED
    Severity.Warning -> AnsiCodes.YELLOW
    Severity.Note -> AnsiCodes.CYAN
  }

private fun labelFor(severity: Severity): String =
  when (severity) {
    Severity.Error -> "error:"
    Severity.Warning -> "warning:"
    Severity.Note -> "note:"
  }

private fun coloredLabel(severity: Severity, color: Boolean): String {
  val label = labelFor(severity)
  return if (color) "${colorFor(severity)}$label${AnsiCodes.RESET}" else label
}

// Pure renderer: severity prefix + headline on line 1, context lines indented
// by two spaces, optional hint as a separate `note:` line that uses the cyan
// note color regardless of the parent severity. Always evaluated against the
// stderr stream because the typed writer is stderr-only by contract.
internal fun renderDiagnostic(diag: RenderedDiagnostic, policy: ColorPolicy): List<String> {
  val color = policy.shouldColor(Stream.Stderr)
  val out = mutableListOf<String>()
  out.add("${coloredLabel(diag.severity, color)} ${diag.headline}")
  for (line in diag.context) {
    out.add("  $line")
  }
  if (diag.hint != null) {
    out.add("${coloredLabel(Severity.Note, color)} ${diag.hint}")
  }
  return out
}

fun eprintDiagnostic(
  diag: RenderedDiagnostic,
  policy: ColorPolicy = ColorPolicy.current(),
  sink: (String) -> Unit = ::eprintln,
) {
  for (line in renderDiagnostic(diag, policy)) {
    sink(line)
  }
}

fun eprintError(
  headline: String,
  context: List<String> = emptyList(),
  hint: String? = null,
  policy: ColorPolicy = ColorPolicy.current(),
  sink: (String) -> Unit = ::eprintln,
) {
  eprintDiagnostic(RenderedDiagnostic(Severity.Error, headline, context, hint), policy, sink)
}

fun eprintWarning(
  headline: String,
  context: List<String> = emptyList(),
  hint: String? = null,
  policy: ColorPolicy = ColorPolicy.current(),
  sink: (String) -> Unit = ::eprintln,
) {
  eprintDiagnostic(RenderedDiagnostic(Severity.Warning, headline, context, hint), policy, sink)
}

fun eprintNote(
  headline: String,
  context: List<String> = emptyList(),
  hint: String? = null,
  policy: ColorPolicy = ColorPolicy.current(),
  sink: (String) -> Unit = ::eprintln,
) {
  eprintDiagnostic(RenderedDiagnostic(Severity.Note, headline, context, hint), policy, sink)
}

object AnsiStripper {
  // CSI sequence: ESC `[` parameters intermediate-bytes final-byte (0x40-0x7E).
  // Matches all ANSI color codes (final `m`) plus most other CSI controls.
  private val CSI_REGEX = Regex("\\x1B\\[[0-?]*[ -/]*[@-~]")

  fun strip(s: String): String = CSI_REGEX.replace(s, "")
}

// Caller-side gate for forwarded subprocess stderr blobs (daemon / fallback
// backends emit pre-rendered byte streams that may carry ANSI). Kept as a
// pure helper so unit tests can drive both branches via an injected policy
// without touching the global `ColorPolicy.current()`.
fun maybeStripAnsi(body: String, policy: ColorPolicy = ColorPolicy.current()): String =
  if (policy.shouldColor(Stream.Stderr)) body else AnsiStripper.strip(body)
