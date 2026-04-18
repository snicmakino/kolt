package kolt.config

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import kolt.infra.homeDirectory

internal data class KoltPaths(val home: String) {
    val cacheBase: String = "$home/.kolt/cache"
    val toolsDir: String = "$home/.kolt/tools"
    val toolchainsDir: String = "$home/.kolt/toolchains"
    val daemonBaseDir: String = "$home/.kolt/daemon"
    val daemonIcDir: String = "$daemonBaseDir/ic"

    fun daemonDir(projectHash: String, kotlinVersion: String): String =
        "$daemonBaseDir/$projectHash/$kotlinVersion"
    fun daemonSocketPath(projectHash: String, kotlinVersion: String): String =
        "${daemonDir(projectHash, kotlinVersion)}/daemon.sock"
    fun daemonLogPath(projectHash: String, kotlinVersion: String): String =
        "${daemonDir(projectHash, kotlinVersion)}/daemon.log"

    fun kotlincPath(version: String): String = "$toolchainsDir/kotlinc/$version"
    fun kotlincBin(version: String): String = "${kotlincPath(version)}/bin/kotlinc"

    fun jdkPath(version: String): String = "$toolchainsDir/jdk/$version"
    fun javaBin(version: String): String = "${jdkPath(version)}/bin/java"
    fun jarBin(version: String): String = "${jdkPath(version)}/bin/jar"

    fun konancPath(version: String): String = "$toolchainsDir/konanc/$version"
    fun konancBin(version: String): String = "${konancPath(version)}/bin/konanc"
    fun cinteropBin(version: String): String = "${konancPath(version)}/bin/cinterop"
}

internal fun resolveKoltPaths(): Result<KoltPaths, String> =
    homeDirectory().map { KoltPaths(it) }.mapError { it.message }
