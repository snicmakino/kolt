package kolt.cli

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import kolt.build.Profile
import kolt.config.versionString
import kolt.infra.eprintln
import kolt.infra.output.ColorPolicy
import kolt.infra.output.eprintError
import kolt.infra.suggest.closestMatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    printUsage()
    return
  }

  val parsed = parseKoltArgs(args.toList())
  // Resolve color policy once at startup from the kolt-level `--no-color`
  // flag plus the NO_COLOR env var and per-stream isatty state.
  ColorPolicy.install(ColorPolicy.fromEnv(noColorFlag = parsed.noColor))
  if (parsed.unknownFlags.isNotEmpty()) {
    val unknown = parsed.unknownFlags.first()
    val hint = closestMatch(unknown, KNOWN_KOLT_FLAGS)?.let { "Did you mean `$it`?" }
    eprintError("unknown flag '$unknown'", hint = hint)
    exitProcess(EXIT_COMMAND_NOT_FOUND)
  }
  val useDaemon = parsed.useDaemon
  val watch = parsed.watch
  val profile = parsed.profile
  val cliSysProps = parsed.cliSysProps
  val filteredArgs = parsed.filteredArgs
  if (filteredArgs.isEmpty()) {
    printUsage()
    return
  }

  when (filteredArgs[0]) {
    "init" -> doInit(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "new" -> doNew(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "build" ->
      if (watch) watchCommandLoop("build", useDaemon, profile = profile)
      else doBuild(useDaemon = useDaemon, profile = profile).getOrElse { exitProcess(it) }
    "check" ->
      if (watch) exitProcess(rejectCheckWatch())
      else doCheck(useDaemon = useDaemon, profile = profile).getOrElse { exitProcess(it) }
    "run" -> {
      val appArgs =
        filteredArgs.let { all ->
          val sep = all.indexOf("--")
          if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
        }
      if (watch) {
        watchRunLoop(useDaemon, appArgs, profile = profile, cliSysProps = cliSysProps)
      } else {
        // ADR 0023 §1 kind gate: reject libraries before the
        // build pipeline runs (R4.2). doRun has the same guard
        // for defense-in-depth when called through other paths.
        val parsed = loadProjectConfig().getOrElse { exitProcess(it) }
        rejectIfLibrary(parsed).getOrElse { exitProcess(it) }
        val buildResult =
          doBuild(useDaemon = useDaemon, profile = profile).getOrElse { exitProcess(it) }
        // The build lock is released between doBuild and doRun. Safe today
        // because classpath is in-memory; if doRun ever re-reads
        // build/<name>-runtime.classpath, fold the doRun acquire into
        // doBuild or hold the lock across both calls.
        doRun(
            buildResult.config,
            buildResult.classpath,
            appArgs,
            buildResult.javaPath,
            profile = profile,
            bundleClasspaths = buildResult.bundleClasspaths,
            cliSysProps = cliSysProps,
          )
          .getOrElse { exitProcess(it) }
      }
    }
    "test" -> {
      val testArgs =
        filteredArgs.let { all ->
          val sep = all.indexOf("--")
          if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
        }
      if (watch)
        watchCommandLoop("test", useDaemon, testArgs, profile = profile, cliSysProps = cliSysProps)
      else
        doTest(testArgs, useDaemon = useDaemon, profile = profile, cliSysProps = cliSysProps)
          .getOrElse { exitProcess(it) }
    }
    "fmt" -> doFmt(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "clean" -> doClean().getOrElse { exitProcess(it) }
    "add" -> doAdd(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "remove" -> doRemove(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "fetch" -> doFetch().getOrElse { exitProcess(it) }
    "update" -> doUpdate().getOrElse { exitProcess(it) }
    "tree" -> doTree().getOrElse { exitProcess(it) }
    "outdated" -> doOutdated(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "tool" -> {
      // The Ok value carries the tool's own exit code (verbatim propagation, R2.2);
      // the Err value carries kolt's own ToolError-mapped exit code (R5.4). Both flow
      // through exitProcess so the parent shell observes the right code.
      val exit = doTool(filteredArgs.drop(1)).fold(success = { it }, failure = { it })
      exitProcess(exit)
    }
    "toolchain" -> doToolchain(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "daemon" -> doDaemon(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "cache" -> doCache(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "info" -> doInfo(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "--version",
    "version" -> println(versionString())
    else -> {
      val unknown = filteredArgs[0]
      val hint = closestMatch(unknown, KNOWN_SUBCOMMANDS_SORTED)?.let { "Did you mean `$it`?" }
      eprintError("unknown command '$unknown'", hint = hint)
      printUsage()
      exitProcess(EXIT_COMMAND_NOT_FOUND)
    }
  }
}

// Sorted alphabetically for deterministic Did-you-mean ordering.
// Source of truth is the dispatcher `when` above; keep this list in sync
// when subcommands are added or removed.
internal val KNOWN_SUBCOMMANDS_SORTED: List<String> =
  listOf(
      "add",
      "build",
      "cache",
      "check",
      "clean",
      "daemon",
      "fetch",
      "fmt",
      "info",
      "init",
      "new",
      "outdated",
      "remove",
      "run",
      "test",
      "tool",
      "toolchain",
      "tree",
      "update",
      "version",
    )
    .sorted()

internal const val NO_DAEMON_FLAG = "--no-daemon"
internal const val WATCH_FLAG = "--watch"
internal const val RELEASE_FLAG = "--release"
internal const val NO_COLOR_FLAG = "--no-color"
internal const val SYS_PROP_PREFIX = "-D"

// Sorted alphabetically for deterministic Did-you-mean. Adding a new
// kolt-level flag requires updating both this list and parseKoltArgs.
internal val KNOWN_KOLT_FLAGS: List<String> =
  listOf(NO_COLOR_FLAG, NO_DAEMON_FLAG, RELEASE_FLAG, WATCH_FLAG).sorted()

// `--version` is `--`-prefixed but semantically a subcommand alias (the
// dispatcher routes it through the version branch). Treating it as a
// kolt-level flag would either require special-casing in `unknownFlags`
// detection or break `kolt --version`; carving it out here keeps both
// the boundary computation and the catalog clean.
private val KOLT_SUBCOMMAND_ALIASES: Set<String> = setOf("--version")

internal data class KoltArgs(
  val useDaemon: Boolean,
  val watch: Boolean,
  val profile: Profile,
  val noColor: Boolean,
  val cliSysProps: List<Pair<String, String>>,
  val filteredArgs: List<String>,
  val unknownFlags: List<String>,
)

// Splits the kolt-level flags from the subcommand and from any `--`
// passthrough segment. The kolt-level flags (--no-daemon, --watch,
// --release, --no-color, -D<k>=<v>) are extracted into typed fields and
// stripped from `filteredArgs`; the passthrough block (after `--`) is
// appended verbatim so `kolt run -- --foo` still passes `-- --foo` to the
// subcommand parser.
internal fun parseKoltArgs(argList: List<String>): KoltArgs {
  val passthroughStart = argList.indexOf("--")
  val koltLevel = if (passthroughStart >= 0) argList.subList(0, passthroughStart) else argList
  val passthrough =
    if (passthroughStart >= 0) argList.subList(passthroughStart, argList.size) else emptyList()
  val useDaemon = !koltLevel.contains(NO_DAEMON_FLAG)
  val watch = koltLevel.contains(WATCH_FLAG)
  val profile = if (koltLevel.contains(RELEASE_FLAG)) Profile.Release else Profile.Debug
  val noColor = koltLevel.contains(NO_COLOR_FLAG)
  val cliSysProps = koltLevel.mapNotNull(::parseDFlag)
  // The kolt-level flag block ends at the first non-`--` non-`-D` token,
  // which is the subcommand name (e.g. `build`, `test`); a `--version`
  // alias also closes the block. Tokens after this boundary belong to
  // the subcommand and must not be misclassified as unknown kolt-level
  // flags. Within the kolt-level block, any `--xxx` not in the known set
  // is an unknown flag.
  val subcommandIndex =
    koltLevel.indexOfFirst { token ->
      (!token.startsWith("--") && parseDFlag(token) == null) || token in KOLT_SUBCOMMAND_ALIASES
    }
  val koltLevelOnly = if (subcommandIndex >= 0) koltLevel.subList(0, subcommandIndex) else koltLevel
  val unknownFlags = koltLevelOnly.filter { it.startsWith("--") && it !in KNOWN_KOLT_FLAGS }
  val filteredArgs =
    koltLevel.filter {
      it != NO_DAEMON_FLAG &&
        it != WATCH_FLAG &&
        it != RELEASE_FLAG &&
        it != NO_COLOR_FLAG &&
        parseDFlag(it) == null
    } + passthrough
  return KoltArgs(useDaemon, watch, profile, noColor, cliSysProps, filteredArgs, unknownFlags)
}

// Returns null when `arg` is not a valid `-D<key>=<value>` form. Bare `-D`,
// `-D=value` (empty key), and non-`-D` args fall back to filteredArgs so the
// dispatcher can surface a normal "unknown command" / passthrough error.
// Recognised forms: `-Dkey=value` -> ("key","value"); `-Dkey` -> ("key","");
// `-Dkey=` -> ("key","").
internal fun parseDFlag(arg: String): Pair<String, String>? {
  if (!arg.startsWith(SYS_PROP_PREFIX)) return null
  val rest = arg.substring(SYS_PROP_PREFIX.length)
  if (rest.isEmpty()) return null
  val eq = rest.indexOf('=')
  if (eq < 0) return rest to ""
  val key = rest.substring(0, eq)
  if (key.isEmpty()) return null
  return key to rest.substring(eq + 1)
}

internal fun usageLines(): List<String> =
  listOf(
    "usage: kolt <command>",
    "",
    "commands:",
    "  init       Create a new project in the current directory",
    "  new        Create a new project in <name>/ (same flags as init)",
    "             init/new flags: --lib|--app, --target <target>, --group <group>",
    "  build      Compile the project",
    "  check      Check compilation without producing artifacts",
    "  run        Build and run the project",
    "  test       Build and run tests",
    "  fmt        Format source files with ktfmt",
    "  clean      Remove build artifacts",
    "  add        Add a dependency to kolt.toml",
    "  remove     Remove a dependency from kolt.toml",
    "  fetch      Resolve dependencies and download JARs",
    "  update     Re-resolve dependencies and update kolt.lock",
    "  tree       Show dependency tree",
    "  outdated   Show dependencies with newer versions on Maven Central",
    "  tool       Run a [tools] alias (kolt tool run <alias> [-- args...])",
    "  toolchain  Manage toolchains (install, list, remove)",
    "  daemon     Manage compiler daemons (stop)",
    "  cache      Manage the global dependency cache (clean)",
    "  info       Display environment and project information",
    "  version    Show version information",
    "",
    "flags:",
    "  --watch         Watch source files and re-run on change",
    "  --no-daemon     Skip the warm compiler daemon for this invocation",
    "  --release       Build under the release profile (Native: -opt; JVM: no-op)",
    "  -D<key>=<value> JVM system property for kolt test/run; third overlay layer (kolt.toml <- kolt.local.toml <- -D) over [test|run.sys_props]",
    "  kolt.local.toml Optional per-project overlay file; allowed sections: [test.sys_props], [run.sys_props], [repositories.<name>]",
  )

private fun printUsage() {
  for (line in usageLines()) eprintln(line)
}
