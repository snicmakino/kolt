package keel

import com.github.michaelbull.result.getOrElse
import kotlin.system.exitProcess

internal data class ToolSpec(
    val group: String,
    val artifact: String,
    val version: String,
    val fileName: String
) {
    fun downloadUrl(): String {
        val groupPath = group.replace('.', '/')
        return "$MAVEN_CENTRAL_BASE/$groupPath/$artifact/$version/$fileName"
    }

    fun toolPath(toolsDir: String): String = "$toolsDir/$fileName"
}

internal val KTFMT_SPEC = ToolSpec(
    group = "com.facebook",
    artifact = "ktfmt",
    version = "0.54",
    fileName = "ktfmt-0.54-jar-with-dependencies.jar"
)

internal val CONSOLE_LAUNCHER_SPEC = ToolSpec(
    group = "org.junit.platform",
    artifact = "junit-platform-console-standalone",
    version = "1.11.4",
    fileName = "junit-platform-console-standalone-1.11.4.jar"
)

internal fun ensureTool(paths: KeelPaths, spec: ToolSpec, exitCode: Int): String {
    val path = spec.toolPath(paths.toolsDir)
    if (fileExists(path)) return path

    ensureDirectoryRecursive(paths.toolsDir).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(exitCode)
    }

    val url = spec.downloadUrl()
    println("downloading ${spec.artifact}...")
    downloadFile(url, path).getOrElse { error ->
        when (error) {
            is DownloadError.HttpFailed -> eprintln("error: failed to download ${spec.artifact} (HTTP ${error.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write $path")
            is DownloadError.NetworkError -> eprintln("error: network error downloading ${spec.artifact}: ${error.message}")
        }
        exitProcess(exitCode)
    }
    return path
}
