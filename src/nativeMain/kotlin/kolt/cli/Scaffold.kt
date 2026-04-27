package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.DEFAULT_SCAFFOLD_TARGET
import kolt.config.ScaffoldKind
import kolt.config.VALID_TARGETS
import kolt.config.generateGitignore
import kolt.config.generateKoltToml
import kolt.config.generateLibKt
import kolt.config.generateLibTestKt
import kolt.config.generateMainKt
import kolt.config.generateTestKt
import kolt.config.isValidGroup
import kolt.config.isValidProjectName
import kolt.config.projectNameToPackageSegment
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.executeCommandQuiet
import kolt.infra.fileExists
import kolt.infra.formatProcessError
import kolt.infra.writeFileAsString

internal data class ScaffoldOptions(
  val projectName: String,
  val kind: ScaffoldKind = ScaffoldKind.APP,
  val target: String = DEFAULT_SCAFFOLD_TARGET,
  val group: String? = null,
)

internal data class ParsedInitArgs(
  val projectName: String?,
  val kind: ScaffoldKind = ScaffoldKind.APP,
  val target: String = DEFAULT_SCAFFOLD_TARGET,
  val group: String? = null,
)

internal fun parseInitArgs(args: List<String>): Result<ParsedInitArgs, String> {
  var projectName: String? = null
  var kind: ScaffoldKind? = null
  var target: String? = null
  var group: String? = null
  val iter = args.iterator()
  while (iter.hasNext()) {
    val arg = iter.next()
    when {
      arg == "--lib" || arg == "--app" -> {
        val newKind = if (arg == "--lib") ScaffoldKind.LIB else ScaffoldKind.APP
        if (kind != null && kind != newKind) {
          return Err("--app and --lib are mutually exclusive")
        }
        kind = newKind
      }
      arg == "--target" || arg.startsWith("--target=") -> {
        val value =
          readFlagValue(arg, "--target", iter).getOrElse {
            return Err(it)
          }
        target =
          mergeTarget(target, value).getOrElse {
            return Err(it)
          }
      }
      arg == "--group" || arg.startsWith("--group=") -> {
        val value =
          readFlagValue(arg, "--group", iter).getOrElse {
            return Err(it)
          }
        group =
          mergeGroup(group, value).getOrElse {
            return Err(it)
          }
      }
      arg.startsWith("--") -> return Err("unknown flag '$arg'")
      else -> {
        if (projectName != null) return Err("unexpected positional argument '$arg'")
        projectName = arg
      }
    }
  }
  return Ok(
    ParsedInitArgs(
      projectName = projectName,
      kind = kind ?: ScaffoldKind.APP,
      target = target ?: DEFAULT_SCAFFOLD_TARGET,
      group = group,
    )
  )
}

// Reads the value bound to a long option, supporting both
// `--name VALUE` and `--name=VALUE`. Rejects `--`-prefixed values so a
// missing value followed by another flag (`--target --lib`) does not
// silently swallow the flag.
private fun readFlagValue(
  arg: String,
  flagName: String,
  iter: Iterator<String>,
): Result<String, String> {
  val value =
    if (arg == flagName) {
      if (!iter.hasNext()) return Err("$flagName requires a value")
      iter.next()
    } else {
      arg.removePrefix("$flagName=")
    }
  if (value.isEmpty()) return Err("$flagName requires a value")
  if (value.startsWith("--")) {
    return Err("$flagName requires a value (got '$value', looks like another flag)")
  }
  return Ok(value)
}

private fun mergeTarget(current: String?, value: String): Result<String, String> {
  if (value !in VALID_TARGETS) {
    return Err("invalid target '$value' (valid: ${VALID_TARGETS.joinToString(", ")})")
  }
  if (current != null && current != value) {
    return Err("--target already set to '$current'; cannot also set '$value'")
  }
  return Ok(value)
}

internal fun validateProjectName(name: String): Result<Unit, String> {
  if (isValidProjectName(name)) return Ok(Unit)
  return Err(
    "invalid project name '$name'\n" +
      "  project name must start with a letter or digit and contain only letters, digits, '.', '-', '_'"
  )
}

private fun mergeGroup(current: String?, value: String): Result<String, String> {
  if (!isValidGroup(value)) {
    return Err("invalid group '$value' (must be a dotted Kotlin package, e.g. com.example)")
  }
  if (current != null && current != value) {
    return Err("--group already set to '$current'; cannot also set '$value'")
  }
  return Ok(value)
}

private const val SRC_DIR = "src"
private const val TEST_DIR = "test"
private const val GITIGNORE = ".gitignore"
private const val GIT_DIR = ".git"

