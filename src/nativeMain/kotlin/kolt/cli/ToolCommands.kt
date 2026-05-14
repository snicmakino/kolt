package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.config.resolveKoltPaths
import kolt.infra.CopyFailed
import kolt.infra.copyFile
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.output.eprintDiagnostic
import kolt.infra.output.eprintError
import kolt.infra.output.eprintWarning
import kolt.infra.readFileAsString
import kolt.infra.writeFileAsString
import kolt.resolve.LOCKFILE_VERSION
import kolt.resolve.Lockfile
import kolt.resolve.LockfileLoadResult
import kolt.resolve.ResolverDeps
import kolt.resolve.classifyLockfileLoad
import kolt.resolve.serializeLockfile
import kolt.usertool.ToolEntry
import kolt.usertool.ToolError
import kolt.usertool.ToolFsDeps
import kolt.usertool.ToolJarHandle
import kolt.usertool.ensureTool
import kolt.usertool.launch
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Parsed shape of `kolt tool ...` argv. The dispatcher only accepts `run` as the v1 sub-action
 * (R7.3 — "do not generate one subcommand per alias"); future `list` / `clear-cache` would extend
 * this sealed type rather than balloon `doTool`.
 */
internal sealed class ToolInvocation {
  data class Run(val alias: String, val args: List<String>) : ToolInvocation()
}

internal sealed class ToolArgError {
  data object MissingSubcommand : ToolArgError()

  data class UnknownSubcommand(val subcommand: String) : ToolArgError()

  data object MissingAlias : ToolArgError()
}

/**
 * Pure parser for `kolt tool` argv. Caller has already stripped the leading `tool` token. The `--`
 * separator after the alias is optional (`kolt tool run ktlint -F` and `kolt tool run ktlint -- -F`
 * are equivalent), matching the precedent in `kolt run` / `kolt test`.
 */
internal fun parseToolArgs(args: List<String>): Result<ToolInvocation, ToolArgError> {
  if (args.isEmpty()) return Err(ToolArgError.MissingSubcommand)
  return when (args[0]) {
    "run" -> {
      if (args.size < 2) Err(ToolArgError.MissingAlias)
      else {
        val alias = args[1]
        val rest = args.drop(2)
        // Drop a single leading `--` so aliases with flag-prefixed args remain parseable.
        // Subsequent `--` tokens are passed through verbatim — matches `doTest` / `doRun`.
        val toolArgs = if (rest.firstOrNull() == "--") rest.drop(1) else rest
        Ok(ToolInvocation.Run(alias = alias, args = toolArgs))
      }
    }
    else -> Err(ToolArgError.UnknownSubcommand(args[0]))
  }
}

/**
 * Top-level dispatcher for `kolt tool ...`. Returns `Result<Int, Int>` where the Ok value is the
 * tool's own exit code (R2.2) and the Err value is kolt's mapped exit code from `ToolError` (R5.4).
 */
internal fun doTool(args: List<String>): Result<Int, Int> {
  val invocation =
    parseToolArgs(args).getOrElse { err ->
      printToolUsage()
      return when (err) {
        is ToolArgError.MissingSubcommand,
        is ToolArgError.MissingAlias -> Err(EXIT_CONFIG_ERROR)
        is ToolArgError.UnknownSubcommand -> {
          eprintError("unknown 'kolt tool' subcommand '${err.subcommand}'")
          Err(EXIT_CONFIG_ERROR)
        }
      }
    }

  return when (invocation) {
    is ToolInvocation.Run -> doToolRun(invocation.alias, invocation.args)
  }
}

private fun doToolRun(alias: String, toolArgs: List<String>): Result<Int, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }
  val paths =
    resolveKoltPaths().getOrElse {
      eprintError("$it")
      return Err(EXIT_CONFIG_ERROR)
    }
  val entry =
    lookupToolEntry(alias, config).getOrElse {
      return Err(it)
    }
  return runToolWithDeps(
    alias = alias,
    entry = entry,
    toolArgs = toolArgs,
    config = config,
    paths = paths,
    deps = createToolDeps(),
    env = currentEnvForTool(),
  )
}

/**
 * Resolve `alias` against `config.tools`. Missing alias surfaces a `ToolError.UnknownAlias` whose
 * known-aliases hint is rendered to stderr by the caller (R2.3). Pure to keep the unknown-alias
 * path covered by unit tests without spinning up project config / paths.
 */
internal fun lookupToolEntry(alias: String, config: KoltConfig): Result<ToolEntry, Int> {
  val entry = config.tools[alias]
  if (entry != null) return Ok(entry)
  val toolError = ToolError.UnknownAlias(alias = alias, knownAliases = config.tools.keys.toList())
  eprintDiagnostic(toolError.render())
  return Err(toolError.toExitCode())
}

/**
 * Test-visible orchestration step. Caller injects `deps` and `env`, and the function performs the
 * lockfile read, ensureTool / launch dance, and translates ToolError variants to exit codes /
 * stderr. `lockfileChanged` write-through after first-fetch is currently routed through the
 * standard `kolt update` path — see design.md §ToolResolution for the rationale on why `kolt tool
 * run` itself does not rewrite kolt.lock.
 */
