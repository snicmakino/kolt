package keel.config

import com.github.michaelbull.result.getOrElse
import keel.infra.eprintln
import keel.infra.homeDirectory
import kotlin.system.exitProcess

internal data class KeelPaths(val home: String) {
    val cacheBase: String = "$home/.keel/cache"
    val toolsDir: String = "$home/.keel/tools"
    val toolchainsDir: String = "$home/.keel/toolchains"

    fun kotlincPath(version: String): String = "$toolchainsDir/kotlinc/$version"
    fun kotlincBin(version: String): String = "${kotlincPath(version)}/bin/kotlinc"

    fun jdkPath(version: String): String = "$toolchainsDir/jdk/$version"
    fun javaBin(version: String): String = "${jdkPath(version)}/bin/java"
    fun jarBin(version: String): String = "${jdkPath(version)}/bin/jar"
}

internal fun resolveKeelPaths(exitCode: Int): KeelPaths {
    val home = homeDirectory().getOrElse { error ->
        eprintln("error: ${error.message}")
        exitProcess(exitCode)
    }
    return KeelPaths(home)
}
