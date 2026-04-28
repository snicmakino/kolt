package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.*
import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.build.daemon.DaemonCompilerBackend
import kolt.build.daemon.DaemonPreconditionError
import kolt.build.daemon.DaemonSetup
import kolt.build.daemon.cleanDaemonIcStateForProject
import kolt.build.daemon.formatDaemonPreconditionWarning
import kolt.build.daemon.resolveDaemonPreconditions
import kolt.build.daemon.wipeNativeIcCache
import kolt.build.nativedaemon.NativeDaemonBackend
import kolt.build.nativedaemon.NativeDaemonPreconditionError
import kolt.build.nativedaemon.NativeDaemonSetup
import kolt.build.nativedaemon.formatNativeDaemonPreconditionWarning
import kolt.build.nativedaemon.resolveNativeDaemonPreconditions
import kolt.concurrency.LockError
import kolt.concurrency.LockHandle
import kolt.concurrency.ProjectLock
import kolt.config.*
import kolt.infra.*
import kolt.resolve.buildClasspath
import kolt.tool.*
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal const val KOLT_TOML = "kolt.toml"

// `classpath` is the main closure, `testClasspath` is main ∪ test. The
// split lets doRun / watch-run launch against main-only while doTest
// compiles and runs against the union without a second resolver walk.
// Both are null for native targets.
internal data class BuildResult(
  val config: KoltConfig,
  val classpath: String?,
  val javaPath: String? = null,
  // JDK install root, propagated as `JAVA_HOME` to any downstream kotlinc
  // subprocess. Null on Native builds where no JVM is provisioned.
  val javaHome: String? = null,
  val testClasspath: String? = null,
)

// ADR 0027 §4 kind/target matrix: JVM `kind = "app"` emits
// `build/<name>-runtime.classpath`; every other combination (JVM lib,
// native app, native lib) must not. A residual manifest from a previous
// kind=app build is deleted so `assemble-dist.sh` never picks up a stale
// classpath after a kind flip (design.md §Components → JVM app tail
// branch, "stale manifest 削除"). File deletion is best-effort: a failure
// here is diagnostic noise, not a build breaker, so the helper swallows it.
internal fun handleRuntimeClasspathManifest(
  config: KoltConfig,
  resolvedJars: List<ResolvedJar>,
  profile: Profile = Profile.Debug,
): Result<Unit, ManifestWriteError> {
  val shouldEmit = config.build.target == "jvm" && !config.isLibrary()
  if (shouldEmit) {
    return writeRuntimeClasspathManifest(config, resolvedJars, profile)
  }
  val manifestPath = outputRuntimeClasspathPath(config, profile)
  if (fileExists(manifestPath)) deleteFile(manifestPath)
  return Ok(Unit)
}

// Kind-gated plan for a native build (ADR 0014 two-stage × ADR 0023 §1).
// `linkMain == null` ⇒ skip stage 2 (library), stage 1 klib is the artifact.
// `linkMain != null` ⇒ run stage 2 (app link) with that entry-point FQN.
internal data class NativeStagePlan(
  val linkMain: String?,
  val artifactKind: String,
  val artifactPath: String,
)

// ADR 0023 §1: library kind has no entry point; app kind must carry one
// (parser invariant). The `?: return` keeps ADR 0001 safe without `!!`;
// it is unreachable for apps produced by `parseConfig`.
internal fun nativeStagePlan(
  config: KoltConfig,
  profile: Profile = Profile.Debug,
): Result<NativeStagePlan, Int> {
  if (config.isLibrary()) {
    return Ok(
      NativeStagePlan(
        linkMain = null,
        artifactKind = "library",
        artifactPath = outputNativeKlibPath(config, profile),
      )
    )
  }
  val main = config.build.main ?: return Err(EXIT_BUILD_ERROR)
  return Ok(
    NativeStagePlan(
      linkMain = main,
      artifactKind = "executable",
      artifactPath = outputKexePath(config, profile),
    )
  )
}

internal fun filterExistingDirs(
  paths: List<String>,
  kind: String,
  exists: (String) -> Boolean = ::fileExists,
  warn: (String) -> Unit = ::eprintln,
): List<String> {
  val (existing, missing) = paths.partition { exists(it) }
  for (path in missing) {
    warn("warning: $kind directory \"$path\" does not exist, skipping")
  }
  return existing
}

internal fun loadProjectConfig(): Result<KoltConfig, Int> {
  val tomlString =
    readFileAsString(KOLT_TOML).getOrElse { error ->
      eprintln("error: could not read ${error.path}")
      return Err(EXIT_CONFIG_ERROR)
    }
  return parseConfig(tomlString)
    .getOrElse { error ->
      when (error) {
        is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
      }
      return Err(EXIT_CONFIG_ERROR)
    }
    .let { Ok(it) }
}

internal fun doCheck(
  useDaemon: Boolean = true,
  profile: Profile = Profile.Debug,
): Result<Unit, Int> = withProjectLock { doCheckInner(useDaemon, profile) }

