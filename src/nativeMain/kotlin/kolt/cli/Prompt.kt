package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.DEFAULT_SCAFFOLD_TARGET
import kolt.config.NATIVE_TARGETS
import kolt.config.ScaffoldKind
import kolt.config.isValidGroup
import kolt.infra.output.AnsiCodes
import kolt.infra.output.ColorPolicy
import kolt.infra.output.Stream

internal data class ResolvedScaffoldOptions(
  val kind: ScaffoldKind,
  val target: String,
  val group: String?,
)

internal fun resolveInteractive(
  parsed: ParsedInitArgs,
  io: ScaffoldIO,
  policy: ColorPolicy = ColorPolicy.current(),
): Result<ResolvedScaffoldOptions, String> {
  val tty = io.isStdinTty()

  val kind =
    parsed.kind
      ?: if (tty) {
        promptKind(io, policy).getOrElse {
          return Err(it)
        }
      } else ScaffoldKind.APP

  val target =
    parsed.target
      ?: if (tty) {
        promptTarget(io, policy).getOrElse {
          return Err(it)
        }
      } else DEFAULT_SCAFFOLD_TARGET

  val group =
    if (parsed.groupSpecified) parsed.group
    else if (tty) {
      promptGroup(io).getOrElse {
        return Err(it)
      }
    } else null

  return Ok(ResolvedScaffoldOptions(kind, target, group))
}

private val PROMPT_TARGETS = listOf(DEFAULT_SCAFFOLD_TARGET) + NATIVE_TARGETS.toList().sorted()

// Wrap value in ANSI when policy enables stdout color. Prompt output goes to
// stdout (kotlin.io.println), so the stream key is Stdout — distinct from the
// stderr-only diagnostic writer.
private fun colored(value: String, color: String, policy: ColorPolicy): String =
  if (policy.shouldColor(Stream.Stdout)) "$color$value${AnsiCodes.RESET}" else value

private fun promptKind(io: ScaffoldIO, policy: ColorPolicy): Result<ScaffoldKind, String> {
  val app = colored("app", AnsiCodes.CYAN, policy)
  val lib = colored("lib", AnsiCodes.YELLOW, policy)
  io.println("Kinds: $app (default) | $lib")
  io.println("> kind [app]:")
  val raw = io.readLine()?.trim().orEmpty()
  return when (raw) {
    "",
    "app" -> Ok(ScaffoldKind.APP)
    "lib" -> Ok(ScaffoldKind.LIB)
    else -> Err("invalid kind '$raw' (expected app or lib)")
  }
}

private fun promptTarget(io: ScaffoldIO, policy: ColorPolicy): Result<String, String> {
  val labels =
    PROMPT_TARGETS.joinToString(" | ") { name ->
      val color = if (name == DEFAULT_SCAFFOLD_TARGET) AnsiCodes.CYAN else AnsiCodes.YELLOW
      val coloredName = colored(name, color, policy)
      if (name == DEFAULT_SCAFFOLD_TARGET) "$coloredName (default)" else coloredName
    }
  io.println("Targets: $labels")
  io.println("> target [$DEFAULT_SCAFFOLD_TARGET]:")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(DEFAULT_SCAFFOLD_TARGET)
  if (raw in PROMPT_TARGETS) return Ok(raw)
  return Err("invalid target '$raw' (expected one of ${PROMPT_TARGETS.joinToString(", ")})")
}

private fun promptGroup(io: ScaffoldIO): Result<String?, String> {
  io.println("> group [none]:")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(null)
  if (!isValidGroup(raw)) {
    return Err("invalid group '$raw' (must be a dotted Kotlin package, e.g. com.example)")
  }
  return Ok(raw)
}
