package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.*
import kolt.build.daemon.DaemonCompilerBackend
import kolt.build.daemon.DaemonPreconditionError
import kolt.build.daemon.DaemonSetup
import kolt.build.daemon.cleanDaemonIcStateForProject
import kolt.build.daemon.formatDaemonPreconditionWarning
import kolt.build.daemon.resolveDaemonPreconditions
import kolt.config.*
import kolt.infra.*
import kolt.resolve.*
import kolt.tool.*
import kotlin.time.TimeSource

internal const val KOLT_TOML = "kolt.toml"
internal const val LOCK_FILE = "kolt.lock"
private const val WORKSPACE_JSON = "workspace.json"
private const val KLS_CLASSPATH = "kls-classpath"

internal data class BuildResult(
    val config: KoltConfig,
    val classpath: String?,
    val javaPath: String? = null,
)

internal fun filterExistingDirs(
    paths: List<String>,
    kind: String,
    exists: (String) -> Boolean = ::fileExists,
    warn: (String) -> Unit = ::eprintln
): List<String> {
    val (existing, missing) = paths.partition { exists(it) }
    for (path in missing) {
        warn("warning: $kind directory \"$path\" does not exist, skipping")
    }
    return existing
}

internal fun loadProjectConfig(): Result<KoltConfig, Int> {
    val tomlString = readFileAsString(KOLT_TOML).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        return Err(EXIT_CONFIG_ERROR)
    }
    return parseConfig(tomlString).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        return Err(EXIT_CONFIG_ERROR)
    }.let { Ok(it) }
}

internal fun doCheck(useDaemon: Boolean = true): Result<Unit, Int> {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig().getOrElse { return Err(it) }
    // konanc has no syntax-only mode; a full build is the only option.
    if (config.build.target == "native") {
        doBuild(useDaemon = useDaemon).getOrElse { return Err(it) }
        return Ok(Unit)
    }
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_BUILD_ERROR) }
    val managedKotlincBin = ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }

    val classpath = resolveDependencies(config).getOrElse { return Err(it) }
    val pArgs = resolvePluginArgs(config, paths, EXIT_BUILD_ERROR).getOrElse { eprintln("error: ${it.message}"); return Err(it.exitCode) }
    val cmd = checkCommand(config, classpath, pArgs, kotlincPath = managedKotlincBin)

    println("checking ${config.name}...")
    executeCommand(cmd).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "check"))
        return Err(EXIT_BUILD_ERROR)
    }
    val elapsed = startMark.elapsedNow()
    println("check passed in ${formatDuration(elapsed)}")
    return Ok(Unit)
}

