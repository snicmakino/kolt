package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.ScaffoldKind
import kolt.infra.ensureDirectory
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.output.AnsiCodes
import kolt.infra.output.ColorPolicy
import kolt.infra.output.Stream
import kolt.infra.output.eprintError

internal fun doNew(
  args: List<String>,
  io: ScaffoldIO = SystemScaffoldIO,
  policy: ColorPolicy = ColorPolicy.current(),
): Result<Unit, Int> {
  val parsed =
    parseInitArgs(args).getOrElse { msg ->
      eprintError(msg)
      return Err(EXIT_CONFIG_ERROR)
    }

  val projectName =
    parsed.projectName
      ?: run {
        eprintError("kolt new requires a project name")
        eprintln("usage: kolt new <name> [--lib|--app] [--target <target>] [--group <group>]")
        return Err(EXIT_CONFIG_ERROR)
      }

  validateProjectName(projectName).getOrElse { msg ->
    eprintError(msg)
    return Err(EXIT_CONFIG_ERROR)
  }

  if (fileExists(projectName)) {
    eprintError("directory '$projectName' already exists")
    eprintln("  use `kolt init` to scaffold inside an existing directory")
    return Err(EXIT_CONFIG_ERROR)
  }

  val resolved =
    resolveInteractive(parsed, io, policy).getOrElse { msg ->
      eprintError(msg)
      return Err(EXIT_CONFIG_ERROR)
    }

  ensureDirectory(projectName).getOrElse { error ->
    eprintError("could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }

  scaffoldProject(
      projectName,
      ScaffoldOptions(projectName, resolved.kind, resolved.target, resolved.group),
    )
    .getOrElse {
      return Err(it)
    }

  printNextSteps(io, projectName, resolved.kind, policy)
  return Ok(Unit)
}

// Routed through ScaffoldIO so tests can capture the block; SystemScaffoldIO
// writes to real stdout. Cyan emphasis matches the note severity in the
// stderr writer — same family signal in a different channel.
private fun printNextSteps(
  io: ScaffoldIO,
  projectName: String,
  kind: ScaffoldKind,
  policy: ColorPolicy,
) {
  val cmd =
    when (kind) {
      ScaffoldKind.APP -> "kolt run"
      ScaffoldKind.LIB -> "kolt build"
    }
  val color = policy.shouldColor(Stream.Stdout)
  fun emph(s: String): String = if (color) "${AnsiCodes.CYAN}$s${AnsiCodes.RESET}" else s

  io.println("")
  io.println("next steps:")
  io.println("  ${emph("cd $projectName")}")
  io.println("  ${emph(cmd)}")
}
