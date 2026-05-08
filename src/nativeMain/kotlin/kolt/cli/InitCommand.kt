package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.inferProjectName
import kolt.infra.fileExists
import kolt.infra.output.ColorPolicy
import kolt.infra.output.eprintError
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getcwd

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(
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
        val cwd = memScoped {
          val buf = allocArray<ByteVar>(PATH_MAX)
          getcwd(buf, PATH_MAX.toULong())?.toKString()
        }
        if (cwd == null) {
          eprintError("could not determine current directory")
          return Err(EXIT_CONFIG_ERROR)
        }
        inferProjectName(cwd)
      }

  validateProjectName(projectName).getOrElse { msg ->
    eprintError(msg)
    return Err(EXIT_CONFIG_ERROR)
  }

  if (fileExists(KOLT_TOML)) {
    eprintError("$KOLT_TOML already exists")
    return Err(EXIT_CONFIG_ERROR)
  }

  val resolved =
    resolveInteractive(parsed, io, policy).getOrElse { msg ->
      eprintError(msg)
      return Err(EXIT_CONFIG_ERROR)
    }

  scaffoldProject(".", ScaffoldOptions(projectName, resolved.kind, resolved.target, resolved.group))
    .getOrElse {
      return Err(it)
    }

  printNextSteps(io, cdTarget = null, resolved.kind, policy)
  return Ok(Unit)
}