internal fun doClean(): Result<Unit, Int> {
    val buildDirRemoved = if (fileExists(BUILD_DIR)) {
        removeDirectoryRecursive(BUILD_DIR).getOrElse { error ->
            eprintln("error: could not remove ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
        println("removed $BUILD_DIR/")
        true
    } else false

    // Daemon-owned IC state at `~/.kolt/daemon/ic/<version>/<projectId>/`
    // (ADR 0019 §5) survives `build/` removal; without this the next
    // daemon-backed build emits an empty `build/classes/` (#135).
    // Best-effort: paths/cwd lookup failure must not block clean.
    val paths = resolveKoltPaths().getOrElse { null }
    val cwd = currentWorkingDirectory()
    if (paths != null && cwd != null) {
        cleanDaemonIcStateForProject(paths, cwd).getOrElse { error ->
            eprintln("warning: could not remove daemon IC state at ${error.path}")
        }
    }

    if (!buildDirRemoved) println("nothing to clean")
    return Ok(Unit)
}

internal fun doBuild(useDaemon: Boolean = true): Result<BuildResult, Int> {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig().getOrElse { return Err(it) }

    if (config.build.target == "native") {
        return doNativeBuild(config)
    }

    val currentState = BuildState(
        configMtime = fileMtime(KOLT_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.build.sources),
        classesDirMtime = if (fileExists(CLASSES_DIR)) newestMtimeAll(CLASSES_DIR) else null,
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
        resourcesNewestMtime = if (config.build.resources.isEmpty()) null
            else config.build.resources.maxOf { newestMtimeAll(it) }
    )
    val cachedState = readFileAsString(BUILD_STATE_FILE).getOrElse { null }
        ?.let { parseBuildState(it) }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_BUILD_ERROR) }
    val (managedJavaBin, managedJarBin) = ensureJdkBinsFromConfig(config, paths).getOrElse { return Err(it) }

    if (isBuildUpToDate(current = currentState, cached = cachedState)) {
        val elapsed = startMark.elapsedNow()
        println("${config.name} is up to date (${formatDuration(elapsed)})")
        // Invariant: the up-to-date path must stay a pure mtime compare —
        // no plugin jar resolution or toolchain provisioning — so offline
        // cached builds don't hard-exit on a failed fetch.
        return Ok(BuildResult(config, cachedState!!.classpath, managedJavaBin))
    }
    val managedKotlincBin = ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }
    val classpath = resolveDependencies(config).getOrElse { return Err(it) }
    val pluginJarPathsByAlias = resolveEnabledPluginJarPaths(config, paths, EXIT_BUILD_ERROR).getOrElse { eprintln("error: ${it.message}"); return Err(it.exitCode) }
    val pArgs = pluginJarPathsByAlias.values.map { "-Xplugin=$it" }
    val pluginJarsForDaemon = pluginJarPathsByAlias.mapValues { (_, path) -> listOf(path) }
    ensureDirectoryRecursive(CLASSES_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        return Err(EXIT_BUILD_ERROR)
    }

    val cwd = currentWorkingDirectory() ?: run {
        eprintln("error: could not determine current working directory")
        return Err(EXIT_BUILD_ERROR)
    }

    val subprocessBackend = SubprocessCompilerBackend(kotlincBin = managedKotlincBin)
    val backend: CompilerBackend = resolveCompilerBackend(
        config = config,
        paths = paths,
        subprocessBackend = subprocessBackend,
        useDaemon = useDaemon,
        absProjectPath = cwd,
        pluginJars = pluginJarsForDaemon,
    )
    // Absolutise all paths: the daemon JVM persists across builds and
    // does not honour CompileRequest.workingDir (ADR 0016 §3).
    val request = CompileRequest(
        workingDir = cwd,
        // TODO(#14 S5+): teach resolveDependencies to return List<String>
        // directly and delete this split.
        classpath = if (classpath.isNullOrEmpty()) emptyList() else classpath.split(":").filter { it.isNotEmpty() },
        // BTA requires individual .kt files, not directories (#117).
        sources = expandKotlinSources(config.build.sources.map { absolutise(it, cwd) })
            .getOrElse { err ->
                eprintln("error: could not list Kotlin sources under ${err.path}")
                return Err(EXIT_BUILD_ERROR)
            },
        outputPath = absolutise(CLASSES_DIR, cwd),
        moduleName = config.name,
        extraArgs = buildList {
            add("-jvm-target")
            add(config.build.jvmTarget)
            addAll(pArgs)
        },
    )

    println("compiling ${config.name}...")
    backend.compile(request).getOrElse { error ->
        if (error is CompileError.CompilationFailed) {
            val body = renderCompilationFailure(error)
            if (body.isNotEmpty()) eprintln(body)
        }
        eprintln(formatCompileError(error, "compilation"))
        return Err(EXIT_BUILD_ERROR)
    }

    val existingResourceDirs = filterExistingDirs(config.build.resources, "resource")
    for (resourceDir in existingResourceDirs) {
        copyDirectoryContents(resourceDir, CLASSES_DIR).getOrElse { error ->
            eprintln("error: could not copy resources from ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
    }

    val jarCmd = jarCommand(config, jarPath = managedJarBin)
    executeCommand(jarCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "jar packaging"))
        return Err(EXIT_BUILD_ERROR)
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
    return Ok(BuildResult(config, classpath, managedJavaBin))
}

