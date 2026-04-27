package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.*
import kolt.concurrency.LockError
import kolt.concurrency.LockHandle
import kolt.concurrency.ProjectLock
import kolt.config.*
import kolt.infra.*
import kolt.resolve.*
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getcwd
import platform.posix.getenv

private const val SRC_DIR = "src"
private const val GITIGNORE = ".gitignore"
private const val GIT_DIR = ".git"

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(args: List<String>): Result<Unit, Int> {
  if (fileExists(KOLT_TOML)) {
    eprintln("error: $KOLT_TOML already exists")
    return Err(EXIT_CONFIG_ERROR)
  }

  val projectName =
    if (args.isNotEmpty()) {
      args[0]
    } else {
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

  if (!isValidProjectName(projectName)) {
    eprintln("error: invalid project name '$projectName'")
    eprintln(
      "  project name must start with a letter or digit and contain only letters, digits, '.', '-', '_'"
    )
    return Err(EXIT_CONFIG_ERROR)
  }

  writeFileAsString(KOLT_TOML, generateKoltToml(projectName)).getOrElse { error ->
    eprintln("error: could not write ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  println("created $KOLT_TOML")

  if (!fileExists(SRC_DIR)) {
    ensureDirectory(SRC_DIR).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
  }

  val mainKtPath = "$SRC_DIR/Main.kt"
  if (!fileExists(mainKtPath)) {
    writeFileAsString(mainKtPath, generateMainKt()).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $mainKtPath")
  }

  val testDir = "test"
  if (!fileExists(testDir)) {
    ensureDirectory(testDir).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
  }

  val testKtPath = "$testDir/MainTest.kt"
  if (!fileExists(testKtPath)) {
    writeFileAsString(testKtPath, generateTestKt()).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $testKtPath")
  }

  if (!fileExists(GITIGNORE)) {
    writeFileAsString(GITIGNORE, generateGitignore()).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $GITIGNORE")
  }

  if (!fileExists(GIT_DIR) && !isInsideExistingGitWorktree()) {
    val err = executeCommand(listOf("git", "init", "-q")).getError()
    if (err == null) {
      println("initialized git repository")
    } else {
      eprintln("warning: could not run git init (${formatProcessError(err, "git init")})")
    }
  }

  println("initialized project '$projectName'")
  return Ok(Unit)
}

// `executeAndCapture` only redirects stdout via popen("r"); git's
// "fatal: not a git repository" still goes to the parent's stderr.
// The 2>/dev/null is load-bearing: it suppresses that noise on the
// expected non-zero-exit path (= cwd not in any worktree).
// Any failure (popen error, missing git, weird permission denied) is
// treated as "not in a worktree" — false negatives are safe (we'd
// just create a fresh `.git/`); false positives would be worse.
private fun isInsideExistingGitWorktree(): Boolean =
  executeAndCapture("git rev-parse --is-inside-work-tree 2>/dev/null").getError() == null

internal fun doAdd(args: List<String>): Result<Unit, Int> = withDependencyLock { doAddInner(args) }

private fun doAddInner(args: List<String>): Result<Unit, Int> {
  val addArgs =
    parseAddArgs(args).getOrElse { error ->
      when (error) {
        is AddArgsError.MissingCoordinate ->
          eprintln("usage: kolt add <group:artifact[:version]> [--test]")
        is AddArgsError.InvalidFormat -> eprintln("error: invalid coordinate '${error.input}'")
      }
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  val toml =
    readFileAsString(KOLT_TOML).getOrElse { error ->
      eprintln("error: could not read ${error.path}")
      return Err(EXIT_CONFIG_ERROR)
    }

  val config =
    parseConfig(toml).getOrElse { error ->
      when (error) {
        is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
      }
      return Err(EXIT_CONFIG_ERROR)
    }

  val version =
    if (addArgs.version != null) {
      addArgs.version
    } else {
      println("fetching latest version for ${addArgs.group}:${addArgs.artifact}...")
      fetchLatestVersion(addArgs.group, addArgs.artifact, config.repositories.values.toList())
        .getOrElse {
          return Err(it)
        }
    }

  val groupArtifact = "${addArgs.group}:${addArgs.artifact}"
  val updatedToml =
    addDependencyToToml(toml, groupArtifact, version, addArgs.isTest).getOrElse { error ->
      when (error) {
        is AlreadyExists ->
          eprintln(
            "error: '${error.groupArtifact}' already exists in ${if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"}"
          )
      }
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  writeFileAsString(KOLT_TOML, updatedToml).getOrElse { error ->
    eprintln("error: could not write ${error.path}")
    return Err(EXIT_CONFIG_ERROR)
  }

  val section = if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"
  println("added $groupArtifact = \"$version\" to $section")

  // doInstallInner (not doInstall) — the outer lock acquired by doAdd
  // already covers the install; calling doInstall would attempt a second
  // flock(2) acquire on a fresh OFD and deadlock against ourselves.
  return doInstallInner()
}

private fun fetchLatestVersion(
  group: String,
  artifact: String,
  repos: List<String>,
): Result<String, Int> {
  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_DEPENDENCY_ERROR)
    }
  val groupPath = group.replace('.', '/')
  val metadataPath = "${paths.cacheBase}/$groupPath/$artifact/maven-metadata.xml"

  ensureDirectoryRecursive("${paths.cacheBase}/$groupPath/$artifact").getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_DEPENDENCY_ERROR)
  }

  val fetchErr =
    downloadFromRepositories(
        repos,
        metadataPath,
        { repo -> buildMetadataDownloadUrl(group, artifact, repo) },
        ::downloadFile,
      )
      .getError()
  if (fetchErr != null) {
    when (fetchErr) {
      is DownloadError.HttpFailed ->
        eprintln(
          "error: could not fetch metadata for $group:$artifact (HTTP ${fetchErr.statusCode})"
        )
      is DownloadError.WriteFailed ->
        eprintln("error: could not write metadata to ${fetchErr.path}")
      is DownloadError.NetworkError ->
        eprintln("error: network error fetching metadata for $group:$artifact: ${fetchErr.message}")
    }
    return Err(EXIT_DEPENDENCY_ERROR)
  }

  val xml =
    readFileAsString(metadataPath).getOrElse { error ->
      eprintln("error: could not read ${error.path}")
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  return parseMetadataXml(xml)
    .getOrElse { error ->
      eprintln("error: ${error.message}")
      return Err(EXIT_DEPENDENCY_ERROR)
    }
    .let { Ok(it) }
}

internal fun doInstall(): Result<Unit, Int> = withDependencyLock { doInstallInner() }

private fun doInstallInner(): Result<Unit, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }
  resolveDependencies(config).getOrElse {
    return Err(it)
  }
  println("install complete")
  return Ok(Unit)
}

