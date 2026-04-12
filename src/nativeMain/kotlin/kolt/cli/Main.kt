package kolt.cli

import kolt.config.versionString
import kolt.infra.eprintln
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "init" -> doInit(args.drop(1))
        "build" -> doBuild()
        "check" -> doCheck()
        "run" -> {
            val appArgs = args.toList().let { all ->
                val sep = all.indexOf("--")
                if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
            }
            val (config, classpath, _, javaPath) = doBuild()
            doRun(config, classpath, appArgs, javaPath)
        }
        "test" -> {
            val testArgs = args.toList().let { all ->
                val sep = all.indexOf("--")
                if (sep >= 0) all.subList(sep + 1, all.size) else emptyList()
            }
            doTest(testArgs)
        }
        "fmt" -> doFmt(args.drop(1))
        "clean" -> doClean()
        "tree" -> doTree()
        "deps" -> doDeps(args.drop(1))
        "add" -> doAdd(args.drop(1))
        "install" -> doInstall()
        "update" -> doUpdate()
        "toolchain" -> doToolchain(args.drop(1))
        "--version", "version" -> println(versionString())
        else -> {
            eprintln("error: unknown command '${args[0]}'")
            printUsage()
            exitProcess(EXIT_BUILD_ERROR)
        }
    }
}

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
    eprintln("  add        Add a dependency (e.g. kolt add group:artifact:version)")
    eprintln("  tree       Show dependency tree")
    eprintln("  install    Resolve dependencies and download JARs")
    eprintln("  update     Re-resolve dependencies and update lockfile")
    eprintln("  toolchain  Manage toolchains (install, list, remove)")
    eprintln("  version    Show version information")
}