private fun doNativeBuild(config: KoltConfig): Result<BuildResult, Int> {
    val startMark = TimeSource.Monotonic.markNow()

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_BUILD_ERROR) }
    val managedKonancBin = ensureKonancBin(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_BUILD_ERROR) }

    val depKlibs = resolveNativeDependencies(config, paths).getOrElse { return Err(it) }

    val kexePath = outputKexePath(config)
    val defNewestMtime = newestDefMtime(config)
    val currentState = BuildState(
        configMtime = fileMtime(KOLT_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.build.sources),
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
        return Ok(BuildResult(config, classpath = null, javaPath = null))
    }

    val nativePluginArgs = resolvePluginArgs(config, paths, EXIT_BUILD_ERROR).getOrElse { eprintln("error: ${it.message}"); return Err(it.exitCode) }

    ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        return Err(EXIT_BUILD_ERROR)
    }

    val cinteropKlibs = runCinterop(config, paths).getOrElse { return Err(it) }
    val klibs = depKlibs + cinteropKlibs

    val libraryCmd = nativeLibraryCommand(config, pluginArgs = nativePluginArgs, konancPath = managedKonancBin, klibs = klibs)
    println("compiling ${config.name} (native)...")
    executeCommand(libraryCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "compilation"))
        return Err(EXIT_BUILD_ERROR)
    }

    ensureDirectoryRecursive(NATIVE_IC_CACHE_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        return Err(EXIT_BUILD_ERROR)
    }

    val linkCmd = nativeLinkCommand(config, konancPath = managedKonancBin, klibs = klibs)
    println("linking ${config.name} (native)...")
    runNativeLinkWithIcFallback(linkCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "linking"))
        return Err(EXIT_BUILD_ERROR)
    }

    if (!fileExists(kexePath)) {
        eprintln("error: $kexePath not produced by konanc")
        return Err(EXIT_BUILD_ERROR)
    }

    val newState = currentState.copy(classesDirMtime = fileMtime(kexePath))
    writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
        eprintln("warning: could not write build state file")
    }

    val elapsed = startMark.elapsedNow()
    println("built $kexePath in ${formatDuration(elapsed)}")
    return Ok(BuildResult(config, classpath = null, javaPath = null))
}

// On konanc non-zero exit, retry once after wiping the IC cache — konanc
// can't distinguish "stale cache" from "real compile error" in its exit
// code, so we treat every non-zero exit as potentially cache-induced.
// The 2x cost on genuine source errors is acceptable because source
// errors are caught at stage 1 (library), not stage 2 (link). Spike #160
// confirmed konanc handles a missing .ic-cache gracefully.
//
// Fork/wait/signal failures are outside the cache-corruption hypothesis
// (the konanc process never ran to completion), so they surface as-is.
// If wipe itself fails, the retry would hit the same stale cache — skip
// the retry and return the original error instead.
internal fun runNativeLinkWithIcFallback(
    args: List<String>,
    execute: (List<String>) -> Result<Int, ProcessError> = ::executeCommand,
    wipeCache: () -> Boolean = ::wipeNativeIcCache
): Result<Int, ProcessError> {
    val first = execute(args)
    val firstError = first.getError() ?: return first
    if (firstError !is ProcessError.NonZeroExit) return first
    if (!wipeCache()) return first
    return execute(args)
}

private fun wipeNativeIcCache(): Boolean {
    if (!fileExists(NATIVE_IC_CACHE_DIR)) return true
    val result = removeDirectoryRecursive(NATIVE_IC_CACHE_DIR)
    if (result.isOk) return true
    val error = result.getError()!!
    eprintln("warning: could not remove ${error.path}")
    return false
}

private fun newestDefMtime(config: KoltConfig): Long? {
    if (config.cinterop.isEmpty()) return null
    val mtimes = config.cinterop.mapNotNull { fileMtime(it.def) }
    return if (mtimes.isEmpty()) null else mtimes.max()
}

