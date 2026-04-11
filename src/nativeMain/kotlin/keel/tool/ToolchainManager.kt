package keel.tool

import com.github.michaelbull.result.getOrElse
import keel.config.KeelPaths
import keel.infra.*
import kotlin.system.exitProcess

internal fun jdkDownloadUrl(version: String): String =
    "https://api.adoptium.net/v3/binary/latest/$version/ga/linux/x64/jdk/hotspot/normal/eclipse"

internal fun resolveJavaBinPath(version: String, paths: KeelPaths): String? {
    val binPath = paths.javaBin(version)
    return if (fileExists(binPath)) binPath else null
}

internal fun resolveJarBinPath(version: String, paths: KeelPaths): String? {
    val binPath = paths.jarBin(version)
    return if (fileExists(binPath)) binPath else null
}

internal fun installJdkToolchain(version: String, paths: KeelPaths, exitCode: Int) {
    val finalPath = paths.jdkPath(version)
    val javaBinPath = paths.javaBin(version)

    if (fileExists(javaBinPath)) {
        println("jdk $version is already installed at $finalPath")
        return
    }

    val jdkBaseDir = "${paths.toolchainsDir}/jdk"
    ensureDirectoryRecursive(jdkBaseDir).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(exitCode)
    }

    val tarPath = "$jdkBaseDir/$version.tar.gz"
    val extractTempDir = "$jdkBaseDir/${version}_extract"

    println("downloading jdk $version...")
    downloadFile(jdkDownloadUrl(version), tarPath).getOrElse { error ->
        when (error) {
            is DownloadError.HttpFailed -> eprintln("error: failed to download jdk $version (HTTP ${error.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write $tarPath")
            is DownloadError.NetworkError -> eprintln("error: network error downloading jdk $version: ${error.message}")
        }
        exitProcess(exitCode)
    }

    ensureDirectoryRecursive(extractTempDir).getOrElse { error ->
        deleteFile(tarPath)
        eprintln("error: could not create directory ${error.path}")
        exitProcess(exitCode)
    }

    println("extracting jdk $version...")
    executeCommand(listOf("tar", "xzf", tarPath, "-C", extractTempDir)).getOrElse { error ->
        deleteFile(tarPath)
        removeDirectoryRecursive(extractTempDir).getOrElse { e ->
            eprintln("warning: could not remove temp directory ${e.path}")
        }
        eprintln(formatProcessError(error, "tar"))
        exitProcess(exitCode)
    }
    deleteFile(tarPath)

    // Adoptium tar.gz contains a top-level dir like "jdk-21.0.2+13/" — find and move its contents
    val topLevelDir = executeAndCapture("ls '$extractTempDir'").getOrElse { _ ->
        removeDirectoryRecursive(extractTempDir).getOrElse { e ->
            eprintln("warning: could not remove temp directory ${e.path}")
        }
        eprintln("error: could not list extracted jdk directory")
        exitProcess(exitCode)
    }.trim()

    executeCommand(listOf("mv", "$extractTempDir/$topLevelDir", finalPath)).getOrElse { error ->
        removeDirectoryRecursive(extractTempDir).getOrElse { e ->
            eprintln("warning: could not remove temp directory ${e.path}")
        }
        eprintln(formatProcessError(error, "mv"))
        exitProcess(exitCode)
    }
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
        eprintln("warning: could not remove temp directory ${e.path}")
    }

    if (!fileExists(javaBinPath)) {
        eprintln("error: java binary not found at $javaBinPath after installation")
        exitProcess(exitCode)
    }

    println("installed jdk $version at $finalPath")
}

internal fun kotlincDownloadUrl(version: String): String =
    "https://github.com/JetBrains/kotlin/releases/download/v$version/kotlin-compiler-$version.zip"

internal fun kotlincSha256Url(version: String): String =
    "${kotlincDownloadUrl(version)}.sha256"

