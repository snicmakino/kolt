package kolt.config

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.homeDirectory
import kotlin.system.exitProcess

internal data class KoltPaths(val home: String) {
    val cacheBase: String = "$home/.kolt/cache"
    val toolsDir: String = "$home/.kolt/tools"
    val toolchainsDir: String = "$home/.kolt/toolchains"

    fun kotlincPath(version: String): String = "$toolchainsDir/kotlinc/$version"
    fun kotlincBin(version: String): String = "${kotlincPath(version)}/bin/kotlinc"

    fun jdkPath(version: String): String = "$toolchainsDir/jdk/$version"
    fun javaBin(version: String): String = "${jdkPath(version)}/bin/java"
    fun jarBin(version: String): String = "${jdkPath(version)}/bin/jar"

    fun konancPath(version: String): String = "$toolchainsDir/konanc/$version"
    fun konancBin(version: String): String = "${konancPath(version)}/bin/konanc"
}

internal fun resolveKoltPaths(exitCode: Int): KoltPaths {
    val home = homeDirectory().getOrElse { error ->
        eprintln("error: ${error.message}")
        exitProcess(exitCode)
    }
    return KoltPaths(home)
}
