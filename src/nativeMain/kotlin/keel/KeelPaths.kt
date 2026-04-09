package keel

import com.github.michaelbull.result.getOrElse
import kotlin.system.exitProcess

internal data class KeelPaths(val home: String) {
    val cacheBase: String = "$home/.keel/cache"
    val toolsDir: String = "$home/.keel/tools"
}

internal fun resolveKeelPaths(exitCode: Int): KeelPaths {
    val home = homeDirectory().getOrElse { error ->
        eprintln("error: ${error.message}")
        exitProcess(exitCode)
    }
    return KeelPaths(home)
}
