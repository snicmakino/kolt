package kolt.tool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.*
import kotlinx.serialization.json.*

internal data class ToolchainError(val message: String)

internal fun jdkDownloadUrl(version: String): String =
  "https://api.adoptium.net/v3/binary/latest/$version/ga/linux/x64/jdk/hotspot/normal/eclipse"

internal fun jdkMetadataUrl(version: String): String =
  "https://api.adoptium.net/v3/assets/latest/$version/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse"

internal fun parseJdkChecksum(json: String): String? =
  try {
    Json.parseToJsonElement(json)
      .jsonArray
      .firstOrNull()
      ?.jsonObject
      ?.get("binary")
      ?.jsonObject
      ?.get("package")
      ?.jsonObject
      ?.get("checksum")
      ?.jsonPrimitive
      ?.content
  } catch (_: Exception) {
    null
  }

internal fun findSingleEntry(lsOutput: String): String? {
  val entries = lsOutput.trim().lines().filter { it.isNotBlank() }
  return if (entries.size == 1) entries.first().trim() else null
}

internal fun resolveJavaBinPath(version: String, paths: KoltPaths): String? {
  val binPath = paths.javaBin(version)
  return if (fileExists(binPath)) binPath else null
}

internal fun resolveJarBinPath(version: String, paths: KoltPaths): String? {
  val binPath = paths.jarBin(version)
  return if (fileExists(binPath)) binPath else null
}

private fun formatDownloadError(
  tool: String,
  version: String,
  writePath: String,
  err: DownloadError,
): String =
  when (err) {
    is DownloadError.HttpFailed -> "failed to download $tool $version (HTTP ${err.statusCode})"
    is DownloadError.WriteFailed -> "could not write $writePath"
    is DownloadError.NetworkError -> "network error downloading $tool $version: ${err.message}"
  }

private fun formatExtractError(error: ExtractError, tool: String, version: String): ToolchainError =
  ToolchainError("could not extract $tool $version: ${error.message}")