private fun doCheckInner(useDaemon: Boolean, profile: Profile): Result<Unit, Int> {
  val startMark = TimeSource.Monotonic.markNow()
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }
  // konanc has no syntax-only mode; a full build is the only option.
  // doBuildInner skips the outer lock acquire — doCheck already holds it.
  if (config.build.target in NATIVE_TARGETS) {
    doBuildInner(useDaemon = useDaemon, profile = profile).getOrElse {
      return Err(it)
    }
    return Ok(Unit)
  }
  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_BUILD_ERROR)
    }
  val managedKotlincBin =
    ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse {
      eprintln("error: ${it.message}")
      return Err(EXIT_BUILD_ERROR)
    }
  val managedJdkBins =
    ensureJdkBinsFromConfig(config, paths).getOrElse {
      return Err(it)
    }

  val classpath =
    resolveDependencies(config)
      .getOrElse {
        return Err(it)
      }
      .mainClasspath
  val pArgs =
    resolvePluginArgs(config, paths, EXIT_BUILD_ERROR).getOrElse {
      eprintln("error: ${it.message}")
      return Err(it.exitCode)
    }
  val cmd = checkCommand(config, classpath, pArgs, kotlincPath = managedKotlincBin)

  println("checking ${config.name}...")
  executeCommand(cmd, mapOf("JAVA_HOME" to managedJdkBins.home)).getOrElse { error ->
    eprintln("error: " + formatProcessError(error, "check"))
    return Err(EXIT_BUILD_ERROR)
  }
  val elapsed = startMark.elapsedNow()
  println("check passed in ${formatDuration(elapsed)}")
  return Ok(Unit)
}

