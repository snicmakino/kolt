package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.DEFAULT_SCAFFOLD_TARGET
import kolt.config.NATIVE_TARGETS
import kolt.config.ScaffoldKind
import kolt.config.isValidGroup

internal data class ResolvedScaffoldOptions(
  val kind: ScaffoldKind,
  val target: String,
  val group: String?,
)

internal fun resolveInteractive(
  parsed: ParsedInitArgs,
  io: ScaffoldIO,
): Result<ResolvedScaffoldOptions, String> {
  val tty = io.isStdinTty()

  val kind =
    parsed.kind
      ?: if (tty) {
        promptKind(io).getOrElse {
          return Err(it)
        }
      } else ScaffoldKind.APP

  val target =
    parsed.target
      ?: if (tty) {
        promptTarget(io).getOrElse {
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

private fun promptKind(io: ScaffoldIO): Result<ScaffoldKind, String> {
  io.println("Project kind:")
  io.println("  1) app (default)")
  io.println("  2) lib")
  val raw = io.readLine()?.trim().orEmpty()
  return when (raw) {
    "",
    "1",
    "app" -> Ok(ScaffoldKind.APP)
    "2",
    "lib" -> Ok(ScaffoldKind.LIB)
    else -> Err("invalid kind '$raw' (expected 1, 2, app, or lib)")
  }
}

private fun promptTarget(io: ScaffoldIO): Result<String, String> {
  io.println("Target:")
  PROMPT_TARGETS.forEachIndexed { idx, name ->
    val suffix = if (name == DEFAULT_SCAFFOLD_TARGET) " (default)" else ""
    io.println("  ${idx + 1}) $name$suffix")
  }
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(DEFAULT_SCAFFOLD_TARGET)
  val byNumber = raw.toIntOrNull()?.let { PROMPT_TARGETS.getOrNull(it - 1) }
  if (byNumber != null) return Ok(byNumber)
  if (raw in PROMPT_TARGETS) return Ok(raw)
  return Err(
    "invalid target '$raw' (expected 1..${PROMPT_TARGETS.size} or one of ${PROMPT_TARGETS.joinToString(", ")})"
  )
}

private fun promptGroup(io: ScaffoldIO): Result<String?, String> {
  io.println("Group (e.g. com.example, blank for none):")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(null)
  if (!isValidGroup(raw)) {
    return Err("invalid group '$raw' (must be a dotted Kotlin package, e.g. com.example)")
  }
  return Ok(raw)
}
