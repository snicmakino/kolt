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

// JVM target appears first so its index 1 lines up with the default; native
// targets follow in alphabetical order. Caller iterates indices for prompt
// numbering AND for input parsing — same list, single source of truth.
private val PROMPT_TARGETS = listOf(DEFAULT_SCAFFOLD_TARGET) + NATIVE_TARGETS.toList().sorted()

private val PROMPT_KINDS = listOf(ScaffoldKind.APP, ScaffoldKind.LIB)

// Wrap value in ANSI when policy enables stdout color. Prompt output goes to
// stdout (kotlin.io.println), so the stream key is Stdout — distinct from the
// stderr-only diagnostic writer.
private fun colored(value: String, color: String, policy: ColorPolicy): String =
  if (policy.shouldColor(Stream.Stdout)) "$color$value${AnsiCodes.RESET}" else value

private fun promptKind(io: ScaffoldIO, policy: ColorPolicy): Result<ScaffoldKind, String> {
  io.println("Kinds:")
  PROMPT_KINDS.forEachIndexed { idx, kind ->
    val name = kind.tomlValue
    val color = if (kind == ScaffoldKind.APP) AnsiCodes.CYAN else AnsiCodes.YELLOW
    val coloredName = colored(name, color, policy)
    val suffix = if (kind == ScaffoldKind.APP) " (default)" else ""
    io.println("  ${idx + 1}) $coloredName$suffix")
  }
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(ScaffoldKind.APP)
  val byNumber = raw.toIntOrNull()?.let { PROMPT_KINDS.getOrNull(it - 1) }
  if (byNumber != null) return Ok(byNumber)
  return Err("invalid kind '$raw' (expected 1..${PROMPT_KINDS.size})")
}

private fun promptTarget(io: ScaffoldIO, policy: ColorPolicy): Result<String, String> {
  io.println("Targets:")
  var nativeHeaderEmitted = false
  PROMPT_TARGETS.forEachIndexed { idx, name ->
    val isJvm = name == DEFAULT_SCAFFOLD_TARGET
    if (!isJvm && !nativeHeaderEmitted) {
      io.println("  -- native --")
      nativeHeaderEmitted = true
    }
    val color = if (isJvm) AnsiCodes.CYAN else AnsiCodes.YELLOW
    val coloredName = colored(name, color, policy)
    val suffix = if (isJvm) " (default)" else ""
    io.println("  ${idx + 1}) $coloredName$suffix")
  }
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(DEFAULT_SCAFFOLD_TARGET)
  val byNumber = raw.toIntOrNull()?.let { PROMPT_TARGETS.getOrNull(it - 1) }
  if (byNumber != null) return Ok(byNumber)
  return Err("invalid target '$raw' (expected 1..${PROMPT_TARGETS.size})")
}

// Group is free-form text by nature (e.g. com.example), so the input must
// stay string-typed; the "blank for none" hint goes in the header rather
// than the prompt line so the input arrow stays consistent with kind/target.
private fun promptGroup(io: ScaffoldIO): Result<String?, String> {
  io.println("Group (e.g. com.example, blank for none):")
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(null)
  if (!isValidGroup(raw)) {
    return Err("invalid group '$raw' (must be a dotted Kotlin package, e.g. com.example)")
  }
  return Ok(raw)
}