internal fun doClean(): Result<Unit, Int> {
  val buildDirRemoved =
    if (fileExists(BUILD_DIR)) {
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

internal fun doBuild(
  useDaemon: Boolean = true,
  profile: Profile = Profile.Debug,
): Result<BuildResult, Int> = withProjectLock { doBuildInner(useDaemon, profile) }

private fun doBuildInner(useDaemon: Boolean, profile: Profile): Result<BuildResult, Int> {
  val startMark = TimeSource.Monotonic.markNow()
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }

  if (config.build.target in NATIVE_TARGETS) {
    return doNativeBuildInner(config, useDaemon, profile)
  }

  val currentState =
    BuildState(
      configMtime = fileMtime(KOLT_TOML) ?: 0L,
      sourcesNewestMtime = newestMtime(config.build.sources),
      classesDirMtime = if (fileExists(CLASSES_DIR)) newestMtimeAll(CLASSES_DIR) else null,
      lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
      resourcesNewestMtime =
        if (config.build.resources.isEmpty()) null
        else config.build.resources.maxOf { newestMtimeAll(it) },
    )
  val cachedState =
    readFileAsString(BUILD_STATE_FILE).getOrElse { null }?.let { parseBuildState(it) }

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_BUILD_ERROR)
    }
  val managedJdkBins =
    ensureJdkBinsFromConfig(config, paths).getOrElse {
      return Err(it)
    }
  val managedJavaBin = managedJdkBins.java
  val managedJarBin = managedJdkBins.jar

  // BuildState.classesDirMtime tracks `build/classes/` (profile-naive on JVM
  // because kotlinc args are profile-independent). The active profile's jar
  // may still be missing if the previous build wrote a different profile's
  // jar, so the artifact existence check is the per-profile gate.
  val jarPath = outputJarPath(config, profile)
  if (isBuildUpToDate(current = currentState, cached = cachedState) && fileExists(jarPath)) {
    val elapsed = startMark.elapsedNow()
    println("${config.name} is up to date (${formatDuration(elapsed)})")
    // Invariant: the up-to-date path must stay a pure mtime compare —
    // no plugin jar resolution or toolchain provisioning — so offline
    // cached builds don't hard-exit on a failed fetch.
    return Ok(
      BuildResult(
        config = config,
        classpath = cachedState!!.classpath,
        javaPath = managedJavaBin,
        javaHome = managedJdkBins.home,
        testClasspath = cachedState.testClasspath,
      )
    )
  }
  // Out of date → rebuild. Drop the state file first so any failure
  // below leaves cached=null for the next run (#50).
  if (fileExists(BUILD_STATE_FILE)) deleteFile(BUILD_STATE_FILE)
  val managedKotlincBin =
    ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse {
      eprintln("error: ${it.message}")
      return Err(EXIT_BUILD_ERROR)
    }
  val resolutionOutcome =
    resolveDependencies(config).getOrElse {
      return Err(it)
    }
  val mainClasspath = resolutionOutcome.mainClasspath
  val testClasspath =
    buildClasspath(resolutionOutcome.allJars.map { it.cachePath }).ifEmpty { null }
  val pluginJarPathsByAlias =
    resolveEnabledPluginJarPaths(config, paths, EXIT_BUILD_ERROR).getOrElse {
      eprintln("error: ${it.message}")
      return Err(it.exitCode)
    }
  val pArgs = pluginJarPathsByAlias.values.map { "-Xplugin=$it" }
  val pluginJarsForDaemon = pluginJarPathsByAlias.mapValues { (_, path) -> listOf(path) }
  ensureDirectoryRecursive(CLASSES_DIR).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }

  val cwd =
    currentWorkingDirectory()
      ?: run {
        eprintln("error: could not determine current working directory")
        return Err(EXIT_BUILD_ERROR)
      }

  val subprocessBackend =
    SubprocessCompilerBackend(kotlincBin = managedKotlincBin, javaHome = managedJdkBins.home)
  val backend: CompilerBackend =
    resolveCompilerBackend(
      config = config,
      paths = paths,
      subprocessBackend = subprocessBackend,
      useDaemon = useDaemon,
      absProjectPath = cwd,
      pluginJars = pluginJarsForDaemon,
    )
  // Absolutise all paths: the daemon JVM persists across builds and
  // does not honour CompileRequest.workingDir (ADR 0016 §3).
  val request =
    CompileRequest(
      workingDir = cwd,
      // Main compile only sees main closure deps (ADR 0027 §1; spec
      // main-test-closure-separation): test-origin jars never reach kotlinc.
      // TODO(#14 S5+): teach resolveDependencies to return List<String>
      // directly and delete this split.
      classpath =
        if (mainClasspath.isNullOrEmpty()) emptyList()
        else mainClasspath.split(":").filter { it.isNotEmpty() },
      // BTA requires individual .kt files, not directories (#117).
      sources =
        expandKotlinSources(config.build.sources.map { absolutise(it, cwd) }).getOrElse { err ->
          eprintln("error: could not list Kotlin sources under ${err.path}")
          return Err(EXIT_BUILD_ERROR)
        },
      outputPath = absolutise(CLASSES_DIR, cwd),
      moduleName = config.name,
      extraArgs =
        buildList {
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

  val jarCmd = jarCommand(config, jarPath = managedJarBin, profile = profile)
  ensureDirectoryRecursive(jarCmd.outputPath.substringBeforeLast('/')).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  executeCommand(jarCmd.args).getOrElse { error ->
    eprintln("error: " + formatProcessError(error, "jar packaging"))
    return Err(EXIT_BUILD_ERROR)
  }

  // ADR 0027 §1 / §4: JVM kind=app emits the runtime classpath manifest
  // right after the jar step; every other kind/target combination
  // (handled by the same helper) scrubs any stale manifest left by a
  // previous kind=app build. Manifest write failure is treated as a
  // build failure (ADR 0001: Err propagates, jar artifact is left on
  // disk mirroring the existing jarCmd failure semantics).
  handleRuntimeClasspathManifest(config, resolutionOutcome.mainJars, profile).getOrElse { err ->
    val failure = err as ManifestWriteError.WriteFailed
    eprintln(
      "error: could not write runtime classpath manifest at ${failure.path}: ${failure.reason}"
    )
    return Err(EXIT_BUILD_ERROR)
  }

  val newState =
    currentState.copy(
      classesDirMtime = newestMtimeAll(CLASSES_DIR),
      lockfileMtime = if (fileExists(LOCK_FILE)) fileMtime(LOCK_FILE) else null,
      classpath = mainClasspath,
      testClasspath = testClasspath,
    )
  writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
    eprintln("warning: could not write build state file")
  }

  val elapsed = startMark.elapsedNow()
  println("built ${jarCmd.outputPath} in ${formatDuration(elapsed)}")
  return Ok(
    BuildResult(
      config = config,
      classpath = mainClasspath,
      javaPath = managedJavaBin,
      javaHome = managedJdkBins.home,
      testClasspath = testClasspath,
    )
  )
}

