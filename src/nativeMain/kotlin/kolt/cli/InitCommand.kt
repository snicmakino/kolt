package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.inferProjectName
import kolt.infra.eprintln
import kolt.infra.fileExists
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getcwd

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(args: List<String>, io: ScaffoldIO = SystemScaffoldIO): Result<Unit, Int> {
  val parsed =
    parseInitArgs(args).getOrElse { msg ->
      eprintln("error: $msg")
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
          eprintln("error: could not determine current directory")
          return Err(EXIT_CONFIG_ERROR)
        }
        inferProjectName(cwd)
      }

  validateProjectName(projectName).getOrElse { msg ->
    eprintln("error: $msg")
    return Err(EXIT_CONFIG_ERROR)
  }

  if (fileExists(KOLT_TOML)) {
    eprintln("error: $KOLT_TOML already exists")
    return Err(EXIT_CONFIG_ERROR)
  }

  val resolved =
    resolveInteractive(parsed, io).getOrElse { msg ->
      eprintln("error: $msg")
      return Err(EXIT_CONFIG_ERROR)
    }

  return scaffoldProject(
    ".",
    ScaffoldOptions(projectName, resolved.kind, resolved.target, resolved.group),
  )
}
