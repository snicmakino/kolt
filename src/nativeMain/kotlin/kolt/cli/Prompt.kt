package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.DEFAULT_SCAFFOLD_TARGET
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

  val (kind, target) =
    when {
      parsed.kind != null && parsed.target != null -> parsed.kind to parsed.target
      !tty -> (parsed.kind ?: ScaffoldKind.APP) to (parsed.target ?: DEFAULT_SCAFFOLD_TARGET)
      parsed.kind != null -> {
        val t =
          promptTarget(io, policy).getOrElse {
            return Err(it)
          }
        parsed.kind to t
      }
      parsed.target != null -> {
        val k =
          promptKind(io, policy).getOrElse {
            return Err(it)
          }
        k to parsed.target
      }
      else -> {
        val preset =
          promptPreset(io, policy).getOrElse {
            return Err(it)
          }
        val t =
          if (preset.isNative) {
            promptNativeTarget(io, policy).getOrElse {
              return Err(it)
            }
          } else DEFAULT_SCAFFOLD_TARGET
        preset.kind to t
      }
    }

  val group =
    if (parsed.groupSpecified) parsed.group
    else if (tty) {
      promptGroup(io).getOrElse {
        return Err(it)
      }
    } else null

  return Ok(ResolvedScaffoldOptions(kind, target, group))
}

private data class TargetOption(val name: String, val deprecated: Boolean = false)

// Native targets in hand-curated share order (linuxX64 dominates server / WSL,
// macosArm64 dominates Apple Silicon dev, mingwX64 covers Windows native,
// linuxArm64 covers ARM server / Pi, macosX64 last because Intel Mac is
// deprecated upstream). Reused by promptTarget (jvm + 5 native, --lib-only
// fallback path) and promptNativeTarget (5 native, no-flag native-preset
// branch). Caller iterates indices for prompt numbering AND input parsing —
// same list, single source of truth.
private val NATIVE_TARGETS_BY_SHARE =
  listOf(
    TargetOption("linuxX64"),
    TargetOption("macosArm64"),
    TargetOption("mingwX64"),
    TargetOption("linuxArm64"),
    TargetOption("macosX64", deprecated = true),
  )

private val PROMPT_TARGETS = listOf(TargetOption(DEFAULT_SCAFFOLD_TARGET)) + NATIVE_TARGETS_BY_SHARE

private val PROMPT_KINDS = listOf(ScaffoldKind.APP, ScaffoldKind.LIB)

private data class PresetChoice(val kind: ScaffoldKind, val isNative: Boolean) {
  fun label(): String {
    val kindLabel = kind.tomlValue
    return if (isNative) "native $kindLabel" else "jvm $kindLabel"
  }
}

private val PROMPT_PRESETS =
  listOf(
    PresetChoice(ScaffoldKind.APP, isNative = false),
    PresetChoice(ScaffoldKind.LIB, isNative = false),
    PresetChoice(ScaffoldKind.APP, isNative = true),
    PresetChoice(ScaffoldKind.LIB, isNative = true),
  )

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
  PROMPT_TARGETS.forEachIndexed { idx, opt ->
    val isJvm = opt.name == DEFAULT_SCAFFOLD_TARGET
    if (!isJvm && !nativeHeaderEmitted) {
      io.println("  -- native --")
      nativeHeaderEmitted = true
    }
    val color = if (isJvm) AnsiCodes.CYAN else AnsiCodes.YELLOW
    val coloredName = colored(opt.name, color, policy)
    val suffix = buildString {
      if (isJvm) append(" (default)")
      if (opt.deprecated) append(" (deprecated)")
    }
    io.println("  ${idx + 1}) $coloredName$suffix")
  }
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(DEFAULT_SCAFFOLD_TARGET)
  val byNumber = raw.toIntOrNull()?.let { PROMPT_TARGETS.getOrNull(it - 1)?.name }
  if (byNumber != null) return Ok(byNumber)
  return Err("invalid target '$raw' (expected 1..${PROMPT_TARGETS.size})")
}

private fun promptPreset(io: ScaffoldIO, policy: ColorPolicy): Result<PresetChoice, String> {
  io.println("Presets:")
  PROMPT_PRESETS.forEachIndexed { idx, preset ->
    val isDefault = idx == 0
    val color = if (isDefault) AnsiCodes.CYAN else AnsiCodes.YELLOW
    val coloredName = colored(preset.label(), color, policy)
    val suffix = if (isDefault) " (default)" else ""
    io.println("  ${idx + 1}) $coloredName$suffix")
  }
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(PROMPT_PRESETS[0])
  val byNumber = raw.toIntOrNull()?.let { PROMPT_PRESETS.getOrNull(it - 1) }
  if (byNumber != null) return Ok(byNumber)
  return Err("invalid preset '$raw' (expected 1..${PROMPT_PRESETS.size})")
}

private fun promptNativeTarget(io: ScaffoldIO, policy: ColorPolicy): Result<String, String> {
  io.println("Native target:")
  NATIVE_TARGETS_BY_SHARE.forEachIndexed { idx, opt ->
    val isDefault = idx == 0
    val color = if (isDefault) AnsiCodes.CYAN else AnsiCodes.YELLOW
    val coloredName = colored(opt.name, color, policy)
    val suffix = buildString {
      if (isDefault) append(" (default)")
      if (opt.deprecated) append(" (deprecated)")
    }
    io.println("  ${idx + 1}) $coloredName$suffix")
  }
  io.println(">")
  val raw = io.readLine()?.trim().orEmpty()
  if (raw.isEmpty()) return Ok(NATIVE_TARGETS_BY_SHARE[0].name)
  val byNumber = raw.toIntOrNull()?.let { NATIVE_TARGETS_BY_SHARE.getOrNull(it - 1)?.name }
  if (byNumber != null) return Ok(byNumber)
  return Err("invalid target '$raw' (expected 1..${NATIVE_TARGETS_BY_SHARE.size})")
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