private fun doNativeBuildInner(
  config: KoltConfig,
  useDaemon: Boolean,
  profile: Profile,
): Result<BuildResult, Int> {
  val startMark = TimeSource.Monotonic.markNow()

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_BUILD_ERROR)
    }
  val managedKonancBin =
    ensureKonancBin(config.kotlin.effectiveCompiler, paths).getOrElse {
      eprintln("error: ${it.message}")
      return Err(EXIT_BUILD_ERROR)
    }

  // ADR 0014 (two-stage native) × ADR 0023 §1 (kind schema): library
  // kind stops at stage 1 (the `-klib` directory), app kind continues
  // into stage 2 (`.kexe`). Artifact path drives both the up-to-date
  // check and the success message so lib builds don't chase a missing
  // `.kexe`.
  val stagePlan =
    nativeStagePlan(config, profile).getOrElse {
      return Err(it)
    }
  val artifactPath = stagePlan.artifactPath
  val defNewestMtime = newestDefMtime(config)
  val currentState =
    BuildState(
      configMtime = fileMtime(KOLT_TOML) ?: 0L,
      sourcesNewestMtime = newestMtime(config.build.sources),
      classesDirMtime = if (fileExists(artifactPath)) fileMtime(artifactPath) else null,
      lockfileMtime = null,
      resourcesNewestMtime = null,
      defNewestMtime = defNewestMtime,
    )
  val cachedState =
    readFileAsString(BUILD_STATE_FILE).getOrElse { null }?.let { parseBuildState(it) }

  if (isBuildUpToDate(current = currentState, cached = cachedState)) {
    val elapsed = startMark.elapsedNow()
    println("${config.name} is up to date (${formatDuration(elapsed)})")
    return Ok(BuildResult(config, classpath = null, javaPath = null))
  }
  // Out of date → rebuild. Drop the state file first so any failure
  // below leaves cached=null for the next run (#50).
  if (fileExists(BUILD_STATE_FILE)) deleteFile(BUILD_STATE_FILE)

  // run_konan / cinterop are shell wrappers that resolve `java` through
  // `$JAVA_HOME/bin/java` first and fall back to PATH; on a clean host
  // with no system Java the wrapper exits 127 (#285). Provision a managed
  // JDK before spawning any konanc subprocess. Lazy: kept after the
  // up-to-date branch so cache hits don't trigger an install.
  val managedJdkBins =
    ensureJdkBinsFromConfig(config, paths).getOrElse {
      return Err(it)
    }

  // Resolve after the up-to-date check (#57) so cache hits don't walk
  // the dependency graph and re-hash every cached klib.
  val depKlibs =
    resolveNativeDependencies(config, paths).getOrElse {
      return Err(it)
    }

  val nativePluginArgs =
    resolvePluginArgs(config, paths, EXIT_BUILD_ERROR).getOrElse {
      eprintln("error: ${it.message}")
      return Err(it.exitCode)
    }

  ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }

  val cinteropKlibs =
    runCinterop(config, paths, javaHome = managedJdkBins.home).getOrElse {
      return Err(it)
    }
  val klibs = depKlibs + cinteropKlibs

  val cwd =
    currentWorkingDirectory()
      ?: run {
        eprintln("error: could not determine current working directory")
        return Err(EXIT_BUILD_ERROR)
      }
  val subprocessBackend =
    NativeSubprocessBackend(konancBin = managedKonancBin, javaHome = managedJdkBins.home)
  val backend: NativeCompilerBackend =
    resolveNativeCompilerBackend(
      config = config,
      paths = paths,
      subprocessBackend = subprocessBackend,
      useDaemon = useDaemon,
      absProjectPath = cwd,
    )

  val libraryCmd =
    nativeLibraryCommand(
      config,
      pluginArgs = nativePluginArgs,
      konancPath = managedKonancBin,
      klibs = klibs,
      profile = profile,
    )
  ensureDirectoryRecursive(libraryCmd.outputPath.substringBeforeLast('/')).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  println("compiling ${config.name} (native)...")
  // ADR 0024 §4: backend.compile takes konanc args *after* the binary.
  // `nativeLibraryCommand` / `nativeLinkCommand` put the binary at [0]
  // for the subprocess call site; strip it when handing off to the
  // backend so the daemon and subprocess paths share the same argv shape.
  backend.compile(libraryCmd.args.drop(1)).getOrElse { error ->
    reportNativeCompileError(error, "compilation")
    return Err(EXIT_BUILD_ERROR)
  }

  // ADR 0014 stage 2 × ADR 0023 §1: skip the native link step for library
  // kind; stage 1 already produced the `.klib` artifact.
  if (stagePlan.linkMain != null) {
    ensureDirectoryRecursive(nativeIcCacheDir(profile)).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }

    val linkCmd =
      nativeLinkCommand(
        config,
        main = stagePlan.linkMain,
        konancPath = managedKonancBin,
        klibs = klibs,
        profile = profile,
      )
    println("linking ${config.name} (native)...")
    runNativeLinkWithIcFallback(backend, linkCmd.args.drop(1)) { wipeNativeIcCache(profile) }
      .getOrElse { error ->
        reportNativeCompileError(error, "linking")
        return Err(EXIT_BUILD_ERROR)
      }
  }

  if (!fileExists(artifactPath)) {
    eprintln("error: $artifactPath not produced by konanc")
    return Err(EXIT_BUILD_ERROR)
  }

  // ADR 0027 §4 stale cleanup: native builds never emit the manifest,
  // but a prior `kind = "app" target = "jvm"` build in the same project
  // directory would have left one behind. Drop it so `assemble-dist.sh`
  // cannot pick up a mismatched artifact after a retarget. The helper
  // takes the "cleanup" arm for every non-jvm-app config and only ever
  // returns Ok, so discarding the Result here is safe by construction.
  handleRuntimeClasspathManifest(config, emptyList(), profile)

  val newState = currentState.copy(classesDirMtime = fileMtime(artifactPath))
  writeFileAsString(BUILD_STATE_FILE, serializeBuildState(newState)).getOrElse {
    eprintln("warning: could not write build state file")
  }

  val elapsed = startMark.elapsedNow()
  println("built ${stagePlan.artifactKind} $artifactPath in ${formatDuration(elapsed)}")
  return Ok(BuildResult(config, classpath = null, javaPath = null))
}