internal fun scaffoldProject(targetDir: String, options: ScaffoldOptions): Result<Unit, Int> {
  // Contract: targetDir must already exist. doInit passes "." (always exists);
  // doNew creates `<name>/` itself before delegating, because the precondition
  // "<name>/ must not exist" cannot be enforced from inside ensureDirectory.
  if (!targetDir.isCurrent() && !fileExists(targetDir)) {
    eprintln("error: target directory '$targetDir' does not exist")
    return Err(EXIT_CONFIG_ERROR)
  }

  val tomlPath = resolveInTarget(targetDir, KOLT_TOML)
  writeFileAsString(
      tomlPath,
      generateKoltToml(options.projectName, options.kind, options.target, options.group),
    )
    .getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
  println("created $tomlPath")

  val packageDecl = options.group?.let { "$it.${projectNameToPackageSegment(options.projectName)}" }
  val packageRelPath = packageDecl?.replace('.', '/')

  val sourceFile = sourceTemplateFor(options.kind, packageDecl)
  writeKindFile(resolveInTarget(targetDir, SRC_DIR), packageRelPath, sourceFile).getOrElse {
    return Err(it)
  }

  val testFile = testTemplateFor(options.kind, packageDecl)
  writeKindFile(resolveInTarget(targetDir, TEST_DIR), packageRelPath, testFile).getOrElse {
    return Err(it)
  }

  val gitignorePath = resolveInTarget(targetDir, GITIGNORE)
  if (!fileExists(gitignorePath)) {
    writeFileAsString(gitignorePath, generateGitignore()).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $gitignorePath")
  }

  val gitDirPath = resolveInTarget(targetDir, GIT_DIR)
  if (!fileExists(gitDirPath) && !isInsideExistingGitWorktree(targetDir)) {
    val cmd =
      if (targetDir.isCurrent()) listOf("git", "init", "-q")
      else listOf("git", "-C", targetDir, "init", "-q")
    val err = executeCommand(cmd).getError()
    if (err == null) {
      println("initialized git repository")
    } else {
      eprintln("warning: could not run git init (${formatProcessError(err, "git init")})")
    }
  }

  println("initialized project '${options.projectName}'")
  return Ok(Unit)
}

private fun resolveInTarget(targetDir: String, name: String): String =
  if (targetDir.isCurrent()) name else "$targetDir/$name"

private fun nestedDir(base: String, packageRelPath: String?): String =
  if (packageRelPath == null) base else "$base/$packageRelPath"

private data class TemplateFile(val name: String, val content: String)

private fun sourceTemplateFor(kind: ScaffoldKind, packageDecl: String?): TemplateFile =
  when (kind) {
    ScaffoldKind.APP -> TemplateFile("Main.kt", generateMainKt(packageDecl))
    ScaffoldKind.LIB -> TemplateFile("Lib.kt", generateLibKt(packageDecl))
  }

private fun testTemplateFor(kind: ScaffoldKind, packageDecl: String?): TemplateFile =
  when (kind) {
    ScaffoldKind.APP -> TemplateFile("MainTest.kt", generateTestKt(packageDecl))
    ScaffoldKind.LIB -> TemplateFile("LibTest.kt", generateLibTestKt(packageDecl))
  }

private fun writeKindFile(
  baseDir: String,
  packageRelPath: String?,
  template: TemplateFile,
): Result<Unit, Int> {
  val dir = nestedDir(baseDir, packageRelPath)
  ensureDirectoryRecursive(dir).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  val path = "$dir/${template.name}"
  if (fileExists(path)) return Ok(Unit)
  writeFileAsString(path, template.content).getOrElse { error ->
    eprintln("error: could not write ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  println("created $path")
  return Ok(Unit)
}

private fun String.isCurrent(): Boolean = isEmpty() || this == "."

// Without explicit suppression git prints "fatal: not a git repository ..."
// on the negative path (= the common "kolt init outside any worktree" case).
// `executeCommandQuiet` redirects child stderr to /dev/null so the
// expected non-zero-exit doesn't noise up the user's terminal. Any
// failure (missing git, permission denied) is treated as "not in a
// worktree" — false negatives are safe (we'd just create a fresh `.git/`);
// false positives would be worse.
private fun isInsideExistingGitWorktree(targetDir: String): Boolean {
  val cmd =
    if (targetDir.isCurrent()) listOf("git", "rev-parse", "--is-inside-work-tree")
    else listOf("git", "-C", targetDir, "rev-parse", "--is-inside-work-tree")
  return executeCommandQuiet(cmd).getError() == null
}
