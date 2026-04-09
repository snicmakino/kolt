package keel

import com.github.michaelbull.result.getOrElse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getcwd
import kotlin.system.exitProcess
import kotlin.time.TimeSource

internal const val KEEL_TOML = "keel.toml"
private const val SRC_DIR = "src"
internal const val LOCK_FILE = "keel.lock"
private const val WORKSPACE_JSON = "workspace.json"
private const val KLS_CLASSPATH = "kls-classpath"

internal data class BuildResult(val config: KeelConfig, val classpath: String?)

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(args: List<String>) {
    if (fileExists(KEEL_TOML)) {
        eprintln("error: $KEEL_TOML already exists")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val projectName = if (args.isNotEmpty()) {
        args[0]
    } else {
        val cwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString()
        }
        if (cwd == null) {
            eprintln("error: could not determine current directory")
            exitProcess(EXIT_CONFIG_ERROR)
        }
        inferProjectName(cwd)
    }

    if (!isValidProjectName(projectName)) {
        eprintln("error: invalid project name '$projectName'")
        eprintln("  project name must start with a letter or digit and contain only letters, digits, '.', '-', '_'")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    writeFileAsString(KEEL_TOML, generateKeelToml(projectName)).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }
    println("created $KEEL_TOML")

    if (!fileExists(SRC_DIR)) {
        ensureDirectory(SRC_DIR).getOrElse { error ->
            eprintln("error: could not create directory ${error.path}")
            exitProcess(EXIT_BUILD_ERROR)
        }
    }

    val mainKtPath = "$SRC_DIR/Main.kt"
    if (!fileExists(mainKtPath)) {
        writeFileAsString(mainKtPath, generateMainKt()).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            exitProcess(EXIT_BUILD_ERROR)
        }
        println("created $mainKtPath")
    }

    val testDir = "test"
    if (!fileExists(testDir)) {
        ensureDirectory(testDir).getOrElse { error ->
            eprintln("error: could not create directory ${error.path}")
            exitProcess(EXIT_BUILD_ERROR)
        }
    }

    val testKtPath = "$testDir/MainTest.kt"
    if (!fileExists(testKtPath)) {
        writeFileAsString(testKtPath, generateTestKt()).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            exitProcess(EXIT_BUILD_ERROR)
        }
        println("created $testKtPath")
    }

    println("initialized project '$projectName'")
}