// Surface konanc stderr before the one-line fallback/error summary so the
// user sees the actual diagnostic. CompilationFailed rides the
// backend-supplied stderr; other variants don't have diagnostic content.
private fun reportNativeCompileError(error: NativeCompileError, context: String) {
  if (error is NativeCompileError.CompilationFailed && error.stderr.isNotEmpty()) {
    eprintln(error.stderr.trimEnd('\n'))
  }
  eprintln(formatNativeCompileError(error, context))
}

// On konanc non-zero exit, retry once after wiping the IC cache — konanc
// can't distinguish "stale cache" from "real compile error" in its exit
// code, so we treat every non-zero exit as potentially cache-induced.
// The 2x cost on genuine source errors is acceptable because source
// errors are caught at stage 1 (library), not stage 2 (link). Spike #160
// confirmed konanc handles a missing .ic-cache gracefully.
//
// After PR 4: the execute step goes through `NativeCompilerBackend`, so
// BackendUnavailable variants (daemon unreachable, subprocess fork/wait
// failures) surface directly — they are outside the cache-corruption
// hypothesis. Only `CompilationFailed` (konanc returned non-zero) is
// retry-eligible. InternalMisuse and NoCommand likewise skip retry.
// If wipe itself fails, the retry would hit the same stale cache — skip
// the retry and return the original error instead.
internal fun runNativeLinkWithIcFallback(
  backend: NativeCompilerBackend,
  args: List<String>,
  wipeCache: () -> Boolean,
): Result<NativeCompileOutcome, NativeCompileError> {
  val first = backend.compile(args)
  val firstError = first.getError() ?: return first
  if (firstError !is NativeCompileError.CompilationFailed) return first
  if (!wipeCache()) return first
  return backend.compile(args)
}

private fun newestDefMtime(config: KoltConfig): Long? {
  if (config.cinterop.isEmpty()) return null
  val mtimes = config.cinterop.mapNotNull { fileMtime(it.def) }
  return if (mtimes.isEmpty()) null else mtimes.max()
}