internal fun resolveKotlincPath(version: String, paths: KeelPaths): String? {
    val binPath = paths.kotlincBin(version)
    return if (fileExists(binPath)) binPath else null
}

internal fun installKotlincToolchain(version: String, paths: KeelPaths, exitCode: Int) {
    val finalPath = paths.kotlincPath(version)
    val binPath = paths.kotlincBin(version)

    if (fileExists(binPath)) {
        println("kotlinc $version is already installed at $finalPath")
        return
    }

    val kotlincBaseDir = "${paths.toolchainsDir}/kotlinc"
    ensureDirectoryRecursive(kotlincBaseDir).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(exitCode)
    }

    val zipPath = "$kotlincBaseDir/$version.zip"
    val sha256Path = "$kotlincBaseDir/$version.zip.sha256"
    val extractTempDir = "$kotlincBaseDir/${version}_extract"

    println("downloading kotlin-compiler-$version.zip...")
    downloadFile(kotlincDownloadUrl(version), zipPath).getOrElse { error ->
        when (error) {
            is DownloadError.HttpFailed -> eprintln("error: failed to download kotlinc $version (HTTP ${error.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write $zipPath")
            is DownloadError.NetworkError -> eprintln("error: network error downloading kotlinc $version: ${error.message}")
        }
        exitProcess(exitCode)
    }

    downloadFile(kotlincSha256Url(version), sha256Path).getOrElse { error ->
        deleteFile(zipPath)
        when (error) {
            is DownloadError.HttpFailed -> eprintln("error: failed to download sha256 for kotlinc $version (HTTP ${error.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write $sha256Path")
            is DownloadError.NetworkError -> eprintln("error: network error downloading sha256: ${error.message}")
        }
        exitProcess(exitCode)
    }

    val sha256Content = readFileAsString(sha256Path).getOrElse { error ->
        deleteFile(zipPath)
        deleteFile(sha256Path)
        eprintln("error: could not read ${error.path}")
        exitProcess(exitCode)
    }
    // SHA256 files can be "<hash>  <filename>" or just "<hash>"
    val expectedHash = sha256Content.trim().split(Regex("\\s+")).first()
    deleteFile(sha256Path)

    val actualHash = computeSha256(zipPath).getOrElse { _ ->
        deleteFile(zipPath)
        eprintln("error: could not compute sha256 for $zipPath")
        exitProcess(exitCode)
    }

    if (actualHash != expectedHash) {
        deleteFile(zipPath)
        eprintln("error: sha256 mismatch for kotlinc $version (expected $expectedHash, got $actualHash)")
        exitProcess(exitCode)
    }

    ensureDirectoryRecursive(extractTempDir).getOrElse { error ->
        deleteFile(zipPath)
        eprintln("error: could not create directory ${error.path}")
        exitProcess(exitCode)
    }

    println("extracting kotlinc $version...")
    executeCommand(listOf("unzip", "-q", zipPath, "-d", extractTempDir)).getOrElse { error ->
        deleteFile(zipPath)
        removeDirectoryRecursive(extractTempDir).getOrElse { e ->
            eprintln("warning: could not remove temp directory ${e.path}")
        }
        eprintln(formatProcessError(error, "unzip"))
        exitProcess(exitCode)
    }
    deleteFile(zipPath)

    executeCommand(listOf("mv", "$extractTempDir/kotlinc", finalPath)).getOrElse { error ->
        removeDirectoryRecursive(extractTempDir).getOrElse { e ->
            eprintln("warning: could not remove temp directory ${e.path}")
        }
        eprintln(formatProcessError(error, "mv"))
        exitProcess(exitCode)
    }
    removeDirectoryRecursive(extractTempDir).getOrElse { e ->
        eprintln("warning: could not remove temp directory ${e.path}")
    }

    if (!fileExists(binPath)) {
        eprintln("error: kotlinc binary not found at $binPath after installation")
        exitProcess(exitCode)
    }

    println("installed kotlinc $version at $finalPath")
}