private fun runCinterop(config: KoltConfig, paths: KoltPaths): Result<List<String>, Int> {
    if (config.cinterop.isEmpty()) return Ok(emptyList())
    val managedCinteropBin = paths.cinteropBin(config.kotlin.effectiveCompiler)
    val klibs = mutableListOf<String>()
    for (entry in config.cinterop) {
        val klibPath = cinteropOutputKlibPath(entry)
        val stampPath = cinteropStampPath(entry)
        val defMtime = fileMtime(entry.def)
        val currentStamp = defMtime?.let { cinteropStamp(entry, it, config.kotlin.effectiveCompiler) }

        if (currentStamp != null && fileExists(klibPath) && fileExists(stampPath)) {
            val previousStamp = readFileAsString(stampPath).get()
            if (previousStamp == currentStamp) {
                klibs.add(klibPath)
                continue
            }
        }

        val cmd = cinteropCommand(entry, cinteropPath = managedCinteropBin)
        println("generating cinterop klib for ${entry.name}...")
        executeCommand(cmd.args).getOrElse { error ->
            eprintln("error: " + formatProcessError(error, "cinterop (${entry.name})"))
            return Err(EXIT_BUILD_ERROR)
        }
        if (currentStamp != null) {
            writeFileAsString(stampPath, currentStamp).getOrElse { error ->
                eprintln("warning: failed to write cinterop stamp ${error.path}")
            }
        }
        klibs.add(klibPath)
    }
    return Ok(klibs)
}

private fun resolveNativeDependencies(config: KoltConfig, paths: KoltPaths): Result<List<String>, Int> {
    if (config.dependencies.isEmpty()) return Ok(emptyList())

    println("resolving native dependencies...")
    val result = resolve(config, existingLock = null, paths.cacheBase, createResolverDeps()).getOrElse { error ->
        eprintln(formatResolveError(error))
        return Err(EXIT_DEPENDENCY_ERROR)
    }
    return Ok(result.deps.map { it.cachePath })
}

internal fun doRun(config: KoltConfig, classpath: String?, appArgs: List<String> = emptyList(), javaPath: String? = null): Result<Unit, Int> {
    if (config.build.target == "native") {
        val kexePath = outputKexePath(config)
        if (!fileExists(kexePath)) {
            eprintln("error: $kexePath not found. Run 'kolt build' first.")
            return Err(EXIT_BUILD_ERROR)
        }
        val cmd = nativeRunCommand(config, appArgs)
        executeCommand(cmd.args).getOrElse { error ->
            return Err(when (error) {
                is ProcessError.NonZeroExit -> error.exitCode
                else -> EXIT_BUILD_ERROR
            })
        }
        return Ok(Unit)
    }
    if (!fileExists(CLASSES_DIR)) {
        eprintln("error: $CLASSES_DIR not found. Run 'kolt build' first.")
        return Err(EXIT_BUILD_ERROR)
    }

    val cmd = runCommand(config, classpath, appArgs, javaPath = javaPath)
    executeCommand(cmd.args).getOrElse { error ->
        return Err(when (error) {
            is ProcessError.NonZeroExit -> error.exitCode
            else -> EXIT_BUILD_ERROR
        })
    }
    return Ok(Unit)
}

internal fun doTest(testArgs: List<String> = emptyList(), useDaemon: Boolean = true): Result<Unit, Int> {
    val config = loadProjectConfig().getOrElse { return Err(it) }
    if (config.build.target == "native") {
        return doNativeTest(config, testArgs)
    }
    val (_, classpath, javaPath) = doBuild(useDaemon = useDaemon).getOrElse { return Err(it) }

    val existingTestSources = filterExistingDirs(config.build.testSources, "test source")
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.build.testSources}")
        return Err(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_TEST_ERROR) }
    val consoleLauncherPath = ensureTool(paths, CONSOLE_LAUNCHER_SPEC).getOrElse { eprintln("error: $it"); return Err(EXIT_TEST_ERROR) }
    val managedKotlincBin = ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_TEST_ERROR) }

    val pArgs = resolvePluginArgs(config, paths, EXIT_TEST_ERROR).getOrElse { eprintln("error: ${it.message}"); return Err(it.exitCode) }

    val testConfig = config.copy(build = config.build.copy(testSources = existingTestSources))
    val testCmd = testBuildCommand(testConfig, CLASSES_DIR, classpath, pArgs, kotlincPath = managedKotlincBin)
    println("compiling tests...")
    executeCommand(testCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "test compilation"))
        return Err(EXIT_BUILD_ERROR)
    }

    val existingTestResourceDirs = filterExistingDirs(config.build.testResources, "test resource")
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
        return Err(EXIT_TEST_ERROR)
    }
    val elapsed = testStartMark.elapsedNow()
    println("tests passed in ${formatDuration(elapsed)}")
    return Ok(Unit)
}

