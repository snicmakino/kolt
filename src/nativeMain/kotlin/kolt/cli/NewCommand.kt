package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectory
import kolt.infra.eprintln
import kolt.infra.fileExists

internal fun doNew(args: List<String>, io: ScaffoldIO = SystemScaffoldIO): Result<Unit, Int> {
  val parsed =
    parseInitArgs(args).getOrElse { msg ->
      eprintln("error: $msg")
      return Err(EXIT_CONFIG_ERROR)
    }

  val projectName =
    parsed.projectName
      ?: run {
        eprintln("error: kolt new requires a project name")
        eprintln("usage: kolt new <name> [--lib|--app] [--target <target>] [--group <group>]")
        return Err(EXIT_CONFIG_ERROR)
      }

  validateProjectName(projectName).getOrElse { msg ->
    eprintln("error: $msg")
    return Err(EXIT_CONFIG_ERROR)
  }

  if (fileExists(projectName)) {
    eprintln("error: directory '$projectName' already exists")
    eprintln("  use `kolt init` to scaffold inside an existing directory")
    return Err(EXIT_CONFIG_ERROR)
  }

  val resolved =
    resolveInteractive(parsed, io).getOrElse { msg ->
      eprintln("error: $msg")
      return Err(EXIT_CONFIG_ERROR)
    }

  ensureDirectory(projectName).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }

  return scaffoldProject(
    projectName,
    ScaffoldOptions(projectName, resolved.kind, resolved.target, resolved.group),
  )
}