internal fun doUpdate(): Result<Unit, Int> = withDependencyLock { doUpdateInner() }

private fun doUpdateInner(): Result<Unit, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }
  val mainSeeds = config.dependencies
  val testSeeds = autoInjectedTestDeps(config) + config.testDependencies
  val allSeeds = mainSeeds + testSeeds
  if (allSeeds.isEmpty()) {
    println("no dependencies to update")
    return Ok(Unit)
  }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  for ((groupArtifact, version) in allSeeds) {
    val coord = parseCoordinate(groupArtifact, version).getOrElse { continue }
    val jarPath = "${paths.cacheBase}/${buildCachePath(coord)}"
    if (fileExists(jarPath)) {
      deleteFile(jarPath)
    }
  }

  println("updating dependencies...")
  val resolveResult =
    resolve(config, null, paths.cacheBase, createResolverDeps(), testSeeds = testSeeds).getOrElse {
      error ->
      eprintln(formatResolveError(error))
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  val lockfile = buildLockfileFromResolved(config, resolveResult.deps)
  val lockJson = serializeLockfile(lockfile)
  writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
    eprintln("error: could not write ${error.path}")
    return Err(EXIT_DEPENDENCY_ERROR)
  }
  println("updated ${resolveResult.deps.size} dependencies")
  return Ok(Unit)
}

internal data class DepsTreeSeeds(
  val mainSeeds: Map<String, String>,
  val testSeeds: Map<String, String>,
) {
  val isEmpty: Boolean
    get() = mainSeeds.isEmpty() && testSeeds.isEmpty()
}

internal fun depsTreeSeeds(config: KoltConfig): DepsTreeSeeds =
  DepsTreeSeeds(
    mainSeeds = config.dependencies,
    // Native targets don't auto-inject `kotlin-test-junit5`; the
    // `autoInjectedTestDeps` helper already filters by target, so both
    // JVM and native paths share this expression.
    testSeeds = autoInjectedTestDeps(config) + config.testDependencies,
  )

