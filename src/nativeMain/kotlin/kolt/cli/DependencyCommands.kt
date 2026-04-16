package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.*
import kolt.config.*
import kolt.infra.*
import kolt.resolve.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getcwd
import kotlin.system.exitProcess

private const val SRC_DIR = "src"

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(args: List<String>) {
    if (fileExists(KOLT_TOML)) {
        eprintln("error: $KOLT_TOML already exists")
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

    writeFileAsString(KOLT_TOML, generateKoltToml(projectName)).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }
    println("created $KOLT_TOML")

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
            is AddArgsError.MissingCoordinate -> eprintln("usage: kolt add <group:artifact[:version]> [--test]")
            is AddArgsError.InvalidFormat -> eprintln("error: invalid coordinate '${error.input}'")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    val toml = readFileAsString(KOLT_TOML).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val config = parseConfig(toml).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val version = if (addArgs.version != null) {
        addArgs.version
    } else {
        println("fetching latest version for ${addArgs.group}:${addArgs.artifact}...")
        fetchLatestVersion(addArgs.group, addArgs.artifact, config.repositories.values.toList())
    }

    val groupArtifact = "${addArgs.group}:${addArgs.artifact}"
    val updatedToml = addDependencyToToml(toml, groupArtifact, version, addArgs.isTest).getOrElse { error ->
        when (error) {
            is AlreadyExists -> eprintln("error: '${error.groupArtifact}' already exists in ${if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"}")
        }
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    writeFileAsString(KOLT_TOML, updatedToml).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val section = if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"
    println("added $groupArtifact = \"$version\" to $section")

    doInstall()
}

private fun fetchLatestVersion(group: String, artifact: String, repos: List<String>): String {
    val paths = resolveKoltPaths(EXIT_DEPENDENCY_ERROR)
    val groupPath = group.replace('.', '/')
    val metadataPath = "${paths.cacheBase}/$groupPath/$artifact/maven-metadata.xml"

    ensureDirectoryRecursive("${paths.cacheBase}/$groupPath/$artifact").getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

    val fetchErr = downloadFromRepositories(
        repos,
        metadataPath,
        { repo -> buildMetadataDownloadUrl(group, artifact, repo) },
        ::downloadFile
    ).getError()
    if (fetchErr != null) {
        when (fetchErr) {
            is DownloadError.HttpFailed -> eprintln("error: could not fetch metadata for $group:$artifact (HTTP ${fetchErr.statusCode})")
            is DownloadError.WriteFailed -> eprintln("error: could not write metadata to ${fetchErr.path}")
            is DownloadError.NetworkError -> eprintln("error: network error fetching metadata for $group:$artifact: ${fetchErr.message}")
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

    val paths = resolveKoltPaths(EXIT_DEPENDENCY_ERROR)

    for ((groupArtifact, version) in allDeps) {
        val coord = parseCoordinate(groupArtifact, version).getOrElse { continue }
        val jarPath = "${paths.cacheBase}/${buildCachePath(coord)}"
        if (fileExists(jarPath)) {
            deleteFile(jarPath)
        }
    }

    val resolveConfig = config.copy(dependencies = allDeps)
    println("updating dependencies...")
    val resolveResult = resolve(resolveConfig, null, paths.cacheBase, createResolverDeps()).getOrElse { error ->
        eprintln(formatResolveError(error))
        exitProcess(EXIT_DEPENDENCY_ERROR)
    }

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

    val hasAnyDeps = config.dependencies.isNotEmpty() ||
        config.testDependencies.isNotEmpty() ||
        autoInjectedTestDeps(config).isNotEmpty()
    if (!hasAnyDeps) {
        println("no dependencies")
        return
    }

    val paths = resolveKoltPaths(EXIT_DEPENDENCY_ERROR)

    if (config.target == "native") {
        val nativeLookup = createNativeLookup(
            config.repositories.values.toList(),
            paths.cacheBase,
            createResolverDeps()
        )
        if (config.dependencies.isNotEmpty()) {
            val tree = buildNativeDependencyTree(config.dependencies, nativeLookup)
            println(formatDependencyTree(tree))
        }
        if (config.testDependencies.isNotEmpty()) {
            if (config.dependencies.isNotEmpty()) println()
            println("test dependencies:")
            val testTree = buildNativeDependencyTree(config.testDependencies, nativeLookup)
            println(formatDependencyTree(testTree))
        }
        return
    }

    val allTestDeps = autoInjectedTestDeps(config) + config.testDependencies
    val pomLookup = createPomLookup(config.repositories.values.toList(), paths.cacheBase, createResolverDeps())
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

internal fun validateDepsSubcommand(args: List<String>): Boolean =
    args.isNotEmpty() && args[0] == "tree"

internal fun doDeps(args: List<String>) {
    if (!validateDepsSubcommand(args)) {
        eprintln("usage: kolt deps tree")
        exitProcess(EXIT_BUILD_ERROR)
    }
    doTree()
}
