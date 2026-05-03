package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.build.formatCommand
import kolt.config.*
import kolt.infra.*
import kolt.tool.*

internal fun doFmt(args: List<String>): Result<Unit, Int> {
  val checkOnly = "--check" in args

  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_FORMAT_ERROR)
    }
  val ktfmtPath =
    ensureTool(paths, KTFMT_SPEC).getOrElse {
      eprintln("error: $it")
      return Err(EXIT_FORMAT_ERROR)
    }
  val managedJdkBins =
    ensureJdkBinsFromConfig(config, paths).getOrElse {
      return Err(it)
    }

  val files = mutableListOf<String>()
  for (dir in config.build.sources) {
    if (isDirectory(dir)) {
      files.addAll(
        listKotlinFiles(dir).getOrElse { error ->
          eprintln("error: could not read directory ${error.path}")
          return Err(EXIT_FORMAT_ERROR)
        }
      )
    }
  }
  for (dir in config.build.testSources) {
    if (isDirectory(dir)) {
      files.addAll(
        listKotlinFiles(dir).getOrElse { error ->
          eprintln("error: could not read directory ${error.path}")
          return Err(EXIT_FORMAT_ERROR)
        }
      )
    }
  }

  if (files.isEmpty()) {
    println("no kotlin files to format")
    return Ok(Unit)
  }

  val jdkVersion = config.build.jdk ?: BOOTSTRAP_JDK_VERSION
  val cmd =
    formatCommand(
      ktfmtPath,
      files,
      checkOnly,
      style = config.fmt.style,
      javaPath = managedJdkBins.java,
      jdkMajorVersion = jdkVersion.substringBefore('.').toIntOrNull(),
    )

  if (checkOnly) {
    println("checking format...")
  } else {
    println("formatting ${files.size} files...")
  }

  executeCommand(cmd.args).getOrElse { error ->
    when (error) {
      is ProcessError.NonZeroExit ->
        eprintln(if (checkOnly) "error: format check failed" else "error: formatting failed")
      else -> eprintln("error: failed to run ktfmt")
    }
    return Err(EXIT_FORMAT_ERROR)
  }

  if (checkOnly) {
    println("format check passed")
  } else {
    println("formatted ${files.size} files")
  }
  return Ok(Unit)
}
