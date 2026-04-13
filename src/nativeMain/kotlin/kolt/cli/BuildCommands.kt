package kolt.cli

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import kolt.build.*
import kolt.config.*
import kolt.infra.*
import kolt.resolve.*
import kolt.tool.*
import kotlin.system.exitProcess
import kotlin.time.TimeSource

internal const val KOLT_TOML = "kolt.toml"
internal const val LOCK_FILE = "kolt.lock"
private const val WORKSPACE_JSON = "workspace.json"
private const val KLS_CLASSPATH = "kls-classpath"

internal data class BuildResult(
    val config: KoltConfig,
    val classpath: String?,
    val pluginArgs: List<String> = emptyList(),
    val javaPath: String? = null
)

internal fun loadProjectConfig(): KoltConfig {
    val tomlString = readFileAsString(KOLT_TOML).getOrElse { error ->
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
    // For native target, check is equivalent to a full build (konanc has no
    // syntax-only mode). Delegate to doBuild and discard its BuildResult —
    // check has no further use for it.
    if (config.target == "native") {
        doBuild()
        return
    }
    val paths = resolveKoltPaths(EXIT_BUILD_ERROR)
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

    if (config.target == "native") {
        return doNativeBuild(config)
    }

    val currentState = BuildState(
        configMtime = fileMtime(KOLT_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.sources),
        classesDirMtime = if (fileExists(CLASSES_DIR)) newestMtimeAll(CLASSES_DIR) else null,
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
        resourcesNewestMtime = if (config.resources.isEmpty()) null
            else config.resources.maxOf { newestMtimeAll(it) }
    )
    val cachedState = readFileAsString(BUILD_STATE_FILE).getOrElse { null }
        ?.let { parseBuildState(it) }

    val paths = resolveKoltPaths(EXIT_BUILD_ERROR)
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

private fun doNativeBuild(config: KoltConfig): BuildResult {
    val startMark = TimeSource.Monotonic.markNow()

    if (needsNativeEntryPointWarning(config)) {
        eprintln(
            "warning: main = \"${config.main}\" does not end with 'Kt'; " +
                "native build assumes a top-level 'fun main' in package " +
                "'${nativeEntryPoint(config).substringBeforeLast('.', "")}' " +
                "(derived entry point: ${nativeEntryPoint(config)})"
        )
    }

    val paths = resolveKoltPaths(EXIT_BUILD_ERROR)
    val managedKonancBin = ensureKonancBin(config.kotlin, paths, EXIT_BUILD_ERROR)

    val depKlibs = resolveNativeDependencies(config, paths)

    val kexePath = outputKexePath(config)
    val defNewestMtime = newestDefMtime(config)
    val currentState = BuildState(
        configMtime = fileMtime(KOLT_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.sources),
        classesDirMtime = if (fileExists(kexePath)) fileMtime(kexePath) else null,
        lockfileMtime = null,
        resourcesNewestMtime = null,
        defNewestMtime = defNewestMtime
    )
    val cachedState = readFileAsString(BUILD_STATE_FILE).getOrElse { null }
        ?.let { parseBuildState(it) }

    if (isBuildUpToDate(current = currentState, cached = cachedState)) {
        val elapsed = startMark.elapsedNow()
        println("${config.name} is up to date (${formatDuration(elapsed)})")
        return BuildResult(config, classpath = null, pluginArgs = emptyList(), javaPath = null)
    }

    // Resolved only on a real rebuild so cached builds don't provision kotlinc.
    val nativePluginArgs = resolveNativePluginArgs(config, paths, EXIT_BUILD_ERROR)

    ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }

    val cinteropKlibs = runCinterop(config, paths)
    val klibs = depKlibs + cinteropKlibs

    val libraryCmd = nativeLibraryCommand(config, pluginArgs = nativePluginArgs, konancPath = managedKonancBin, klibs = klibs)
    println("compiling ${config.name} (native)...")
    executeCommand(libraryCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    val linkCmd = nativeLinkCommand(config, konancPath = managedKonancBin, klibs = klibs)
    println("linking ${config.name} (native)...")
    executeCommand(linkCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "linking"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    if (!fileExists(kexePath)) {
        eprintln("error: $kexePath not produced by konanc")
        exitProcess(EXIT_BUILD_ERROR)
    }

    val newState = currentState.copy(classesDirMtime = fileMtime(kexePath))
    writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
        eprintln("warning: could not write build state file")
    }

    val elapsed = startMark.elapsedNow()
    println("built $kexePath in ${formatDuration(elapsed)}")
    return BuildResult(config, classpath = null, pluginArgs = nativePluginArgs, javaPath = null)
}

/** Returns the newest mtime among all .def files declared in cinterop entries, or null if none. */
private fun newestDefMtime(config: KoltConfig): Long? {
    if (config.cinterop.isEmpty()) return null
    val mtimes = config.cinterop.mapNotNull { fileMtime(it.def) }
    return if (mtimes.isEmpty()) null else mtimes.max()
}

/**
 * Runs `cinterop` for each [[cinterop]] entry in the config.
 *
 * Per-entry freshness check: if the previously generated klib and its sidecar
 * `.klib.stamp` file both exist and the stamp matches the stamp that would be
 * produced by the current entry + its .def mtime, the cinterop invocation is
 * skipped and the cached klib is reused. The stamp observes every field of
 * CinteropConfig, so editing compiler_options / linker_options in kolt.toml
 * invalidates the cache even though the .def file is untouched.
 *
 * Returns the list of generated .klib paths to pass to konanc via -l.
 */
private fun runCinterop(config: KoltConfig, paths: KoltPaths): List<String> {
    if (config.cinterop.isEmpty()) return emptyList()
    val managedCinteropBin = paths.cinteropBin(config.kotlin)
    return config.cinterop.map { entry ->
        val klibPath = cinteropOutputKlibPath(entry)
        val stampPath = cinteropStampPath(entry)
        val defMtime = fileMtime(entry.def)
        val currentStamp = defMtime?.let { cinteropStamp(entry, it) }

        if (currentStamp != null && fileExists(klibPath) && fileExists(stampPath)) {
            val previousStamp = readFileAsString(stampPath).get()
            if (previousStamp == currentStamp) {
                return@map klibPath
            }
        }

        val cmd = cinteropCommand(entry, cinteropPath = managedCinteropBin)
        println("generating cinterop klib for ${entry.name}...")
        executeCommand(cmd.args).getOrElse { error ->
            eprintln(formatProcessError(error, "cinterop (${entry.name})"))
            exitProcess(EXIT_BUILD_ERROR)
        }
        if (currentStamp != null) {
            writeFileAsString(stampPath, currentStamp).getOrElse { error ->
                // Stamp write failure is non-fatal: the next build will simply
                // re-run cinterop instead of reusing the cached klib.
                eprintln("warning: failed to write cinterop stamp ${error.path}")
            }
        }
        klibPath
    }
}

/**
 * Resolves direct + transitive Kotlin/Native dependencies and returns their
 * on-disk `.klib` paths. Unlike [resolveDependencies] for jvm, this does not
 * read or write `kolt.lock` (lockfile support for native lands later).
 */
internal fun resolveNativeDependencies(config: KoltConfig, paths: KoltPaths): List<String> {
    if (config.dependencies.isEmpty()) return emptyList()

    println("resolving native dependencies...")
    val result = resolve(config, existingLock = null, paths.cacheBase, createResolverDeps()).getOrElse { error ->
        eprintln(formatResolveError(error))
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }
    return result.deps.map { it.cachePath }
}

internal fun doRun(config: KoltConfig, classpath: String?, appArgs: List<String> = emptyList(), javaPath: String? = null) {
    if (config.target == "native") {
        val kexePath = outputKexePath(config)
        if (!fileExists(kexePath)) {
            eprintln("error: $kexePath not found. Run 'kolt build' first.")
            exitProcess(EXIT_BUILD_ERROR)
        }
        val cmd = nativeRunCommand(config, appArgs)
        executeCommand(cmd.args).getOrElse { error ->
            when (error) {
                is ProcessError.NonZeroExit -> exitProcess(error.exitCode)
                else -> exitProcess(EXIT_BUILD_ERROR)
            }
        }
        return
    }

    if (!fileExists(CLASSES_DIR)) {
        eprintln("error: $CLASSES_DIR not found. Run 'kolt build' first.")
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
    // Load the config once up front so we can dispatch on target before doing
    // any compilation work. The native path compiles main + test in a single
    // konanc invocation and therefore bypasses doBuild() entirely, unlike the
    // jvm path which reuses the build artifacts from doBuild().
    val config = loadProjectConfig()
    if (config.target == "native") {
        doNativeTest(config, testArgs)
        return
    }
    val (_, classpath, pArgs, javaPath) = doBuild()

    val existingTestSources = config.testSources.filter { fileExists(it) }
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.testSources}")
        exitProcess(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    val paths = resolveKoltPaths(EXIT_TEST_ERROR)
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

private fun doNativeTest(config: KoltConfig, testArgs: List<String>) {
    val existingTestSources = config.testSources.filter { fileExists(it) }
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.testSources}")
        exitProcess(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    val paths = resolveKoltPaths(EXIT_TEST_ERROR)
    val managedKonancBin = ensureKonancBin(config.kotlin, paths, EXIT_TEST_ERROR)
    val nativePluginArgs = resolveNativePluginArgs(config, paths, EXIT_TEST_ERROR)

    val depKlibs = resolveNativeDependencies(config, paths)

    ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }

    val cinteropKlibs = runCinterop(config, paths)
    val klibs = depKlibs + cinteropKlibs

    val testConfig = config.copy(testSources = existingTestSources)
    val libraryCmd = nativeTestLibraryCommand(testConfig, pluginArgs = nativePluginArgs, konancPath = managedKonancBin, klibs = klibs)
    println("compiling tests (native)...")
    executeCommand(libraryCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "test compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    val linkCmd = nativeTestLinkCommand(testConfig, konancPath = managedKonancBin, klibs = klibs)
    println("linking tests (native)...")
    executeCommand(linkCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "test linking"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    if (!fileExists(linkCmd.outputPath)) {
        eprintln("error: ${linkCmd.outputPath} not produced by konanc")
        exitProcess(EXIT_BUILD_ERROR)
    }

    val runCmd = nativeTestRunCommand(testConfig, testArgs)
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

internal fun ensureJdkBinsFromConfig(config: KoltConfig, paths: KoltPaths): JdkBins {
    val version = config.jdk ?: return JdkBins(null, null)
    return ensureJdkBins(version, paths, EXIT_BUILD_ERROR)
}

internal fun createResolverDeps() = object : ResolverDeps {
    override fun fileExists(path: String): Boolean = kolt.infra.fileExists(path)
    override fun ensureDirectoryRecursive(path: String) = kolt.infra.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String) = kolt.infra.downloadFile(url, destPath)
    override fun computeSha256(filePath: String) = kolt.infra.computeSha256(filePath)
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

internal fun resolveDependencies(config: KoltConfig): String? {
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

    val paths = resolveKoltPaths(EXIT_DEPENDENCY_ERROR)

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

private fun writeWorkspaceFiles(config: KoltConfig, deps: List<ResolvedDep>) {
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