private fun doNativeTest(config: KoltConfig, testArgs: List<String>): Result<Unit, Int> {
    val existingTestSources = filterExistingDirs(config.build.testSources, "test source")
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.build.testSources}")
        return Err(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_TEST_ERROR) }
    val managedKonancBin = ensureKonancBin(config.kotlin.effectiveCompiler, paths).getOrElse { eprintln("error: ${it.message}"); return Err(EXIT_TEST_ERROR) }
    val nativePluginArgs = resolvePluginArgs(config, paths, EXIT_TEST_ERROR).getOrElse { eprintln("error: ${it.message}"); return Err(it.exitCode) }

    val depKlibs = resolveNativeDependencies(config, paths).getOrElse { return Err(it) }

    ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        return Err(EXIT_BUILD_ERROR)
    }

    val cinteropKlibs = runCinterop(config, paths).getOrElse { return Err(it) }
    val klibs = depKlibs + cinteropKlibs

    val testConfig = config.copy(build = config.build.copy(testSources = existingTestSources))
    val libraryCmd = nativeTestLibraryCommand(testConfig, pluginArgs = nativePluginArgs, konancPath = managedKonancBin, klibs = klibs)
    println("compiling tests (native)...")
    executeCommand(libraryCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "test compilation"))
        return Err(EXIT_BUILD_ERROR)
    }

    val linkCmd = nativeTestLinkCommand(testConfig, konancPath = managedKonancBin, klibs = klibs)
    println("linking tests (native)...")
    executeCommand(linkCmd.args).getOrElse { error ->
        eprintln("error: " + formatProcessError(error, "test linking"))
        return Err(EXIT_BUILD_ERROR)
    }

    if (!fileExists(linkCmd.outputPath)) {
        eprintln("error: ${linkCmd.outputPath} not produced by konanc")
        return Err(EXIT_BUILD_ERROR)
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
        return Err(EXIT_TEST_ERROR)
    }
    val elapsed = testStartMark.elapsedNow()
    println("tests passed in ${formatDuration(elapsed)}")
    return Ok(Unit)
}

internal fun ensureJdkBinsFromConfig(config: KoltConfig, paths: KoltPaths): Result<JdkBins, Int> {
    val version = config.build.jdk ?: return Ok(JdkBins(null, null))
    return ensureJdkBins(version, paths).getOrElse { err ->
        eprintln("error: ${err.message}")
        return Err(EXIT_BUILD_ERROR)
    }.let { Ok(it) }
}

internal const val WARNING_DAEMON_DIR_UNWRITABLE =
    "warning: could not create daemon state directory — falling back to subprocess compile"

// Daemon is never load-bearing for correctness (ADR 0016 §5): any
// precondition or wiring failure degrades to the subprocess backend.
internal fun resolveCompilerBackend(
    config: KoltConfig,
    paths: KoltPaths,
    subprocessBackend: CompilerBackend,
    useDaemon: Boolean,
    absProjectPath: String,
    bundledKotlinVersion: String = BUNDLED_DAEMON_KOTLIN_VERSION,
    pluginJars: Map<String, List<String>> = emptyMap(),
    preconditionResolver: (KoltPaths, String, String, String) -> Result<DaemonSetup, DaemonPreconditionError> =
        { p, kotlincVersion, cwd, bundled ->
            resolveDaemonPreconditions(p, kotlincVersion, cwd, bundled)
        },
    daemonDirCreator: (String) -> Result<Unit, MkdirFailed> = ::ensureDirectoryRecursive,
    daemonBackendFactory: (DaemonSetup, Map<String, List<String>>) -> CompilerBackend = ::createDaemonBackend,
    warningSink: (String) -> Unit = ::eprintln,
): CompilerBackend {
    if (!useDaemon) return subprocessBackend

    val setup = preconditionResolver(
        paths, config.kotlin.effectiveCompiler, absProjectPath, bundledKotlinVersion,
    ).getOrElse { err ->
        warningSink(formatDaemonPreconditionWarning(err))
        return subprocessBackend
    }

    // spawnDetached opens the log with O_CREAT but not the parent dir;
    // create it before the daemon tries to bind the socket.
    if (daemonDirCreator(setup.daemonDir).getError() != null) {
        warningSink(WARNING_DAEMON_DIR_UNWRITABLE)
        return subprocessBackend
    }

    return FallbackCompilerBackend(
        primary = daemonBackendFactory(setup, pluginJars),
        fallback = subprocessBackend,
        onFallback = ::reportFallback,
    )
}

