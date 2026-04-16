package kolt.tool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.*
import kolt.config.MAVEN_CENTRAL_BASE

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

internal fun ensureTool(paths: KoltPaths, spec: ToolSpec): Result<String, String> {
    val path = spec.toolPath(paths.toolsDir)
    if (fileExists(path)) return Ok(path)

    ensureDirectoryRecursive(paths.toolsDir).getOrElse { error ->
        return Err("could not create directory ${error.path}")
    }

    val url = spec.downloadUrl()
    println("downloading ${spec.artifact}...")
    downloadFile(url, path).getOrElse { error ->
        return Err(when (error) {
            is DownloadError.HttpFailed -> "failed to download ${spec.artifact} (HTTP ${error.statusCode})"
            is DownloadError.WriteFailed -> "could not write $path"
            is DownloadError.NetworkError -> "network error downloading ${spec.artifact}: ${error.message}"
        })
    }
    return Ok(path)
}