internal fun installJdkToolchain(
  version: String,
  paths: KoltPaths,
  progressSink: (String) -> Unit = ::println,
): Result<Unit, ToolchainError> {
  val finalPath = paths.jdkPath(version)
  val javaBinPath = paths.javaBin(version)

  if (fileExists(javaBinPath)) {
    progressSink("jdk $version is already installed at $finalPath")
    return Ok(Unit)
  }

  val jdkBaseDir = "${paths.toolchainsDir}/jdk"
  ensureDirectoryRecursive(jdkBaseDir).getOrElse { error ->
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  val tarPath = "$jdkBaseDir/$version.tar.gz"
  val metadataPath = "$jdkBaseDir/$version.metadata.json"
  val extractTempDir = "$jdkBaseDir/${version}_extract"

  progressSink("downloading jdk $version...")
  downloadFile(jdkDownloadUrl(version), tarPath).getOrElse { error ->
    return Err(ToolchainError(formatDownloadError("jdk", version, tarPath, error)))
  }

  downloadFile(jdkMetadataUrl(version), metadataPath).getOrElse { error ->
    deleteFile(tarPath)
    val msg =
      when (error) {
        is DownloadError.HttpFailed ->
          "failed to download metadata for jdk $version (HTTP ${error.statusCode})"
        is DownloadError.WriteFailed -> "could not write $metadataPath"
        is DownloadError.NetworkError -> "network error downloading metadata: ${error.message}"
      }
    return Err(ToolchainError(msg))
  }

  val metadataContent =
    readFileAsString(metadataPath).getOrElse { error ->
      deleteFile(tarPath)
      deleteFile(metadataPath)
      return Err(ToolchainError("could not read ${error.path}"))
    }
  val expectedHash = parseJdkChecksum(metadataContent)
  deleteFile(metadataPath)

  if (expectedHash == null) {
    deleteFile(tarPath)
    return Err(ToolchainError("could not parse checksum from jdk $version metadata"))
  }

  val actualHash =
    computeSha256(tarPath).getOrElse { _ ->
      deleteFile(tarPath)
      return Err(ToolchainError("could not compute sha256 for $tarPath"))
    }

  if (actualHash != expectedHash) {
    deleteFile(tarPath)
    return Err(
      ToolchainError("sha256 mismatch for jdk $version (expected $expectedHash, got $actualHash)")
    )
  }

  ensureDirectoryRecursive(extractTempDir).getOrElse { error ->
    deleteFile(tarPath)
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  progressSink("extracting jdk $version...")
  extractArchive(tarPath, extractTempDir).getOrElse { error ->
    deleteFile(tarPath)
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(formatExtractError(error, "jdk", version))
  }
  deleteFile(tarPath)

  val lsOutput =
    executeAndCapture("ls '$extractTempDir'").getOrElse { _ ->
      removeDirectoryRecursive(extractTempDir).getOrElse { e ->
        eprintln("warning: could not remove temp directory ${e.path}")
      }
      return Err(ToolchainError("could not list extracted jdk directory"))
    }
  val topLevelDir = findSingleEntry(lsOutput)
  if (topLevelDir == null) {
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(
      ToolchainError(
        "expected exactly one top-level directory in jdk archive, got: ${lsOutput.trim()}"
      )
    )
  }

  executeCommand(listOf("mv", "$extractTempDir/$topLevelDir", finalPath)).getOrElse { error ->
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(ToolchainError(formatProcessError(error, "mv")))
  }
  removeDirectoryRecursive(extractTempDir).getOrElse { e ->
    eprintln("warning: could not remove temp directory ${e.path}")
  }

  if (!fileExists(javaBinPath)) {
    return Err(ToolchainError("java binary not found at $javaBinPath after installation"))
  }

  progressSink("installed jdk $version at $finalPath")
  return Ok(Unit)
}

internal fun kotlincDownloadUrl(version: String): String =
  "https://github.com/JetBrains/kotlin/releases/download/v$version/kotlin-compiler-$version.zip"

internal fun kotlincSha256Url(version: String): String = "${kotlincDownloadUrl(version)}.sha256"

internal fun resolveKotlincPath(version: String, paths: KoltPaths): String? {
  val binPath = paths.kotlincBin(version)
  return if (fileExists(binPath)) binPath else null
}

internal fun ensureKotlincBin(version: String, paths: KoltPaths): Result<String, ToolchainError> {
  resolveKotlincPath(version, paths)?.let {
    return Ok(it)
  }
  installKotlincToolchain(version, paths).getOrElse {
    return Err(it)
  }
  return resolveKotlincPath(version, paths)?.let { Ok(it) }
    ?: Err(ToolchainError("kotlinc $version not found after installation"))
}

// `home` is the JDK install root (parent of `bin/`), surfaced so callers
// can hand it off as `JAVA_HOME` to the kotlinc shell wrapper.
internal data class JdkBins(val home: String, val java: String, val jar: String?)

internal fun ensureJdkBins(version: String, paths: KoltPaths): Result<JdkBins, ToolchainError> {
  val home = paths.jdkPath(version)
  val javaBin = resolveJavaBinPath(version, paths)
  if (javaBin != null) {
    val jarBin = resolveJarBinPath(version, paths)
    return Ok(JdkBins(home = home, java = javaBin, jar = jarBin))
  }
  installJdkToolchain(version, paths).getOrElse {
    return Err(it)
  }
  val java =
    resolveJavaBinPath(version, paths)
      ?: return Err(ToolchainError("java binary not found after installation"))
  return Ok(JdkBins(home = home, java = java, jar = resolveJarBinPath(version, paths)))
}

internal fun installKotlincToolchain(
  version: String,
  paths: KoltPaths,
  progressSink: (String) -> Unit = ::println,
): Result<Unit, ToolchainError> {
  val finalPath = paths.kotlincPath(version)
  val binPath = paths.kotlincBin(version)

  if (fileExists(binPath)) {
    progressSink("kotlinc $version is already installed at $finalPath")
    return Ok(Unit)
  }

  val kotlincBaseDir = "${paths.toolchainsDir}/kotlinc"
  ensureDirectoryRecursive(kotlincBaseDir).getOrElse { error ->
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  val zipPath = "$kotlincBaseDir/$version.zip"
  val sha256Path = "$kotlincBaseDir/$version.zip.sha256"
  val extractTempDir = "$kotlincBaseDir/${version}_extract"

  progressSink("downloading kotlin-compiler-$version.zip...")
  downloadFile(kotlincDownloadUrl(version), zipPath).getOrElse { error ->
    return Err(ToolchainError(formatDownloadError("kotlinc", version, zipPath, error)))
  }

  downloadFile(kotlincSha256Url(version), sha256Path).getOrElse { error ->
    deleteFile(zipPath)
    val msg =
      when (error) {
        is DownloadError.HttpFailed ->
          "failed to download sha256 for kotlinc $version (HTTP ${error.statusCode})"
        is DownloadError.WriteFailed -> "could not write $sha256Path"
        is DownloadError.NetworkError -> "network error downloading sha256: ${error.message}"
      }
    return Err(ToolchainError(msg))
  }

  val sha256Content =
    readFileAsString(sha256Path).getOrElse { error ->
      deleteFile(zipPath)
      deleteFile(sha256Path)
      return Err(ToolchainError("could not read ${error.path}"))
    }
  val expectedHash = sha256Content.trim().split(Regex("\\s+")).first()
  deleteFile(sha256Path)

  val actualHash =
    computeSha256(zipPath).getOrElse { _ ->
      deleteFile(zipPath)
      return Err(ToolchainError("could not compute sha256 for $zipPath"))
    }

  if (actualHash != expectedHash) {
    deleteFile(zipPath)
    return Err(
      ToolchainError(
        "sha256 mismatch for kotlinc $version (expected $expectedHash, got $actualHash)"
      )
    )
  }

  ensureDirectoryRecursive(extractTempDir).getOrElse { error ->
    deleteFile(zipPath)
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  progressSink("extracting kotlinc $version...")
  extractArchive(zipPath, extractTempDir).getOrElse { error ->
    deleteFile(zipPath)
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(formatExtractError(error, "kotlinc", version))
  }
  deleteFile(zipPath)

  executeCommand(listOf("mv", "$extractTempDir/kotlinc", finalPath)).getOrElse { error ->
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(ToolchainError(formatProcessError(error, "mv")))
  }
  removeDirectoryRecursive(extractTempDir).getOrElse { e ->
    eprintln("warning: could not remove temp directory ${e.path}")
  }

  if (!fileExists(binPath)) {
    return Err(ToolchainError("kotlinc binary not found at $binPath after installation"))
  }

  progressSink("installed kotlinc $version at $finalPath")
  return Ok(Unit)
}

internal fun konancDownloadUrl(version: String): String =
  "https://github.com/JetBrains/kotlin/releases/download/v$version/kotlin-native-prebuilt-linux-x86_64-$version.tar.gz"

internal fun konancSha256Url(version: String): String = "${konancDownloadUrl(version)}.sha256"

internal fun resolveKonancPath(version: String, paths: KoltPaths): String? {
  val binPath = paths.konancBin(version)
  return if (fileExists(binPath)) binPath else null
}

internal fun ensureKonancBin(version: String, paths: KoltPaths): Result<String, ToolchainError> {
  resolveKonancPath(version, paths)?.let {
    return Ok(it)
  }
  installKonancToolchain(version, paths).getOrElse {
    return Err(it)
  }
  return resolveKonancPath(version, paths)?.let { Ok(it) }
    ?: Err(ToolchainError("konanc $version not found after installation"))
}

internal fun installKonancToolchain(
  version: String,
  paths: KoltPaths,
  progressSink: (String) -> Unit = ::println,
): Result<Unit, ToolchainError> {
  val finalPath = paths.konancPath(version)
  val binPath = paths.konancBin(version)

  if (fileExists(binPath)) {
    progressSink("konanc $version is already installed at $finalPath")
    return Ok(Unit)
  }

  val konancBaseDir = "${paths.toolchainsDir}/konanc"
  ensureDirectoryRecursive(konancBaseDir).getOrElse { error ->
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  val tarPath = "$konancBaseDir/$version.tar.gz"
  val sha256Path = "$konancBaseDir/$version.tar.gz.sha256"
  val extractTempDir = "$konancBaseDir/${version}_extract"
  val archiveTopLevelDir = "kotlin-native-prebuilt-linux-x86_64-$version"

  progressSink("downloading konanc $version...")
  downloadFile(konancDownloadUrl(version), tarPath).getOrElse { error ->
    return Err(ToolchainError(formatDownloadError("konanc", version, tarPath, error)))
  }

  downloadFile(konancSha256Url(version), sha256Path).getOrElse { error ->
    deleteFile(tarPath)
    val msg =
      when (error) {
        is DownloadError.HttpFailed ->
          "failed to download sha256 for konanc $version (HTTP ${error.statusCode})"
        is DownloadError.WriteFailed -> "could not write $sha256Path"
        is DownloadError.NetworkError -> "network error downloading sha256: ${error.message}"
      }
    return Err(ToolchainError(msg))
  }

  val sha256Content =
    readFileAsString(sha256Path).getOrElse { error ->
      deleteFile(tarPath)
      deleteFile(sha256Path)
      return Err(ToolchainError("could not read ${error.path}"))
    }
  val expectedHash = sha256Content.trim().split(Regex("\\s+")).first()
  deleteFile(sha256Path)

  val actualHash =
    computeSha256(tarPath).getOrElse { _ ->
      deleteFile(tarPath)
      return Err(ToolchainError("could not compute sha256 for $tarPath"))
    }

  if (actualHash != expectedHash) {
    deleteFile(tarPath)
    return Err(
      ToolchainError(
        "sha256 mismatch for konanc $version (expected $expectedHash, got $actualHash)"
      )
    )
  }

  ensureDirectoryRecursive(extractTempDir).getOrElse { error ->
    deleteFile(tarPath)
    return Err(ToolchainError("could not create directory ${error.path}"))
  }

  progressSink("extracting konanc $version...")
  executeCommand(listOf("tar", "xzf", tarPath, "-C", extractTempDir)).getOrElse { error ->
    deleteFile(tarPath)
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(ToolchainError(formatProcessError(error, "tar")))
  }
  deleteFile(tarPath)

  executeCommand(listOf("mv", "$extractTempDir/$archiveTopLevelDir", finalPath)).getOrElse { error
    ->
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
      eprintln("warning: could not remove temp directory ${e.path}")
    }
    return Err(ToolchainError(formatProcessError(error, "mv")))
  }
  removeDirectoryRecursive(extractTempDir).getOrElse { e ->
    eprintln("warning: could not remove temp directory ${e.path}")
  }

  if (!fileExists(binPath)) {
    return Err(ToolchainError("konanc binary not found at $binPath after installation"))
  }

  progressSink("installed konanc $version at $finalPath")
  return Ok(Unit)
}
