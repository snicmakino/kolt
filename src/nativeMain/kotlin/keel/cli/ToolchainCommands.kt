package keel.cli

import keel.config.resolveKeelPaths
import keel.infra.eprintln
import keel.tool.installKotlincToolchain
import kotlin.system.exitProcess

internal fun doToolchain(args: List<String>) {
    if (args.isEmpty() || args[0] != "install") {
        eprintln("usage: keel toolchain install")
        exitProcess(EXIT_BUILD_ERROR)
    }
    doToolchainInstall()
}

private fun doToolchainInstall() {
    val config = loadProjectConfig()
    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    installKotlincToolchain(config.kotlin, paths, EXIT_BUILD_ERROR)
}
