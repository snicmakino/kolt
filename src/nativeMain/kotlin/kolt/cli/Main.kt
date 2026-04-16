package kolt.cli

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
    val passthrough = if (passthroughStart >= 0) argList.subList(passthroughStart, argList.size) else emptyList()
    val useDaemon = !koltLevel.contains(NO_DAEMON_FLAG)
    val filteredArgs = koltLevel.filter { it != NO_DAEMON_FLAG } + passthrough
    if (filteredArgs.isEmpty()) {
        printUsage()
        return
    }

    when (filteredArgs[0]) {
        "init" -> doInit(filteredArgs.drop(1))
        "build" -> doBuild(useDaemon = useDaemon)
        "check" -> doCheck(useDaemon = useDaemon)
        "run" -> {
            val appArgs = filteredArgs.let { all ->
                val sep = all.indexOf("--")
                if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
            }
            val (config, classpath, javaPath) = doBuild(useDaemon = useDaemon)
            doRun(config, classpath, appArgs, javaPath)
        }
        "test" -> {
            val testArgs = filteredArgs.let { all ->
                val sep = all.indexOf("--")
                if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
            }
            doTest(testArgs, useDaemon = useDaemon)
        }
        "fmt" -> doFmt(filteredArgs.drop(1))
        "clean" -> doClean()
        "deps" -> doDeps(filteredArgs.drop(1))
        "add" -> doDeps(listOf("add") + filteredArgs.drop(1))
        "install" -> doDeps(listOf("install"))
        "update" -> doDeps(listOf("update"))
        "tree" -> doDeps(listOf("tree"))
        "toolchain" -> doToolchain(filteredArgs.drop(1))
        "daemon" -> doDaemon(filteredArgs.drop(1))
        "--version", "version" -> println(versionString())
        else -> {
            eprintln("error: unknown command '${filteredArgs[0]}'")
            printUsage()
            exitProcess(EXIT_BUILD_ERROR)
        }
    }
}

private const val NO_DAEMON_FLAG = "--no-daemon"

private fun printUsage() {
    eprintln("usage: kolt <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  init       Create a new project")
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
    eprintln("  version    Show version information")
    eprintln("")
    eprintln("flags:")
    eprintln("  --no-daemon  Skip the warm compiler daemon for this invocation")
}