internal fun doAdd(args: List<String>) {
    val addArgs = parseAddArgs(args).getOrElse { error ->
        when (error) {
            is AddArgsError.MissingCoordinate -> eprintln("usage: keel add <group:artifact[:version]> [--test]")
            is AddArgsError.InvalidFormat -> eprintln("error: invalid coordinate '${error.input}'")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    val version = if (addArgs.version != null) {
        addArgs.version
    } else {
        println("fetching latest version for ${addArgs.group}:${addArgs.artifact}...")
        fetchLatestVersion(addArgs.group, addArgs.artifact)
    }

    val groupArtifact = "${addArgs.group}:${addArgs.artifact}"
    val toml = readFileAsString(KEEL_TOML).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val updatedToml = addDependencyToToml(toml, groupArtifact, version, addArgs.isTest).getOrElse { error ->
        when (error) {
            is AlreadyExists -> eprintln("error: '${error.groupArtifact}' already exists in ${if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"}")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    writeFileAsString(KEEL_TOML, updatedToml).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val section = if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"
    println("added $groupArtifact = \"$version\" to $section")

    doInstall()
}

private fun fetchLatestVersion(group: String, artifact: String): String {
    val paths = resolveKeelPaths(EXIT_DEPENDENCY_ERROR)
    val groupPath = group.replace('.', '/')
    val metadataPath = "${paths.cacheBase}/$groupPath/$artifact/maven-metadata.xml"

    val url = buildMetadataDownloadUrl(group, artifact)
    ensureDirectoryRecursive("${paths.cacheBase}/$groupPath/$artifact").getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    downloadFile(url, metadataPath).getOrElse { error ->
        when (error) {
            is DownloadError.HttpFailed -> eprintln("error: could not fetch metadata for $group:$artifact (HTTP ${error.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write metadata to ${error.path}")
            is DownloadError.NetworkError -> eprintln("error: network error fetching metadata for $group:$artifact: ${error.message}")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    val xml = readFileAsString(metadataPath).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    return parseMetadataXml(xml).getOrElse { error ->
        eprintln("error: ${error.message}")
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }
}

internal fun doInstall() {
    val config = loadProjectConfig()
    resolveDependencies(config)
    println("install complete")
}

internal fun doUpdate() {
    val config = loadProjectConfig()
    val allDeps = mergeAllDeps(config)
    if (allDeps.isEmpty()) {
        println("no dependencies to update")
        return
    }

    val paths = resolveKeelPaths(EXIT_DEPENDENCY_ERROR)

    // Delete cached JARs to force re-download
    for ((groupArtifact, version) in allDeps) {
        val coord = parseCoordinate(groupArtifact, version).getOrElse { continue }
        val jarPath = "${paths.cacheBase}/${buildCachePath(coord)}"
        if (fileExists(jarPath)) {
            deleteFile(jarPath)
        }
    }

    // Resolve without existing lockfile to get fresh state
    val resolveConfig = config.copy(dependencies = allDeps)
    println("updating dependencies...")
    val resolveResult = resolve(resolveConfig, null, paths.cacheBase, createResolverDeps()).getOrElse { error ->
        when (error) {
            is ResolveError.InvalidDependency -> eprintln("error: invalid dependency '${error.input}'")
            is ResolveError.Sha256Mismatch -> {
                eprintln("error: sha256 mismatch for ${error.groupArtifact}")
                eprintln("  expected: ${error.expected}")
                eprintln("  got:      ${error.actual}")
            }
            is ResolveError.DownloadFailed -> eprintln("error: failed to download ${error.groupArtifact}")
            is ResolveError.HashComputeFailed -> eprintln("error: failed to compute hash for ${error.groupArtifact}")
            is ResolveError.DirectoryCreateFailed -> eprintln("error: could not create directory ${error.path}")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    // Always write lockfile
    val lockfile = buildLockfileFromResolved(resolveConfig, resolveResult.deps)
    val lockJson = serializeLockfile(lockfile)
    writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }
    println("updated ${resolveResult.deps.size} dependencies")
}

internal fun doTree() {
    val config = loadProjectConfig()
    val allTestDeps = autoInjectedTestDeps(config) + config.testDependencies
    if (config.dependencies.isEmpty() && allTestDeps.isEmpty()) {
        println("no dependencies")
        return
    }

    val paths = resolveKeelPaths(EXIT_DEPENDENCY_ERROR)

    val pomLookup = createPomLookup(paths.cacheBase, createResolverDeps())
    if (config.dependencies.isNotEmpty()) {
        val tree = buildDependencyTree(config.dependencies, pomLookup)
        println(formatDependencyTree(tree))
    }
    if (allTestDeps.isNotEmpty()) {
        if (config.dependencies.isNotEmpty()) println()
        println("test dependencies:")
        val testTree = buildDependencyTree(allTestDeps, pomLookup)
        println(formatDependencyTree(testTree))
    }
}

internal fun doDeps(args: List<String>) {
    if (args.isEmpty() || args[0] != "tree") {
        eprintln("usage: keel deps tree")
        exitProcess(EXIT_BUILD_ERROR)
    }
    doTree()
}

internal fun doCheck() {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig()
    checkVersion(config)

    val classpath = resolveDependencies(config)
    val cmd = checkCommand(config, classpath)

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

internal fun loadProjectConfig(): KeelConfig {
    val tomlString = readFileAsString("keel.toml").getOrElse { error ->
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

private fun checkVersion(config: KeelConfig) {
    val output = executeAndCapture("kotlinc -version 2>&1").getOrElse {
        eprintln("warning: could not determine kotlinc version")
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

internal fun doFmt(args: List<String>) {
    val checkOnly = "--check" in args

    val config = loadProjectConfig()

    val paths = resolveKeelPaths(EXIT_FORMAT_ERROR)
    val ktfmtPath = ensureTool(paths, KTFMT_SPEC, EXIT_FORMAT_ERROR)

    val files = buildList {
        for (dir in config.sources) {
            if (isDirectory(dir)) {
                addAll(listKotlinFiles(dir).getOrElse { error ->
                    eprintln("error: could not read directory ${error.path}")
                    exitProcess(EXIT_FORMAT_ERROR)
                })
            }
        }
        for (dir in config.testSources) {
            if (isDirectory(dir)) {
                addAll(listKotlinFiles(dir).getOrElse { error ->
                    eprintln("error: could not read directory ${error.path}")
                    exitProcess(EXIT_FORMAT_ERROR)
                })
            }
        }
    }

    if (files.isEmpty()) {
        println("no kotlin files to format")
        return
    }

    val cmd = formatCommand(ktfmtPath, files, checkOnly, style = config.fmtStyle)

    if (checkOnly) {
        println("checking format...")
    } else {
        println("formatting ${files.size} files...")
    }

    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> eprintln(if (checkOnly) "error: format check failed" else "error: formatting failed")
            else -> eprintln("error: failed to run ktfmt")
        }
        exitProcess(EXIT_FORMAT_ERROR)
    }

    if (checkOnly) {
        println("format check passed")
    } else {
        println("formatted ${files.size} files")
    }
}

internal fun createResolverDeps() = object : ResolverDeps {
    override fun fileExists(path: String): Boolean = keel.fileExists(path)
    override fun ensureDirectoryRecursive(path: String) = keel.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String) = keel.downloadFile(url, destPath)
    override fun computeSha256(filePath: String) = keel.computeSha256(filePath)
    override fun readFileContent(path: String) = readFileAsString(path)
}

internal fun resolveDependencies(config: KeelConfig): String? {
    // Warn if the same dependency appears in both [dependencies] and [test-dependencies]
    val overlap = config.dependencies.keys.intersect(config.testDependencies.keys)
    for (key in overlap) {
        val mainVersion = config.dependencies[key]
        val testVersion = config.testDependencies[key]
        if (mainVersion != testVersion) {
            eprintln("warning: '$key' is in both [dependencies] ($mainVersion) and [test-dependencies] ($testVersion); using $mainVersion")
        }
    }

    val allDeps = mergeAllDeps(config)
    if (allDeps.isEmpty()) {
        if (fileExists(LOCK_FILE)) {
            deleteFile(LOCK_FILE)
        }
        return null
    }

    // Resolve main + test + auto-injected dependencies together for a consistent lockfile
    val resolveConfig = config.copy(dependencies = allDeps)

    val paths = resolveKeelPaths(EXIT_DEPENDENCY_ERROR)

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
    val resolveResult = resolve(resolveConfig, existingLock, paths.cacheBase, createResolverDeps()).getOrElse { error ->
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
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    // Write lockfile
    if (resolveResult.lockChanged) {
        val lockfile = buildLockfileFromResolved(resolveConfig, resolveResult.deps)
        val lockJson = serializeLockfile(lockfile)
        writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            exitProcess(EXIT_DEPENDENCY_ERROR)
        }
    }

    // Generate workspace.json and kls-classpath when lockfile changes or files are missing
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

internal fun doTest(testArgs: List<String> = emptyList()) {
    // Build main sources first (also resolves main + test deps together)
    val (config, classpath) = doBuild()

    // Filter to test source directories that actually exist
    val existingTestSources = config.testSources.filter { fileExists(it) }
    if (existingTestSources.isEmpty()) {
        eprintln("error: no test sources found in ${config.testSources}")
        exitProcess(EXIT_TEST_ERROR)
    }

    val testStartMark = TimeSource.Monotonic.markNow()

    // Ensure JUnit Platform Console Standalone is available
    val paths = resolveKeelPaths(EXIT_TEST_ERROR)
    val consoleLauncherPath = ensureTool(paths, CONSOLE_LAUNCHER_SPEC, EXIT_TEST_ERROR)

    // Compile test sources (use config with only existing test source dirs)
    val testConfig = config.copy(testSources = existingTestSources)
    val mainJar = jarPath(config)
    val testCmd = testBuildCommand(testConfig, mainJar, classpath)
    println("compiling tests...")
    executeCommand(testCmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "test compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    // Run tests (classpath already includes main + test deps from unified resolution)
    val runCmd = testRunCommand(mainJar, testCmd.outputPath, consoleLauncherPath, classpath, testArgs)
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

internal fun doBuild(): BuildResult {
    val startMark = TimeSource.Monotonic.markNow()
    val config = loadProjectConfig()

    val outputPath = jarPath(config)

    // Check if build is up-to-date via mtime comparison (before dependency resolution)
    val currentState = BuildState(
        configMtime = fileMtime(KEEL_TOML) ?: 0L,
        sourcesNewestMtime = newestMtime(config.sources),
        outputMtime = fileMtime(outputPath),
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null
    )
    val cachedState = readFileAsString(BUILD_STATE_FILE).getOrElse { null }
        ?.let { parseBuildState(it) }

    if (isBuildUpToDate(current = currentState, cached = cachedState)) {
        val elapsed = startMark.elapsedNow()
        println("${config.name} is up to date (${formatDuration(elapsed)})")
        return BuildResult(config, cachedState!!.classpath)
    }

    checkVersion(config)
    val classpath = resolveDependencies(config)
    val cmd = buildCommand(config, classpath)
    ensureDirectory(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }

    println("compiling ${config.name}...")
    executeCommand(cmd.args).getOrElse { error ->
        eprintln(formatProcessError(error, "compilation"))
        exitProcess(EXIT_BUILD_ERROR)
    }

    // Save build state after successful compilation
    val newState = currentState.copy(
        outputMtime = fileMtime(cmd.outputPath),
        lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
        classpath = classpath
    )
    writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
        eprintln("warning: could not write build state file")
    }

    val elapsed = startMark.elapsedNow()
    println("built ${cmd.outputPath} in ${formatDuration(elapsed)}")
    return BuildResult(config, classpath)
}

internal fun doRun(config: KeelConfig, classpath: String?, appArgs: List<String> = emptyList()) {
    val cmd = runCommand(config, classpath, appArgs)

    if (!fileExists(cmd.jarPath)) {
        eprintln("error: ${cmd.jarPath} not found. Run 'keel build' first.")
        exitProcess(EXIT_BUILD_ERROR)
    }

    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> exitProcess(error.exitCode)
            else -> exitProcess(EXIT_BUILD_ERROR)
        }
    }
}
