package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.*
import kolt.infra.*
import kolt.tool.installJdkToolchain
import kolt.tool.installKonancToolchain
import kolt.tool.installKotlincToolchain

private val KNOWN_TOOLCHAINS = listOf("kotlinc", "jdk", "konanc")

internal fun doToolchain(args: List<String>): Result<Unit, Int> {
    if (args.isEmpty()) {
        printToolchainUsage()
        return Err(EXIT_CONFIG_ERROR)
    }
    return when (args[0]) {
        "install" -> doToolchainInstall()
        "list" -> doToolchainList()
        "remove" -> doToolchainRemove(args.drop(1))
        else -> {
            printToolchainUsage()
            Err(EXIT_CONFIG_ERROR)
        }
    }
}

private fun printToolchainUsage() {
    eprintln("usage: kolt toolchain <subcommand>")
    eprintln("")
    eprintln("subcommands:")
    eprintln("  install    Install toolchains defined in kolt.toml")
    eprintln("  list       List installed toolchains")
    eprintln("  remove     Remove an installed toolchain (e.g. kolt toolchain remove kotlinc 2.1.0)")
}

private fun doToolchainInstall(): Result<Unit, Int> {
    val config = loadProjectConfig().getOrElse { return Err(it) }
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }
    installKotlincToolchain(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }
    if (config.build.jdk != null) {
        installJdkToolchain(config.build.jdk, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }
    }
    if (config.build.target == "native") {
        installKonancToolchain(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }
    }
    return Ok(Unit)
}

private fun doToolchainList(): Result<Unit, Int> {
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }
    val kotlincVersions = listInstalledVersions("${paths.toolchainsDir}/kotlinc")
    val jdkVersions = listInstalledVersions("${paths.toolchainsDir}/jdk")
    val konancVersions = listInstalledVersions("${paths.toolchainsDir}/konanc")
    println(formatToolchainList(kotlincVersions, jdkVersions, konancVersions))
    return Ok(Unit)
}

private fun listInstalledVersions(dir: String): List<String> {
    if (!fileExists(dir)) return emptyList()
    return listSubdirectories(dir).getOrElse { emptyList() }
}

internal fun formatToolchainList(
    kotlincVersions: List<String>,
    jdkVersions: List<String>,
    konancVersions: List<String>
): String {
    if (kotlincVersions.isEmpty() && jdkVersions.isEmpty() && konancVersions.isEmpty()) {
        return "no toolchains installed"
    }
    val sections = mutableListOf<String>()
    if (kotlincVersions.isNotEmpty()) {
        sections.add("kotlinc (Kotlin compiler):\n" + kotlincVersions.joinToString("\n") { "  $it" })
    }
    if (jdkVersions.isNotEmpty()) {
        sections.add("jdk (Java Development Kit):\n" + jdkVersions.joinToString("\n") { "  $it" })
    }
    if (konancVersions.isNotEmpty()) {
        sections.add("konanc (Kotlin/Native compiler):\n" + konancVersions.joinToString("\n") { "  $it" })
    }
    return sections.joinToString("\n\n")
}

internal data class ToolchainRemoveArgs(val name: String, val version: String)

internal fun validateToolchainRemoveArgs(args: List<String>): Result<ToolchainRemoveArgs, String> {
    if (args.size < 2) {
        return Err("usage: kolt toolchain remove <name> <version>")
    }
    val name = args[0]
    if (name !in KNOWN_TOOLCHAINS) {
        return Err("error: unknown toolchain '$name' (available: ${KNOWN_TOOLCHAINS.joinToString(", ")})")
    }
    return Ok(ToolchainRemoveArgs(name, args[1]))
}

internal fun resolveToolchainPathForRemove(name: String, version: String, paths: KoltPaths): String? {
    val dir = when (name) {
        "kotlinc" -> paths.kotlincPath(version)
        "jdk" -> paths.jdkPath(version)
        "konanc" -> paths.konancPath(version)
        else -> return null
    }
    return if (fileExists(dir)) dir else null
}

private fun doToolchainRemove(args: List<String>): Result<Unit, Int> {
    val parsed = validateToolchainRemoveArgs(args).getOrElse { error ->
        eprintln(error)
        return Err(EXIT_CONFIG_ERROR)
    }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }
    val toolchainPath = resolveToolchainPathForRemove(parsed.name, parsed.version, paths)
    if (toolchainPath == null) {
        eprintln("error: ${parsed.name} ${parsed.version} is not installed")
        return Err(EXIT_BUILD_ERROR)
    }

    removeDirectoryRecursive(toolchainPath).getOrElse { err ->
        eprintln("error: could not remove ${parsed.name} ${parsed.version}: ${err.path}")
        return Err(EXIT_BUILD_ERROR)
    }
    println("removed ${parsed.name} ${parsed.version}")
    return Ok(Unit)
}
