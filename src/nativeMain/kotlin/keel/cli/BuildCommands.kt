package keel.cli

import com.github.michaelbull.result.getOrElse
import keel.build.*
import keel.config.*
import keel.infra.*
import keel.resolve.*
import keel.tool.*
import kotlin.system.exitProcess
import kotlin.time.TimeSource

internal const val KEEL_TOML = "keel.toml"
internal const val LOCK_FILE = "keel.lock"
private const val WORKSPACE_JSON = "workspace.json"
private const val KLS_CLASSPATH = "kls-classpath"

internal data class BuildResult(
    val config: KeelConfig,
    val classpath: String?,
    val pluginArgs: List<String> = emptyList(),
    val javaPath: String? = null
)

internal fun loadProjectConfig(): KeelConfig {
    val tomlString = readFileAsString(KEEL_TOML).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(EXIT_CONFIG_ERROR)
    }
    return parseConfig(tomlString).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        exitProcess(EXIT_CONFIG_ERROR)
    }
}

internal fun doCheck() {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig()
    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    val managedKotlincBin = ensureKotlincBin(config.kotlin, paths, EXIT_BUILD_ERROR)

    val classpath = resolveDependencies(config)
    val pArgs = resolvePluginArgs(config, managedKotlincBin)
    val cmd = checkCommand(config, classpath, pArgs, kotlincPath = managedKotlincBin)

    println("checking ${config.name}...")
    executeCommand(cmd).getOrElse { error ->
        eprintln(formatProcessError(error, "check"))
        exitProcess(EXIT_BUILD_ERROR)
    }
    val elapsed = startMark.elapsedNow()
    println("check passed in ${formatDuration(elapsed)}")
}

internal fun doClean() {
    if (!fileExists(BUILD_DIR)) {
        println("nothing to clean")
        return
    }
    removeDirectoryRecursive(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not remove ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }
    println("removed $BUILD_DIR/")
}

internal fun doBuild(): BuildResult {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig()

    val currentState = BuildState(
        configMtime = fileMtime(KEEL_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.sources),
        classesDirMtime = if (fileExists(CLASSES_DIR)) newestMtimeAll(CLASSES_DIR) else null,
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
        resourcesNewestMtime = if (config.resources.isEmpty()) null
            else config.resources.maxOf { newestMtimeAll(it) }
    )
    val cachedState = readFileAsString(BUILD_STATE_FILE).getOrElse { null }
        ?.let { parseBuildState(it) }

    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    val managedKotlincBin = ensureKotlincBin(config.kotlin, paths, EXIT_BUILD_ERROR)
    val (managedJavaBin, managedJarBin) = ensureJdkBinsFromConfig(config, paths)

    if (isBuildUpToDate(current = currentState, cached = cachedState)) {
        val elapsed = startMark.elapsedNow()
        println("${config.name} is up to date (${formatDuration(elapsed)})")
        return BuildResult(config, cachedState!!.classpath, resolvePluginArgs(config, managedKotlincBin), managedJavaBin)
    }
    val classpath = resolveDependencies(config)
    val pArgs = resolvePluginArgs(config, managedKotlincBin)
    val buildCmd = buildCommand(config, classpath, pArgs, kotlincPath = managedKotlincBin)
    ensureDirectoryRecursive(CLASSES_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }

    println("compiling ${config.name}...")
    executeCommand(buildCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    for (resourceDir in config.resources) {
        if (!fileExists(resourceDir)) continue
        copyDirectoryContents(resourceDir, CLASSES_DIR).getOrElse { error ->
            eprintln("error: could not copy resources from ${error.path}")
            exitProcess(EXIT_BUILD_ERROR)
        }
    }

    val jarCmd = jarCommand(config, jarPath = managedJarBin)
    executeCommand(jarCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "jar packaging"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    val newState = currentState.copy(
        classesDirMtime = newestMtimeAll(CLASSES_DIR),
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
        classpath = classpath
    )
    writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
        eprintln("warning: could not write build state file")
    }

    val elapsed = startMark.elapsedNow()
    println("built ${jarCmd.outputPath} in ${formatDuration(elapsed)}")
    return BuildResult(config, classpath, pArgs, managedJavaBin)
}

internal fun doRun(config: KeelConfig, classpath: String?, appArgs: List<String> = emptyList(), javaPath: String? = null) {
    if (!fileExists(CLASSES_DIR)) {
        eprintln("error: $CLASSES_DIR not found. Run 'keel build' first.")
        exitProcess(EXIT_BUILD_ERROR)
    }

    val cmd = runCommand(config, classpath, appArgs, javaPath = javaPath)
    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> exitProcess(error.exitCode)
            else -> exitProcess(EXIT_BUILD_ERROR)
        }
    }
}