private fun runCinterop(
  config: KoltConfig,
  paths: KoltPaths,
  javaHome: String? = null,
): Result<List<String>, Int> {
  if (config.cinterop.isEmpty()) return Ok(emptyList())
  val managedCinteropBin = paths.cinteropBin(config.kotlin.effectiveCompiler)
  val cinteropEnv = if (javaHome != null) mapOf("JAVA_HOME" to javaHome) else emptyMap()
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

    val cmd =
      cinteropCommand(entry, target = config.build.target, cinteropPath = managedCinteropBin)
    println("generating cinterop klib for ${entry.name}...")
    executeCommand(cmd.args, cinteropEnv).getOrElse { error ->
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

// ADR 0023 §1: library kind has no entry point; `kolt run` pre-empts the
// build pipeline with this canonical stderr line.
private const val RUN_LIB_ERROR = "library projects cannot be run"

// Pure kind guard for `kolt run` / `kolt run --watch`. Returns
// `Err(EXIT_CONFIG_ERROR)` for library configs (ADR 0023 §1) and `Ok`
// for apps. Lifted to a top-level helper so `doRun` and `watchRunLoop`
// share one rejection path and the guard can be tested hermetically
// per design.md §Testing Strategy.
internal fun rejectIfLibrary(
  config: KoltConfig,
  eprint: (String) -> Unit = ::eprintln,
): Result<Unit, Int> {
  if (!config.isLibrary()) return Ok(Unit)
  eprint("error: $RUN_LIB_ERROR")
  return Err(EXIT_CONFIG_ERROR)
}

internal fun doRun(
  config: KoltConfig,
  classpath: String?,
  appArgs: List<String> = emptyList(),
  javaPath: String? = null,
  profile: Profile = Profile.Debug,
): Result<Unit, Int> = withProjectLock { doRunInner(config, classpath, appArgs, javaPath, profile) }

private fun doRunInner(
  config: KoltConfig,
  classpath: String?,
  appArgs: List<String>,
  javaPath: String?,
  profile: Profile,
): Result<Unit, Int> {
  // ADR 0023 §1 kind gate: reject libraries before any artifact lookup
  // or process launch. Target-agnostic by design (R4.3).
  rejectIfLibrary(config).getOrElse {
    return Err(it)
  }

  if (config.build.target in NATIVE_TARGETS) {
    val kexePath = outputKexePath(config, profile)
    if (!fileExists(kexePath)) {
      eprintln("error: $kexePath not found. Run 'kolt build' first.")
      return Err(EXIT_BUILD_ERROR)
    }
    val cmd = nativeRunCommand(config, appArgs, profile)
    executeCommand(cmd.args).getOrElse { error ->
      return Err(
        when (error) {
          is ProcessError.NonZeroExit -> error.exitCode
          else -> EXIT_BUILD_ERROR
        }
      )
    }
    return Ok(Unit)
  }
  if (!fileExists(CLASSES_DIR)) {
    eprintln("error: $CLASSES_DIR not found. Run 'kolt build' first.")
    return Err(EXIT_BUILD_ERROR)
  }

  // Parser invariant `kind == "app" ⇒ main != null` guarantees non-null;
  // the kind gate above pre-empts libs, so this `?: return` is ADR 0001
  // safety for the parser invariant and is unreachable on apps at runtime.
  val jvmMain = config.build.main ?: return Err(EXIT_BUILD_ERROR)
  val cmd =
    runCommand(
      config,
      main = jvmMain,
      classpath = classpath,
      appArgs = appArgs,
      javaPath = javaPath,
      profile = profile,
    )
  executeCommand(cmd.args).getOrElse { error ->
    return Err(
      when (error) {
        is ProcessError.NonZeroExit -> error.exitCode
        else -> EXIT_BUILD_ERROR
      }
    )
  }
  return Ok(Unit)
}

internal fun doTest(
  testArgs: List<String> = emptyList(),
  useDaemon: Boolean = true,
  profile: Profile = Profile.Debug,
): Result<Unit, Int> = withProjectLock { doTestInner(testArgs, useDaemon, profile) }

private fun doTestInner(
  testArgs: List<String>,
  useDaemon: Boolean,
  profile: Profile,
): Result<Unit, Int> {
  val config =
    loadProjectConfig().getOrElse {
      return Err(it)
    }
  if (config.build.target in NATIVE_TARGETS) {
    return doNativeTest(config, testArgs, profile)
  }
  // doBuildInner (not doBuild) — the outer lock acquired by doTest
  // already covers the build; calling doBuild would attempt a second
  // flock(2) acquire on a fresh OFD and deadlock against ourselves.
  val buildResult =
    doBuildInner(useDaemon = useDaemon, profile = profile).getOrElse {
      return Err(it)
    }
  // R4.1: test compile/run classpath is main ∪ test. Fall through to the
  // main-only classpath if the resolver found no test origin deps (the
  // union collapses to the main closure).
  val testClasspath = buildResult.testClasspath ?: buildResult.classpath
  val javaPath = buildResult.javaPath
  val javaHome = buildResult.javaHome

  val existingTestSources = filterExistingDirs(config.build.testSources, "test source")
  if (existingTestSources.isEmpty()) {
    eprintln("error: no test sources found in ${config.build.testSources}")
    return Err(EXIT_TEST_ERROR)
  }

  val testStartMark = TimeSource.Monotonic.markNow()

  val paths =
    resolveKoltPaths().getOrElse {
      eprintln("error: $it")
      return Err(EXIT_TEST_ERROR)
    }
  val consoleLauncherPath =
    ensureTool(paths, CONSOLE_LAUNCHER_SPEC).getOrElse {
      eprintln("error: $it")
      return Err(EXIT_TEST_ERROR)
    }
  val managedKotlincBin =
    ensureKotlincBin(config.kotlin.effectiveCompiler, paths).getOrElse {
      eprintln("error: ${it.message}")
      return Err(EXIT_TEST_ERROR)
    }

  val pArgs =
    resolvePluginArgs(config, paths, EXIT_TEST_ERROR).getOrElse {
      eprintln("error: ${it.message}")
      return Err(it.exitCode)
    }

  val testConfig = config.copy(build = config.build.copy(testSources = existingTestSources))
  val testCmd =
    testBuildCommand(
      testConfig,
      CLASSES_DIR,
      testClasspath,
      pArgs,
      kotlincPath = managedKotlincBin,
      profile = profile,
    )
  println("compiling tests...")
  val testCompileEnv = if (javaHome != null) mapOf("JAVA_HOME" to javaHome) else emptyMap()
  executeCommand(testCmd.args, testCompileEnv).getOrElse { error ->
    eprintln("error: " + formatProcessError(error, "test compilation"))
    return Err(EXIT_BUILD_ERROR)
  }

  val existingTestResourceDirs = filterExistingDirs(config.build.testResources, "test resource")
  val runCmd =
    testRunCommand(
      classesDir = CLASSES_DIR,
      testClassesDir = testCmd.outputPath,
      consoleLauncherPath = consoleLauncherPath,
      testResourceDirs = existingTestResourceDirs,
      classpath = testClasspath,
      testArgs = testArgs,
      javaPath = javaPath,
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

private fun doNativeTest(
  config: KoltConfig,
  testArgs: List<String>,
  profile: Profile,
): Result<Unit, Int> {
  val existingTestSources = filterExistingDirs(config.build.testSources, "test source")
  if (existingTestSources.isEmpty()) {
    eprintln("error: no test sources found in ${config.build.testSources}")
    return Err(EXIT_TEST_ERROR)
  }

  val testStartMark = TimeSource.Monotonic.markNow()
  val testConfig = config.copy(build = config.build.copy(testSources = existingTestSources))
  val testKexePath = outputNativeTestKexePath(testConfig, profile)

  val currentState =
    TestBuildState(
      configMtime = fileMtime(KOLT_TOML) ?: 0L,
      sourcesNewestMtime = newestMtime(config.build.sources),
      testSourcesNewestMtime = newestMtime(existingTestSources),
      testKexeMtime = if (fileExists(testKexePath)) fileMtime(testKexePath) else null,
      defNewestMtime = newestDefMtime(config),
    )
  val cachedState =
    readFileAsString(TEST_BUILD_STATE_FILE).getOrElse { null }?.let { parseTestBuildState(it) }

  if (!isTestBuildUpToDate(current = currentState, cached = cachedState)) {
    // Out of date → rebuild. Drop the state file first so any failure
    // below leaves cached=null for the next run (mirror of #50).
    if (fileExists(TEST_BUILD_STATE_FILE)) deleteFile(TEST_BUILD_STATE_FILE)

    val paths =
      resolveKoltPaths().getOrElse {
        eprintln("error: $it")
        return Err(EXIT_TEST_ERROR)
      }
    val managedKonancBin =
      ensureKonancBin(config.kotlin.effectiveCompiler, paths).getOrElse {
        eprintln("error: ${it.message}")
        return Err(EXIT_TEST_ERROR)
      }
    val managedJdkBins =
      ensureJdkBinsFromConfig(config, paths).getOrElse {
        return Err(it)
      }
    val konancEnv = mapOf("JAVA_HOME" to managedJdkBins.home)
    val nativePluginArgs =
      resolvePluginArgs(config, paths, EXIT_TEST_ERROR).getOrElse {
        eprintln("error: ${it.message}")
        return Err(it.exitCode)
      }

    val depKlibs =
      resolveNativeDependencies(config, paths).getOrElse {
        return Err(it)
      }

    ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }

    val cinteropKlibs =
      runCinterop(config, paths, javaHome = managedJdkBins.home).getOrElse {
        return Err(it)
      }
    val klibs = depKlibs + cinteropKlibs

    val libraryCmd =
      nativeTestLibraryCommand(
        testConfig,
        pluginArgs = nativePluginArgs,
        konancPath = managedKonancBin,
        klibs = klibs,
        profile = profile,
      )
    ensureDirectoryRecursive(libraryCmd.outputPath.substringBeforeLast('/')).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("compiling tests (native)...")
    executeCommand(libraryCmd.args, konancEnv).getOrElse { error ->
      eprintln("error: " + formatProcessError(error, "test compilation"))
      return Err(EXIT_BUILD_ERROR)
    }

    val linkCmd =
      nativeTestLinkCommand(
        testConfig,
        konancPath = managedKonancBin,
        klibs = klibs,
        profile = profile,
      )
    println("linking tests (native)...")
    executeCommand(linkCmd.args, konancEnv).getOrElse { error ->
      eprintln("error: " + formatProcessError(error, "test linking"))
      return Err(EXIT_BUILD_ERROR)
    }

    if (!fileExists(linkCmd.outputPath)) {
      eprintln("error: ${linkCmd.outputPath} not produced by konanc")
      return Err(EXIT_BUILD_ERROR)
    }

    val newState = currentState.copy(testKexeMtime = fileMtime(linkCmd.outputPath))
    writeFileAsString(TEST_BUILD_STATE_FILE, serializeTestBuildState(newState)).getOrElse {
      eprintln("warning: could not write test state file")
    }
  }

  val runCmd = nativeTestRunCommand(testConfig, testArgs, profile)
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

// Falls back to the bootstrap JDK (ADR 0017) when `[build] jdk` is unset so
// downstream consumers always have a managed `java`/`jar` available. The
// pin in `kolt.toml` still wins; this only fills the unset case.
internal fun ensureJdkBinsFromConfig(
  config: KoltConfig,
  paths: KoltPaths,
  ensureJdkBins: (String, KoltPaths) -> Result<JdkBins, ToolchainError> = ::ensureJdkBins,
): Result<JdkBins, Int> {
  val version = config.build.jdk ?: BOOTSTRAP_JDK_VERSION
  return ensureJdkBins(version, paths)
    .getOrElse { err ->
      eprintln("error: ${err.message}")
      return Err(EXIT_BUILD_ERROR)
    }
    .let { Ok(it) }
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
  daemonDirCreator: (String) -> Result<Unit, MkdirFailed> = ::ensureDirectoryRecursive,
  daemonBackendFactory: (DaemonSetup, Map<String, List<String>>) -> CompilerBackend =
    ::createDaemonBackend,
  warningSink: (String) -> Unit = ::eprintln,
  preconditionResolver:
    (KoltPaths, String, String, String) -> Result<DaemonSetup, DaemonPreconditionError> =
    { p, kotlincVersion, cwd, bundled ->
      resolveDaemonPreconditions(
        paths = p,
        kotlincVersion = kotlincVersion,
        absProjectPath = cwd,
        bundledKotlinVersion = bundled,
        warningSink = warningSink,
      )
    },
): CompilerBackend {
  if (!useDaemon) return subprocessBackend

  val setup =
    preconditionResolver(
        paths,
        config.kotlin.effectiveCompiler,
        absProjectPath,
        bundledKotlinVersion,
      )
      .getOrElse { err ->
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

// ADR 0024 §7: native daemon is never load-bearing either. Same fallback
// shape as `resolveCompilerBackend`, but simpler: no plugin fingerprinting
// (no daemon-side plugin channel per §6) and a different precondition
// resolver.
internal fun resolveNativeCompilerBackend(
  config: KoltConfig,
  paths: KoltPaths,
  subprocessBackend: NativeCompilerBackend,
  useDaemon: Boolean,
  absProjectPath: String,
  preconditionResolver:
    (KoltPaths, String, String) -> Result<NativeDaemonSetup, NativeDaemonPreconditionError> =
    { p, kotlincVersion, cwd ->
      resolveNativeDaemonPreconditions(p, kotlincVersion, cwd)
    },
  daemonDirCreator: (String) -> Result<Unit, MkdirFailed> = ::ensureDirectoryRecursive,
  daemonBackendFactory: (NativeDaemonSetup) -> NativeCompilerBackend = ::createNativeDaemonBackend,
  warningSink: (String) -> Unit = ::eprintln,
): NativeCompilerBackend {
  if (!useDaemon) return subprocessBackend

  val setup =
    preconditionResolver(paths, config.kotlin.effectiveCompiler, absProjectPath).getOrElse { err ->
      warningSink(formatNativeDaemonPreconditionWarning(err))
      return subprocessBackend
    }

  // spawnDetached opens the log with O_CREAT but not the parent dir;
  // create it before the daemon tries to bind the socket. Mirrors the
  // JVM daemon wiring.
  if (daemonDirCreator(setup.daemonDir).getError() != null) {
    warningSink(WARNING_DAEMON_DIR_UNWRITABLE)
    return subprocessBackend
  }

  return FallbackNativeCompilerBackend(
    primary = daemonBackendFactory(setup),
    fallback = subprocessBackend,
    onFallback = ::reportNativeFallback,
  )
}

internal fun createNativeDaemonBackend(setup: NativeDaemonSetup): NativeCompilerBackend =
  NativeDaemonBackend(
    javaBin = setup.javaBin,
    daemonLaunchArgs = setup.daemonLaunchArgs,
    konancJar = setup.konancJar,
    konanHome = setup.konanHome,
    socketPath = setup.socketPath,
    logPath = setup.logPath,
    onSpawn = { eprintln("starting native compiler daemon...") },
  )

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
    daemonLaunchArgs = setup.daemonLaunchArgs,
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
  val canonical =
    pluginJars.entries
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

// Read KOLT_LOCK_TIMEOUT_MS from the env. Anything that does not parse as
// a non-negative Long (unset, empty, "abc", "-5") collapses to the
// default. Zero is allowed and means "fail immediately if a peer holds".
// Duplicated in DependencyCommands by design (tasks.md 3.1: no shared
// abstraction yet) — the two CLI layers stay independently understandable.
@OptIn(ExperimentalForeignApi::class)
internal fun parseLockTimeoutMs(): Long {
  val raw = getenv("KOLT_LOCK_TIMEOUT_MS")?.toKString()
  val parsed = raw?.toLongOrNull() ?: return ProjectLock.DEFAULT_TIMEOUT_MS
  return if (parsed >= 0L) parsed else ProjectLock.DEFAULT_TIMEOUT_MS
}

// Wrap a build-system entry in the project-local advisory lock. The
// lock guards `kolt.lock` rewrite and `build/` finalisation — a peer
// that holds it for longer than the (env-overridable) timeout exits via
// EXIT_LOCK_TIMEOUT; a flock(2) IO failure (FS read-only, EBADF, ...)
// drops to EXIT_BUILD_ERROR with errno + message on stderr.
private inline fun <T> withProjectLock(crossinline body: () -> Result<T, Int>): Result<T, Int> {
  ensureDirectoryRecursive(BUILD_DIR).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  val timeoutMs = parseLockTimeoutMs()
  val handle: LockHandle =
    ProjectLock.acquire(BUILD_DIR, timeoutMs).getOrElse { lockError ->
      return Err(mapLockErrorToExitCode(lockError, timeoutMs))
    }
  return handle.use { body() }
}

private fun mapLockErrorToExitCode(error: LockError, requestedTimeoutMs: Long): Int =
  when (error) {
    is LockError.TimedOut -> {
      val reportedMs = if (error.waitedMs > 0L) error.waitedMs else requestedTimeoutMs
      eprintln(
        "error: lock acquisition timed out after ${reportedMs}ms; " +
          "another kolt build may be stuck"
      )
      EXIT_LOCK_TIMEOUT
    }
    is LockError.IoError -> {
      eprintln(
        "error: could not acquire build lock at $BUILD_DIR/.kolt-build.lock " +
          "(errno=${error.errno}: ${error.message})"
      )
      EXIT_BUILD_ERROR
    }
  }
