package keel.cli

import com.github.michaelbull.result.getOrElse
import keel.build.parseKotlinHome
import keel.build.pluginArgs
import keel.config.KeelConfig
import keel.infra.eprintln
import keel.infra.executeAndCapture
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

internal fun resolvePluginArgs(config: KeelConfig, managedKotlincBin: String? = null): List<String> {
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