internal fun doTest(testArgs: List<String> = emptyList()) {
    val (config, classpath, pArgs, javaPath) = doBuild()

    val existingTestSources = config.testSources.filter { fileExists(it) }
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.testSources}")
        exitProcess(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    val paths = resolveKeelPaths(EXIT_TEST_ERROR)
    val consoleLauncherPath = ensureTool(paths, CONSOLE_LAUNCHER_SPEC, EXIT_TEST_ERROR)
    val managedKotlincBin = ensureKotlincBin(config.kotlin, paths, EXIT_TEST_ERROR)

    val testConfig = config.copy(testSources = existingTestSources)
    val testCmd = testBuildCommand(testConfig, CLASSES_DIR, classpath, pArgs, kotlincPath = managedKotlincBin)
    println("compiling tests...")
    executeCommand(testCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "test compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    val existingTestResourceDirs = config.testResources.filter { fileExists(it) }
    val runCmd = testRunCommand(
        classesDir = CLASSES_DIR,
        testClassesDir = testCmd.outputPath,
        consoleLauncherPath = consoleLauncherPath,
        testResourceDirs = existingTestResourceDirs,
        classpath = classpath,
        testArgs = testArgs,
        javaPath = javaPath
    )
    println("running tests...")
    executeCommand(runCmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> {
                val elapsed = testStartMark.elapsedNow()
                eprintln("tests failed in ${formatDuration(elapsed)}")
            }
            else -> eprintln("error: failed to run tests")
        }
        exitProcess(EXIT_TEST_ERROR)
    }
    val elapsed = testStartMark.elapsedNow()
    println("tests passed in ${formatDuration(elapsed)}")
}

internal fun ensureJdkBinsFromConfig(config: KeelConfig, paths: KeelPaths): JdkBins {
    val version = config.jdk ?: return JdkBins(null, null)
    return ensureJdkBins(version, paths, EXIT_BUILD_ERROR)
}

internal fun createResolverDeps() = object : ResolverDeps {
    override fun fileExists(path: String): Boolean = keel.infra.fileExists(path)
    override fun ensureDirectoryRecursive(path: String) = keel.infra.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String) = keel.infra.downloadFile(url, destPath)
    override fun computeSha256(filePath: String) = keel.infra.computeSha256(filePath)
    override fun readFileContent(path: String) = readFileAsString(path)
}

internal data class OverlappingDep(
    val groupArtifact: String,
    val mainVersion: String?,
    val testVersion: String?
)

internal fun findOverlappingDependencies(
    mainDeps: Map<String, String>,
    testDeps: Map<String, String>
): List<OverlappingDep> {
    val overlap = mainDeps.keys.intersect(testDeps.keys)
    return overlap
        .filter { mainDeps[it] != testDeps[it] }
        .map { OverlappingDep(it, mainDeps[it], testDeps[it]) }
}

internal fun resolveDependencies(config: KeelConfig): String? {
    for (dep in findOverlappingDependencies(config.dependencies, config.testDependencies)) {
        eprintln("warning: '${dep.groupArtifact}' is in both [dependencies] (${dep.mainVersion}) and [test-dependencies] (${dep.testVersion}); using ${dep.mainVersion}")
    }

    val allDeps = mergeAllDeps(config)
    if (allDeps.isEmpty()) {
        if (fileExists(LOCK_FILE)) {
            deleteFile(LOCK_FILE)
        }
        return null
    }

    val resolveConfig = config.copy(dependencies = allDeps)

    val paths = resolveKeelPaths(EXIT_DEPENDENCY_ERROR)

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
    val resolveResult = resolve(resolveConfig, existingLock, paths.cacheBase, createResolverDeps()).getOrElse { error ->
        eprintln(formatResolveError(error))
        if (error is ResolveError.Sha256Mismatch) {
            eprintln("delete the cached jar and rebuild to re-download")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    if (resolveResult.lockChanged) {
        val lockfile = buildLockfileFromResolved(resolveConfig, resolveResult.deps)
        val lockJson = serializeLockfile(lockfile)
        writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            exitProcess(EXIT_DEPENDENCY_ERROR)
        }
    }

    if (resolveResult.lockChanged || !fileExists(WORKSPACE_JSON) || !fileExists(KLS_CLASSPATH)) {
        writeWorkspaceFiles(config, resolveResult.deps)
    }

    val jarPaths = resolveResult.deps.map { it.cachePath }
    return buildClasspath(jarPaths).ifEmpty { null }
}

private fun writeWorkspaceFiles(config: KeelConfig, deps: List<ResolvedDep>) {
    val workspaceJson = generateWorkspaceJson(config, deps)
    writeFileAsString(WORKSPACE_JSON, workspaceJson).getOrElse { error ->
        eprintln("warning: could not write $WORKSPACE_JSON: ${error.path}")
        return
    }

    val klsContent = generateKlsClasspath(deps)
    writeFileAsString(KLS_CLASSPATH, klsContent).getOrElse { error ->
        eprintln("warning: could not write $KLS_CLASSPATH: ${error.path}")
        return
    }
}