internal fun runToolWithDeps(
  alias: String,
  entry: ToolEntry,
  toolArgs: List<String>,
  config: KoltConfig,
  paths: KoltPaths,
  deps: ToolDeps,
  env: Map<String, String>,
): Result<Int, Int> {
  val lockfile =
    loadLockfileForTool(config).getOrElse {
      return Err(it)
    }
  val handle =
    ensureTool(
        alias = alias,
        entry = entry,
        paths = paths,
        lockfile = lockfile,
        netDeps = deps,
        repos = config.repositories.values.toList(),
      )
      .getOrElse { resolutionError ->
        return surfaceToolError(ToolError.Resolve(resolutionError))
      }

  if (handle.lockfileChanged) {
    writeFirstFetchLockEntry(lockfile, alias, handle).getOrElse {
      return Err(it)
    }
  }

  val exit =
    launch(alias = alias, jarHandle = handle, args = toolArgs, paths = paths, env = env)
      .getOrElse { launchError ->
        return surfaceToolError(ToolError.Launch(launchError))
      }
  return Ok(exit)
}

private fun surfaceToolError(error: ToolError): Result<Int, Int> {
  eprintDiagnostic(error.render())
  return Err(error.toExitCode())
}

private fun loadLockfileForTool(config: KoltConfig): Result<Lockfile, Int> {
  val lockJson =
    if (fileExists(LOCK_FILE)) {
      readFileAsString(LOCK_FILE).getOrElse { error ->
        eprintWarning("could not read $LOCK_FILE: ${error.path}")
        null
      }
    } else null
  return when (val outcome = classifyLockfileLoad(lockJson, allowMigration = false)) {
    is LockfileLoadResult.Loaded -> Ok(outcome.lockfile)
    is LockfileLoadResult.Absent ->
      Ok(
        Lockfile(
          version = LOCKFILE_VERSION,
          kotlin = config.kotlin.version,
          jvmTarget = config.build.jvmTarget,
          dependencies = emptyMap(),
        )
      )
    is LockfileLoadResult.Corrupt -> {
      eprintWarning("${outcome.message}")
      Ok(
        Lockfile(
          version = LOCKFILE_VERSION,
          kotlin = config.kotlin.version,
          jvmTarget = config.build.jvmTarget,
          dependencies = emptyMap(),
        )
      )
    }
    is LockfileLoadResult.UnsupportedAndMigrationAllowed,
    is LockfileLoadResult.UnsupportedAndMigrationDenied -> {
      eprintError("$LOCK_FILE requires regeneration via 'kolt fetch'")
      Err(EXIT_DEPENDENCY_ERROR)
    }
  }
}

// First-fetch write-through. See design.md §ToolResolution: lockfile pin absence is the only
// case where `kolt tool run` writes the lockfile; coords mismatch routes through `kolt update`.
private fun writeFirstFetchLockEntry(
  lockfile: Lockfile,
  alias: String,
  handle: ToolJarHandle,
): Result<Unit, Int> {
  val innerKey =
    if (handle.classifier == null)
      "${handle.resolvedCoords.group}:${handle.resolvedCoords.artifact}"
    else "${handle.resolvedCoords.group}:${handle.resolvedCoords.artifact}:${handle.classifier}"
  val newEntry =
    kolt.resolve.LockEntry(version = handle.resolvedCoords.version, sha256 = jarSha(handle.jarPath))
  val updatedToolsBundles = lockfile.toolsBundles + (alias to mapOf(innerKey to newEntry))
  val updated = lockfile.copy(toolsBundles = updatedToolsBundles)
  val json = serializeLockfile(updated)
  writeFileAsString(LOCK_FILE, json).getOrElse { error ->
    eprintError("could not write ${error.path}")
    return Err(EXIT_DEPENDENCY_ERROR)
  }
  return Ok(Unit)
}

private fun jarSha(jarPath: String): String =
  // ensureTool already verified SHA-256 on the cache path. Re-deriving here keeps the lockfile
  // write-through self-contained, matching what `kolt update` will write on a re-run.
  kolt.infra.computeSha256(jarPath).getOrElse { "" }

@OptIn(ExperimentalForeignApi::class)
private fun currentEnvForTool(): Map<String, String> {
  // Pass through the verbose flag only; tools inherit the broader process env via execvp. Adding
  // entries here would *override* the inherited copy in the child (see Process.executeCommand).
  return when (val v = getenv("KOLT_VERBOSE")?.toKString()) {
    null -> emptyMap()
    else -> mapOf("KOLT_VERBOSE" to v)
  }
}

internal interface ToolDeps : ResolverDeps, ToolFsDeps

private fun createToolDeps(): ToolDeps {
  val resolver = createResolverDeps()
  return object : ToolDeps {
    override fun fileExists(path: String) = resolver.fileExists(path)

    override fun ensureDirectoryRecursive(path: String) = resolver.ensureDirectoryRecursive(path)

    override fun downloadFile(url: String, destPath: String, headers: Map<String, String>?) =
      resolver.downloadFile(url, destPath, headers)

    override fun computeSha256(filePath: String) = resolver.computeSha256(filePath)

    override fun readFileContent(path: String) = resolver.readFileContent(path)

    override fun copyFile(src: String, dest: String): Result<Unit, CopyFailed> = copyFile(src, dest)
  }
}

private fun printToolUsage() {
  eprintln("usage: kolt tool run <alias> [-- args...]")
}
