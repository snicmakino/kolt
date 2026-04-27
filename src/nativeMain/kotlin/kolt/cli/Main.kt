package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.config.versionString
import kolt.infra.eprintln
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    printUsage()
    return
  }

  val argList = args.toList()
  val passthroughStart = argList.indexOf("--")
  val koltLevel = if (passthroughStart >= 0) argList.subList(0, passthroughStart) else argList
  val passthrough =
    if (passthroughStart >= 0) argList.subList(passthroughStart, argList.size) else emptyList()
  val useDaemon = !koltLevel.contains(NO_DAEMON_FLAG)
  val watch = koltLevel.contains(WATCH_FLAG)
  val filteredArgs = koltLevel.filter { it != NO_DAEMON_FLAG && it != WATCH_FLAG } + passthrough
  if (filteredArgs.isEmpty()) {
    printUsage()
    return
  }

  when (filteredArgs[0]) {
    "init" -> doInit(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "new" -> doNew(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "build" ->
      if (watch) watchCommandLoop("build", useDaemon)
      else doBuild(useDaemon = useDaemon).getOrElse { exitProcess(it) }
    "check" ->
      if (watch) watchCommandLoop("check", useDaemon)
      else doCheck(useDaemon = useDaemon).getOrElse { exitProcess(it) }
    "run" -> {
      val appArgs =
        filteredArgs.let { all ->
          val sep = all.indexOf("--")
          if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
        }
      if (watch) {
        watchRunLoop(useDaemon, appArgs)
      } else {
        // ADR 0023 §1 kind gate: reject libraries before the
        // build pipeline runs (R4.2). doRun has the same guard
        // for defense-in-depth when called through other paths.
        val parsed = loadProjectConfig().getOrElse { exitProcess(it) }
        rejectIfLibrary(parsed).getOrElse { exitProcess(it) }
        val (config, classpath, javaPath) =
          doBuild(useDaemon = useDaemon).getOrElse { exitProcess(it) }
        // The build lock is released between doBuild and doRun. Safe today
        // because classpath is in-memory; if doRun ever re-reads
        // build/<name>-runtime.classpath, fold the doRun acquire into
        // doBuild or hold the lock across both calls.
        doRun(config, classpath, appArgs, javaPath).getOrElse { exitProcess(it) }
      }
    }
    "test" -> {
      val testArgs =
        filteredArgs.let { all ->
          val sep = all.indexOf("--")
          if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
        }
      if (watch) watchCommandLoop("test", useDaemon, testArgs)
      else doTest(testArgs, useDaemon = useDaemon).getOrElse { exitProcess(it) }
    }
    "fmt" -> doFmt(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "clean" -> doClean().getOrElse { exitProcess(it) }
    "deps" -> doDeps(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "add" -> doDeps(listOf("add") + filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "install" -> doDeps(listOf("install")).getOrElse { exitProcess(it) }
    "update" -> doDeps(listOf("update")).getOrElse { exitProcess(it) }
    "tree" -> doDeps(listOf("tree")).getOrElse { exitProcess(it) }
    "toolchain" -> doToolchain(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "daemon" -> doDaemon(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "cache" -> doCache(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "info" -> doInfo(filteredArgs.drop(1)).getOrElse { exitProcess(it) }
    "--version",
    "version" -> println(versionString())
    else -> {
      eprintln("error: unknown command '${filteredArgs[0]}'")
      printUsage()
      exitProcess(EXIT_BUILD_ERROR)
    }
  }
}

private const val NO_DAEMON_FLAG = "--no-daemon"
private const val WATCH_FLAG = "--watch"

private fun printUsage() {
  eprintln("usage: kolt <command>")
  eprintln("")
  eprintln("commands:")
  eprintln("  init       Create a new project in the current directory")
  eprintln("  new        Create a new project in <name>/ (same flags as init)")
  eprintln("             init/new flags: --lib|--app, --target <target>, --group <group>")
  eprintln("  build      Compile the project")
  eprintln("  check      Check compilation without producing artifacts")
  eprintln("  run        Build and run the project")
  eprintln("  test       Build and run tests")
  eprintln("  fmt        Format source files with ktfmt")
  eprintln("  clean      Remove build artifacts")
  eprintln("  add        Add a dependency (alias for deps add)")
  eprintln("  deps       Manage dependencies (add, install, update, tree)")
  eprintln("             install, update, tree are also available as top-level aliases")
  eprintln("  toolchain  Manage toolchains (install, list, remove)")
  eprintln("  daemon     Manage compiler daemons (stop)")
  eprintln("  cache      Manage the global dependency cache (clean)")
  eprintln("  info       Display environment and project information")
  eprintln("  version    Show version information")
  eprintln("")
  eprintln("flags:")
  eprintln("  --watch      Watch source files and re-run on change")
  eprintln("  --no-daemon  Skip the warm compiler daemon for this invocation")
}
