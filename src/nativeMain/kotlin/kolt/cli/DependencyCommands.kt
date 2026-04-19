package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
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

private const val SRC_DIR = "src"

@OptIn(ExperimentalForeignApi::class)
internal fun doInit(args: List<String>): Result<Unit, Int> {
    if (fileExists(KOLT_TOML)) {
        eprintln("error: $KOLT_TOML already exists")
        return Err(EXIT_CONFIG_ERROR)
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
            return Err(EXIT_CONFIG_ERROR)
        }
        inferProjectName(cwd)
    }

    if (!isValidProjectName(projectName)) {
        eprintln("error: invalid project name '$projectName'")
        eprintln("  project name must start with a letter or digit and contain only letters, digits, '.', '-', '_'")
        return Err(EXIT_CONFIG_ERROR)
    }

    writeFileAsString(KOLT_TOML, generateKoltToml(projectName)).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        return Err(EXIT_BUILD_ERROR)
    }
    println("created $KOLT_TOML")

    if (!fileExists(SRC_DIR)) {
        ensureDirectory(SRC_DIR).getOrElse { error ->
            eprintln("error: could not create directory ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
    }

    val mainKtPath = "$SRC_DIR/Main.kt"
    if (!fileExists(mainKtPath)) {
        writeFileAsString(mainKtPath, generateMainKt()).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
        println("created $mainKtPath")
    }

    val testDir = "test"
    if (!fileExists(testDir)) {
        ensureDirectory(testDir).getOrElse { error ->
            eprintln("error: could not create directory ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
    }

    val testKtPath = "$testDir/MainTest.kt"
    if (!fileExists(testKtPath)) {
        writeFileAsString(testKtPath, generateTestKt()).getOrElse { error ->
            eprintln("error: could not write ${error.path}")
            return Err(EXIT_BUILD_ERROR)
        }
        println("created $testKtPath")
    }

    println("initialized project '$projectName'")
    return Ok(Unit)
}

internal fun doAdd(args: List<String>): Result<Unit, Int> {
    val addArgs = parseAddArgs(args).getOrElse { error ->
        when (error) {
            is AddArgsError.MissingCoordinate -> eprintln("usage: kolt add <group:artifact[:version]> [--test]")
            is AddArgsError.InvalidFormat -> eprintln("error: invalid coordinate '${error.input}'")
        }
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    val toml = readFileAsString(KOLT_TOML).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        return Err(EXIT_CONFIG_ERROR)
    }

    val config = parseConfig(toml).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        return Err(EXIT_CONFIG_ERROR)
    }

    val version = if (addArgs.version != null) {
        addArgs.version
    } else {
        println("fetching latest version for ${addArgs.group}:${addArgs.artifact}...")
        fetchLatestVersion(addArgs.group, addArgs.artifact, config.repositories.values.toList())
            .getOrElse { return Err(it) }
    }

    val groupArtifact = "${addArgs.group}:${addArgs.artifact}"
    val updatedToml = addDependencyToToml(toml, groupArtifact, version, addArgs.isTest).getOrElse { error ->
        when (error) {
            is AlreadyExists -> eprintln("error: '${error.groupArtifact}' already exists in ${if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"}")
        }
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    writeFileAsString(KOLT_TOML, updatedToml).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        return Err(EXIT_CONFIG_ERROR)
    }

    val section = if (addArgs.isTest) "[test-dependencies]" else "[dependencies]"
    println("added $groupArtifact = \"$version\" to $section")

    return doInstall()
}

private fun fetchLatestVersion(group: String, artifact: String, repos: List<String>): Result<String, Int> {
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_DEPENDENCY_ERROR) }
    val groupPath = group.replace('.', '/')
    val metadataPath = "${paths.cacheBase}/$groupPath/$artifact/maven-metadata.xml"

    ensureDirectoryRecursive("${paths.cacheBase}/$groupPath/$artifact").getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        return Err(EXIT_DEPENDENCY_ERROR)
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
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    val xml = readFileAsString(metadataPath).getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    return parseMetadataXml(xml).getOrElse { error ->
        eprintln("error: ${error.message}")
        return Err(EXIT_DEPENDENCY_ERROR)
    }.let { Ok(it) }
}

internal fun doInstall(): Result<Unit, Int> {
    val config = loadProjectConfig().getOrElse { return Err(it) }
    resolveDependencies(config).getOrElse { return Err(it) }
    println("install complete")
    return Ok(Unit)
}

internal fun doUpdate(): Result<Unit, Int> {
    val config = loadProjectConfig().getOrElse { return Err(it) }
    val allDeps = mergeAllDeps(config)
    if (allDeps.isEmpty()) {
        println("no dependencies to update")
        return Ok(Unit)
    }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_DEPENDENCY_ERROR) }

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
        return Err(EXIT_DEPENDENCY_ERROR)
    }

    val lockfile = buildLockfileFromResolved(resolveConfig, resolveResult.deps)
    val lockJson = serializeLockfile(lockfile)
    writeFileAsString(LOCK_FILE, lockJson).getOrElse { error ->
        eprintln("error: could not write ${error.path}")
        return Err(EXIT_DEPENDENCY_ERROR)
    }
    println("updated ${resolveResult.deps.size} dependencies")
    return Ok(Unit)
}

internal fun doTree(): Result<Unit, Int> {
    val config = loadProjectConfig().getOrElse { return Err(it) }

    val hasAnyDeps = config.dependencies.isNotEmpty() ||
        config.testDependencies.isNotEmpty() ||
        autoInjectedTestDeps(config).isNotEmpty()
    if (!hasAnyDeps) {
        println("no dependencies")
        return Ok(Unit)
    }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_DEPENDENCY_ERROR) }

    if (config.build.target in NATIVE_TARGETS) {
        val nativeLookup = createNativeLookup(
            config.repositories.values.toList(),
            paths.cacheBase,
            createResolverDeps(),
            nativeTarget = konanTargetGradleName(config.build.target)
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
        return Ok(Unit)
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
    return Ok(Unit)
}

private val DEPS_SUBCOMMANDS = setOf("add", "install", "update", "tree")

internal fun validateDepsSubcommand(args: List<String>): Boolean =
    args.isNotEmpty() && args[0] in DEPS_SUBCOMMANDS

internal fun doDeps(args: List<String>): Result<Unit, Int> {
    if (!validateDepsSubcommand(args)) {
        printDepsUsage()
        return Err(EXIT_BUILD_ERROR)
    }
    return when (args[0]) {
        "add" -> doAdd(args.drop(1))
        "install" -> doInstall()
        "update" -> doUpdate()
        "tree" -> doTree()
        else -> Ok(Unit)
    }
}

private fun printDepsUsage() {
    eprintln("usage: kolt deps <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  add        Add a dependency (e.g. kolt deps add group:artifact:version)")
    eprintln("  install    Resolve dependencies and download JARs")
    eprintln("  update     Re-resolve dependencies and update lockfile")
    eprintln("  tree       Show dependency tree")
}