internal fun createDaemonBackend(
    setup: DaemonSetup,
    pluginJars: Map<String, List<String>>,
): CompilerBackend {
    // Plugin jars are baked into the daemon at spawn time. A fingerprint
    // in the socket name forces a new daemon when the plugin set changes.
    val fp = pluginsFingerprint(pluginJars)
    val socketPath = applyPluginsFingerprintToFile(setup.socketPath, fp)
    val logPath = applyPluginsFingerprintToFile(setup.logPath, fp)
    return DaemonCompilerBackend(
        javaBin = setup.javaBin,
        daemonJarPath = setup.daemonJarPath,
        compilerJars = setup.compilerJars,
        btaImplJars = setup.btaImplJars,
        socketPath = socketPath,
        logPath = logPath,
        pluginJars = pluginJars,
        onSpawn = { eprintln("starting compiler daemon...") },
    )
}

// Inner classpath order is significant (BTA plugin classpath order).
internal fun pluginsFingerprint(pluginJars: Map<String, List<String>>): String {
    if (pluginJars.isEmpty()) return "noplugins"
    val canonical = pluginJars.entries
        .sortedBy { it.key }
        .joinToString(";") { (alias, cp) -> "$alias=${cp.joinToString(":")}" }
    return kolt.infra.sha256Hex(canonical.encodeToByteArray()).take(8)
}

internal fun applyPluginsFingerprintToFile(path: String, fingerprint: String): String {
    val slash = path.lastIndexOf('/')
    val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
    val name = if (slash >= 0) path.substring(slash + 1) else path
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
        "$dir${name.substring(0, dot)}-$fingerprint${name.substring(dot)}"
    } else {
        "$dir$name-$fingerprint"
    }
}

internal fun createResolverDeps(): ResolverDeps = defaultResolverDeps()

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

internal fun resolveDependencies(config: KoltConfig): Result<String?, Int> {
    for (dep in findOverlappingDependencies(config.dependencies, config.testDependencies)) {
        eprintln("warning: '${dep.groupArtifact}' is in both [dependencies] (${dep.mainVersion}) and [test-dependencies] (${dep.testVersion}); using ${dep.mainVersion}")
    }

    val allDeps = mergeAllDeps(config)
    if (allDeps.isEmpty()) {
        if (fileExists(LOCK_FILE)) {
            deleteFile(LOCK_FILE)
        }
        return Ok(null)
    }

    val resolveConfig = config.copy(dependencies = allDeps)

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_DEPENDENCY_ERROR) }

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
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    if (resolveResult.lockChanged) {
        val lockfile = buildLockfileFromResolved(resolveConfig, resolveResult.deps)
        val lockJson = serializeLockfile(lockfile)
        writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            return Err(EXIT_DEPENDENCY_ERROR)
        }
    }

    if (resolveResult.lockChanged || !fileExists(WORKSPACE_JSON) || !fileExists(KLS_CLASSPATH)) {
        writeWorkspaceFiles(config, resolveResult.deps)
    }

    val jarPaths = resolveResult.deps.map { it.cachePath }
    return Ok(buildClasspath(jarPaths).ifEmpty { null })
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
