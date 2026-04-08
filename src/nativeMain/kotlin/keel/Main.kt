package keel

import com.github.michaelbull.result.getOrElse
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "build" -> doBuild()
        "run" -> {
            val (config, classpath) = doBuild()
            doRun(config, classpath)
        }
        else -> {
            eprintln("error: unknown command '${args[0]}'")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun printUsage() {
    eprintln("usage: keel <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  build    Compile the project")
    eprintln("  run      Build and run the project")
}

private fun loadProjectConfig(): KeelConfig {
    val tomlString = readFileAsString("keel.toml").getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(1)
    }
    return parseConfig(tomlString).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        exitProcess(1)
    }
}

private fun checkVersion(config: KeelConfig) {
    val output = executeAndCapture("kotlinc -version 2>&1").getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit,
            is ProcessError.PopenFailed,
            is ProcessError.SignalKilled -> eprintln("warning: could not determine kotlinc version")
            // Not reachable via executeAndCapture
            is ProcessError.EmptyArgs,
            is ProcessError.ForkFailed,
            is ProcessError.WaitFailed -> eprintln("warning: could not determine kotlinc version")
        }
        return
    }
    val installedVersion = parseKotlincVersion(output)
    if (installedVersion == null) {
        eprintln("warning: could not parse kotlinc version from: $output")
        return
    }
    if (installedVersion != config.kotlin) {
        eprintln("warning: keel.toml specifies kotlin ${config.kotlin}, but kotlinc $installedVersion is installed")
    }
}

private const val LOCK_FILE = "keel.lock"

private fun createResolverDeps(client: io.ktor.client.HttpClient) = object : ResolverDeps {
    override fun fileExists(path: String): Boolean = keel.fileExists(path)
    override fun ensureDirectoryRecursive(path: String) = keel.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String) = downloadFileWith(client, url, destPath)
    override fun computeSha256(filePath: String) = keel.computeSha256(filePath)
    override fun readFileContent(path: String) = readFileAsString(path)
}

private fun resolveDependencies(config: KeelConfig): String? {
    if (config.dependencies.isEmpty()) {
        if (fileExists(LOCK_FILE)) {
            deleteFile(LOCK_FILE)
        }
        return null
    }

    val home = homeDirectory().getOrElse { error ->
        eprintln("error: ${error.message}")
        exitProcess(1)
    }
    val cacheBase = "$home/.keel/cache"

    // Load existing lockfile
    val existingLock = if (fileExists(LOCK_FILE)) {
        val lockJson = readFileAsString(LOCK_FILE).getOrElse { error ->
            eprintln("warning: could not read $LOCK_FILE: ${error.path}")
            null
        }
        lockJson?.let {
            parseLockfile(it).getOrElse { error ->
                when (error) {
                    is LockfileError.ParseFailed -> eprintln("warning: ${error.message}")
                    is LockfileError.UnsupportedVersion -> eprintln("warning: unsupported lock file version ${error.version}")
                }
                null
            }
        }
    } else null

    println("resolving dependencies...")
    val resolveResult = withHttpClient { client ->
        resolve(config, existingLock, cacheBase, createResolverDeps(client))
    }.getOrElse { error ->
        when (error) {
            is ResolveError.InvalidDependency -> eprintln("error: invalid dependency '${error.input}'")
            is ResolveError.Sha256Mismatch -> {
                eprintln("error: sha256 mismatch for ${error.groupArtifact}")
                eprintln("  expected: ${error.expected}")
                eprintln("  got:      ${error.actual}")
                eprintln("delete the cached jar and rebuild to re-download")
            }
            is ResolveError.DownloadFailed -> eprintln("error: failed to download ${error.groupArtifact}")
            is ResolveError.HashComputeFailed -> eprintln("error: failed to compute hash for ${error.groupArtifact}")
            is ResolveError.DirectoryCreateFailed -> eprintln("error: could not create directory ${error.path}")
        }
        exitProcess(1)
    }

    // Write lockfile
    if (resolveResult.lockChanged) {
        val lockfile = buildLockfileFromResolved(config, resolveResult.deps)
        val lockJson = serializeLockfile(lockfile)
        writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            exitProcess(1)
        }
    }

    val paths = resolveResult.deps.map { it.cachePath }
    return buildClasspath(paths).ifEmpty { null }
}

private data class BuildResult(val config: KeelConfig, val classpath: String?)

private fun doBuild(): BuildResult {
    val config = loadProjectConfig()
    checkVersion(config)

    val classpath = resolveDependencies(config)
    val cmd = buildCommand(config, classpath)
    ensureDirectory(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(1)
    }

    println("compiling ${config.name}...")
    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> eprintln("error: compilation failed with exit code ${error.exitCode}")
            is ProcessError.EmptyArgs -> eprintln("error: no command to execute")
            is ProcessError.ForkFailed -> eprintln("error: failed to start compiler process")
            is ProcessError.WaitFailed -> eprintln("error: failed waiting for compiler process")
            is ProcessError.SignalKilled -> eprintln("error: compiler process was killed")
            // Not reachable via executeCommand
            is ProcessError.PopenFailed -> eprintln("error: failed to start compiler process")
        }
        exitProcess(1)
    }
    println("built ${cmd.outputPath}")
    return BuildResult(config, classpath)
}

private fun doRun(config: KeelConfig, classpath: String?) {
    val cmd = runCommand(config, classpath)

    if (!fileExists(cmd.jarPath)) {
        eprintln("error: ${cmd.jarPath} not found. Run 'keel build' first.")
        exitProcess(1)
    }

    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> exitProcess(error.exitCode)
            is ProcessError.EmptyArgs,
            is ProcessError.ForkFailed,
            is ProcessError.WaitFailed,
            is ProcessError.SignalKilled -> exitProcess(1)
            // Not reachable via executeCommand
            is ProcessError.PopenFailed -> exitProcess(1)
        }
    }
}