internal fun doTree(): Result<Unit, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }

  val seeds = depsTreeSeeds(config)
  if (seeds.isEmpty) {
    println("no dependencies")
    return Ok(Unit)
  }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_DEPENDENCY_ERROR)
    }

  if (config.build.target in NATIVE_TARGETS) {
    val nativeLookup =
      createNativeLookup(
        config.repositories.values.toList(),
        paths.cacheBase,
        createResolverDeps(),
        nativeTarget = konanTargetGradleName(config.build.target),
      )
    if (seeds.mainSeeds.isNotEmpty()) {
      val tree = buildNativeDependencyTree(seeds.mainSeeds, nativeLookup)
      println(formatDependencyTree(tree))
    }
    if (seeds.testSeeds.isNotEmpty()) {
      if (seeds.mainSeeds.isNotEmpty()) println()
      println("test dependencies:")
      val testTree = buildNativeDependencyTree(seeds.testSeeds, nativeLookup)
      println(formatDependencyTree(testTree))
    }
    return Ok(Unit)
  }

  val pomLookup =
    createPomLookup(config.repositories.values.toList(), paths.cacheBase, createResolverDeps())
  if (seeds.mainSeeds.isNotEmpty()) {
    val tree = buildDependencyTree(seeds.mainSeeds, pomLookup)
    println(formatDependencyTree(tree))
  }
  if (seeds.testSeeds.isNotEmpty()) {
    if (seeds.mainSeeds.isNotEmpty()) println()
    println("test dependencies:")
    val testTree = buildDependencyTree(seeds.testSeeds, pomLookup)
    println(formatDependencyTree(testTree))
  }
  return Ok(Unit)
}

private val DEPS_SUBCOMMANDS = setOf("add", "install", "update", "tree")

internal fun validateDepsSubcommand(args: List<String>): Boolean =
  args.isNotEmpty() && args[0] in DEPS_SUBCOMMANDS

internal fun doDeps(args: List<String>): Result<Unit, Int> {
  if (!validateDepsSubcommand(args)) {
    printDepsUsage()
    return Err(EXIT_BUILD_ERROR)
  }
  return when (args[0]) {
    "add" -> doAdd(args.drop(1))
    "install" -> doInstall()
    "update" -> doUpdate()
    "tree" -> doTree()
    else -> Ok(Unit)
  }
}

private fun printDepsUsage() {
  eprintln("usage: kolt deps <command>")
  eprintln("")
  eprintln("commands:")
  eprintln("  add        Add a dependency (e.g. kolt deps add group:artifact:version)")
  eprintln("  install    Resolve dependencies and download JARs")
  eprintln("  update     Re-resolve dependencies and update lockfile")
  eprintln("  tree       Show dependency tree")
}

// Read KOLT_LOCK_TIMEOUT_MS from the env. Anything that does not parse as
// a non-negative Long (unset, empty, "abc", "-5") collapses to the
// default. Zero is allowed and means "fail immediately if a peer holds".
// Duplicated from BuildCommands by design (tasks.md 3.2: no shared
// abstraction yet) — the two CLI layers stay independently understandable.
@OptIn(ExperimentalForeignApi::class)
internal fun parseDependencyLockTimeoutMs(): Long {
  val raw = getenv("KOLT_LOCK_TIMEOUT_MS")?.toKString()
  val parsed = raw?.toLongOrNull() ?: return ProjectLock.DEFAULT_TIMEOUT_MS
  return if (parsed >= 0L) parsed else ProjectLock.DEFAULT_TIMEOUT_MS
}

// Wrap a deps-system entry in the project-local advisory lock. Mirrors
// `withProjectLock` in BuildCommands but routes IO failures to
// EXIT_DEPENDENCY_ERROR (the deps tree of CLI exit codes — task 3.2).
private inline fun <T> withDependencyLock(crossinline body: () -> Result<T, Int>): Result<T, Int> {
  ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_DEPENDENCY_ERROR)
  }
  val timeoutMs = parseDependencyLockTimeoutMs()
  val handle: LockHandle =
    ProjectLock.acquire(BUILD_DIR, timeoutMs).getOrElse { lockError ->
      return Err(mapDependencyLockErrorToExitCode(lockError, timeoutMs))
    }
  return handle.use { body() }
}

private fun mapDependencyLockErrorToExitCode(error: LockError, requestedTimeoutMs: Long): Int =
  when (error) {
    is LockError.TimedOut -> {
      val reportedMs = if (error.waitedMs > 0L) error.waitedMs else requestedTimeoutMs
      eprintln(
        "error: lock acquisition timed out after ${reportedMs}ms; " +
          "another kolt build may be stuck"
      )
      EXIT_LOCK_TIMEOUT
    }
    is LockError.IoError -> {
      eprintln(
        "error: could not acquire build lock at $BUILD_DIR/.kolt-build.lock " +
          "(errno=${error.errno}: ${error.message})"
      )
      EXIT_DEPENDENCY_ERROR
    }
  }
