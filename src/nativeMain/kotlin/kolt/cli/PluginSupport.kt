package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.parseKotlinHome
import kolt.build.pluginArgs
import kolt.config.KoltConfig
import kolt.infra.eprintln
import kolt.infra.executeAndCapture
import kolt.config.KoltPaths
import kolt.tool.ensureKotlincBin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
internal fun resolveKotlinHome(): String {
    val envHome = getenv("KOTLIN_HOME")?.toKString()
    if (!envHome.isNullOrEmpty()) return envHome

    val kotlincPath = executeAndCapture("which kotlinc").getOrElse {
        eprintln("error: KOTLIN_HOME is not set and kotlinc is not found in PATH")
        exitProcess(EXIT_BUILD_ERROR)
    }.trim()
    return parseKotlinHome(kotlincPath)
}

internal fun resolvePluginArgs(config: KoltConfig, managedKotlincBin: String? = null): List<String> {
    val enabled = config.plugins.filterValues { it }
    if (enabled.isEmpty()) return emptyList()

    val kotlinHome = if (managedKotlincBin != null) {
        parseKotlinHome(managedKotlincBin)
    } else {
        resolveKotlinHome()
    }
    return pluginArgs(config.plugins, kotlinHome).getOrElse { error ->
        eprintln("error: unknown plugin '${error.name}'")
        exitProcess(EXIT_CONFIG_ERROR)
    }
}

// Kotlin/Native path: konanc ships no compiler-plugin jars, so when a native
// build needs plugins we provision the kotlinc distribution purely to borrow
// them. Callers that have no enabled plugins should not call this at all
// (short-circuit on the caller side) to avoid the kotlinc download entirely.
internal fun resolveNativePluginArgs(config: KoltConfig, paths: KoltPaths, exitCode: Int): List<String> {
    val hasEnabledPlugin = config.plugins.any { (_, enabled) -> enabled }
    if (!hasEnabledPlugin) return emptyList()
    val managedKotlincBin = ensureKotlincBin(config.kotlin, paths, exitCode)
    return resolvePluginArgs(config, managedKotlincBin)
}
