package kolt.cli

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.Profile
import kolt.build.nativeRunCommand
import kolt.config.KoltConfig
import kolt.config.NATIVE_TARGETS
import kolt.config.NOTIFICATION_MARKER
import kolt.infra.*
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.*
import platform.linux.IN_CREATE
import platform.linux.IN_ISDIR
import platform.posix.*

internal fun collectWatchPaths(config: KoltConfig, command: String): List<String> {
  val seen = mutableSetOf<String>()
  val paths = mutableListOf<String>()
  fun add(p: String) {
    if (seen.add(p)) paths.add(p)
  }
  add(".")
  config.build.sources.forEach(::add)
  if (command == "test") config.build.testSources.forEach(::add)
  config.build.resources.forEach(::add)
  if (command == "test") config.build.testResources.forEach(::add)
  return paths
}

internal fun shouldTriggerRebuild(name: String): Boolean {
  if (name.isEmpty()) return false
  if (name.startsWith(".")) return false
  if (name.endsWith("~")) return false
  if (name.endsWith(".swp") || name.endsWith(".swo") || name.endsWith(".swx")) return false
  if (name.endsWith(".tmp")) return false
  if (name == "4913") return false
  if (name == "kolt.toml") return true
  return name.endsWith(".kt") || name.endsWith(".kts")
}

private val sigintReceived = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
private fun installSigintHandler() {
  sigintReceived.value = 0
  signal(SIGINT, staticCFunction<Int, Unit> { _ -> sigintReceived.value = 1 })
}

private fun shouldExit(): Boolean = sigintReceived.value != 0

internal enum class WatchKind {
  ROOT_DIR,
  SOURCE_DIR,
  RESOURCE_DIR,
}

private data class WatchSetup(val watcher: InotifyWatcher, val wdKinds: Map<Int, WatchKind>)

private data class InnerLoopOutcome(val shouldRebuild: Boolean, val shouldRespawnOnly: Boolean)

private data class KoltTomlEffect(
  val breakInnerLoop: Boolean,
  val shouldRebuild: Boolean,
  val shouldRespawnOnly: Boolean,
)

private fun setupWatches(watchDirs: List<String>, config: KoltConfig): WatchSetup? {
  val watcher =
    InotifyWatcher.create().getOrElse { err ->
      eprintln("error: failed to initialize inotify: $err")
      return null
    }
  val wdKinds = mutableMapOf<Int, WatchKind>()
  val resourceDirs = config.build.resources.toSet() + config.build.testResources.toSet()

  for (dir in watchDirs) {
    if (!fileExists(dir)) continue
    if (dir == ".") {
      // Invariant: "." is only watched for kolt.toml changes (non-recursive).
      val wd =
        watcher.addWatch(dir, DEFAULT_WATCH_MASK).getOrElse { err ->
          reportWatchError(err)
          watcher.close()
          return null
        }
      wdKinds[wd] = WatchKind.ROOT_DIR
    } else {
      val kind = if (dir in resourceDirs) WatchKind.RESOURCE_DIR else WatchKind.SOURCE_DIR
      val wds =
        watcher.addWatchRecursive(dir).getOrElse { err ->
          reportWatchError(err)
          watcher.close()
          return null
        }
      for (wd in wds) {
        wdKinds[wd] = kind
      }
    }
  }
  return WatchSetup(watcher, wdKinds)
}

private fun reportWatchError(err: InotifyError) {
  when (err) {
    is InotifyError.WatchLimitExceeded ->
      eprintln(
        "error: inotify watch limit exceeded on ${err.path}\n" +
          "hint: increase with: sudo sysctl -w fs.inotify.max_user_watches=65536"
      )
    else -> eprintln("error: inotify watch failed: $err")
  }
}

private fun hasRelevantEvents(events: List<InotifyEvent>, wdKinds: Map<Int, WatchKind>): Boolean {
  for (event in events) {
    when (wdKinds[event.wd]) {
      WatchKind.ROOT_DIR -> {
        if (event.name == "kolt.toml") return true
      }
      WatchKind.RESOURCE_DIR -> return true
      WatchKind.SOURCE_DIR -> {
        if (shouldTriggerRebuild(event.name)) return true
      }
      null -> {
        // wd not in map — auto-added subdirectory; treat as source.
        if (shouldTriggerRebuild(event.name)) return true
      }
    }
  }
  return false
}

internal fun hasKoltTomlEvent(events: List<InotifyEvent>, wdKinds: Map<Int, WatchKind>): Boolean =
  events.any { wdKinds[it.wd] == WatchKind.ROOT_DIR && it.name == "kolt.toml" }

private fun autoAddNewDirs(
  events: List<InotifyEvent>,
  wdKinds: MutableMap<Int, WatchKind>,
  watcher: InotifyWatcher,
) {
  for (event in events) {
    if (event.mask and IN_CREATE.toUInt() == 0u) continue
    if (event.mask and IN_ISDIR.toUInt() == 0u) continue
    if (wdKinds[event.wd] == WatchKind.ROOT_DIR) continue
    if (event.name in EXCLUDED_DIRS) continue
    val parentPath = watcher.pathForWd(event.wd) ?: continue
    val newDirPath = "$parentPath/${event.name}"
    val parentKind = wdKinds[event.wd] ?: WatchKind.SOURCE_DIR
    val newWds =
      watcher.addWatchRecursive(newDirPath).getOrElse { err ->
        reportWatchError(err)
        continue
      }
    for (wd in newWds) {
      wdKinds[wd] = parentKind
    }
  }
}

private fun settleAndDrain(watcher: InotifyWatcher, wdKinds: MutableMap<Int, WatchKind>): Boolean {
  var relevant = false
  while (true) {
    val more =
      watcher.pollEvents(timeoutMs = 100).getOrElse {
        return relevant
      }
    if (more.isEmpty()) break
    autoAddNewDirs(more, wdKinds, watcher)
    if (hasRelevantEvents(more, wdKinds)) relevant = true
  }
  return relevant
}

internal fun watchCommandLoop(
  command: String,
  useDaemon: Boolean,
  testArgs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
  cliSysProps: List<Pair<String, String>> = emptyList(),
  commandRunner: (String, Boolean, List<String>) -> Result<*, Int> = { cmd, daemon, args ->
    when (cmd) {
      "build" -> doBuild(useDaemon = daemon, profile = profile)
      "test" ->
        doTest(testArgs = args, useDaemon = daemon, profile = profile, cliSysProps = cliSysProps)
      else -> doBuild(useDaemon = daemon, profile = profile)
    }
  },
) {
  var currentConfig =
    loadProjectConfig().getOrElse {
      eprintln("error: cannot start watch mode with invalid config")
      return
    }
  val initialSetup =
    setupWatches(collectWatchPaths(currentConfig, command), currentConfig) ?: return
  var watcher = initialSetup.watcher
  val wdKinds = initialSetup.wdKinds.toMutableMap()

  installSigintHandler()
  eprintln("watching for changes (Ctrl+C to stop)...")

  commandRunner(command, useDaemon, testArgs)

  // Dispatch a kolt.toml change via ChangeMatrix. Returns true if the dispatch decided to invoke
  // commandRunner (so the caller knows whether to skip a follow-on source-rebuild). Mutates
  // currentConfig, watcher, and wdKinds when the outcome is Reload.
  fun handleKoltTomlChange(): Boolean {
    when (val outcome = dispatchKoltTomlChange(currentConfig, parseProjectConfig())) {
      is KoltTomlChangeOutcome.ParseError -> {
        eprintln(
          "$NOTIFICATION_MARKER kolt.toml parse error: ${outcome.message}; " +
            "retaining previous configuration"
        )
        return false
      }
      KoltTomlChangeOutcome.NoChange -> return false
      is KoltTomlChangeOutcome.NotifyOnly -> {
        outcome.notifications.forEach { eprintln(it) }
        return false
      }
      is KoltTomlChangeOutcome.Reload -> {
        currentConfig = outcome.newConfig
        if (outcome.watcherRebuildNeeded) {
          watcher.close()
          val rebuilt =
            setupWatches(collectWatchPaths(currentConfig, command), currentConfig)
              ?: run {
                eprintln("error: failed to rebuild watcher after kolt.toml change; exiting watch")
                return false
              }
          watcher = rebuilt.watcher
          wdKinds.clear()
          wdKinds.putAll(rebuilt.wdKinds)
        }
        if (outcome.rebuild) {
          eprintln("\n--- change detected, rebuilding ---")
          commandRunner(command, useDaemon, testArgs)
          return true
        }
        return false
      }
    }
  }

  while (!shouldExit()) {
    val events =
      watcher.pollEvents(timeoutMs = 500).getOrElse {
        if (!shouldExit()) eprintln("warning: inotify read failed")
        break
      }
    if (shouldExit()) break
    if (events.isEmpty()) continue

    autoAddNewDirs(events, wdKinds, watcher)
    val relevant = hasRelevantEvents(events, wdKinds)
    val settleRelevant = settleAndDrain(watcher, wdKinds)
    if (!relevant && !settleRelevant) continue

    if (shouldExit()) break

    val koltTomlChanged = hasKoltTomlEvent(events, wdKinds)
    val nonTomlEventsExist =
      events.any { !(wdKinds[it.wd] == WatchKind.ROOT_DIR && it.name == "kolt.toml") }

    if (koltTomlChanged) {
      val rebuildInvoked = handleKoltTomlChange()
      if (!rebuildInvoked && nonTomlEventsExist) {
        eprintln("\n--- change detected, rebuilding ---")
        commandRunner(command, useDaemon, testArgs)
      }
    } else {
      eprintln("\n--- change detected, rebuilding ---")
      commandRunner(command, useDaemon, testArgs)
    }

    val pending = watcher.pollEvents(timeoutMs = 0).getOrElse { emptyList() }
    if (pending.isNotEmpty()) {
      autoAddNewDirs(pending, wdKinds, watcher)
      if (hasRelevantEvents(pending, wdKinds)) {
        settleAndDrain(watcher, wdKinds)
        if (!shouldExit()) {
          val pendingKoltToml = hasKoltTomlEvent(pending, wdKinds)
          val pendingNonTomlEvents =
            pending.any { !(wdKinds[it.wd] == WatchKind.ROOT_DIR && it.name == "kolt.toml") }
          if (pendingKoltToml) {
            val rebuiltInvoked = handleKoltTomlChange()
            if (!rebuiltInvoked && pendingNonTomlEvents) {
              eprintln("\n--- pending changes detected, rebuilding ---")
              commandRunner(command, useDaemon, testArgs)
            }
          } else {
            eprintln("\n--- pending changes detected, rebuilding ---")
            commandRunner(command, useDaemon, testArgs)
          }
        }
      }
    }
  }

  watcher.close()
  eprintln("\nwatch mode stopped")
}

@OptIn(ExperimentalForeignApi::class)
private fun spawnInProcessGroup(args: List<String>): Int {
  val pid = fork()
  if (pid < 0) return -1
  if (pid == 0) {
    setpgid(0, 0)
    memScoped {
      val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
      for (i in args.indices) {
        argv[i] = args[i].cstr.ptr
      }
      argv[args.size] = null
      execvp(args[0], argv)
      _exit(127)
    }
  }
  setpgid(pid, pid)
  return pid
}

@OptIn(ExperimentalForeignApi::class)
private fun killProcessGroup(pid: Int) {
  kill(-pid, SIGTERM)
  for (i in 0 until 5) {
    usleep(100_000u)
    memScoped {
      val status = alloc<IntVar>()
      val ret = waitpid(pid, status.ptr, WNOHANG)
      if (ret > 0) return
      if (ret < 0) return // ECHILD — already reaped
    }
  }
  kill(-pid, SIGKILL)
  memScoped {
    val status = alloc<IntVar>()
    waitpid(pid, status.ptr, 0)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun reapChild(pid: Int): Boolean {
  memScoped {
    val status = alloc<IntVar>()
    val ret = waitpid(pid, status.ptr, WNOHANG)
    return ret != 0
  }
}

internal fun watchRunLoop(
  useDaemon: Boolean,
  appArgs: List<String> = emptyList(),
  profile: Profile = Profile.Debug,
  cliSysProps: List<Pair<String, String>> = emptyList(),
  eprint: (String) -> Unit = ::eprintln,
) {
  var currentConfig =
    loadProjectConfig().getOrElse {
      eprint("error: cannot start watch mode with invalid config")
      return
    }
  // ADR 0023 §1 kind gate: emit the canonical rejection once at entry and return cleanly so a
  // library invocation does not enter the rebuild-poll loop.
  if (rejectIfLibrary(currentConfig, eprint).getError() != null) return
  if (rejectCliSysPropsOnNative(currentConfig, cliSysProps, eprint).getError() != null) return

  val initialSetup = setupWatches(collectWatchPaths(currentConfig, "run"), currentConfig) ?: return
  var watcher = initialSetup.watcher
  val wdKinds = initialSetup.wdKinds.toMutableMap()

  installSigintHandler()
  eprintln("watching for changes (Ctrl+C to stop)...")

  fun applyKoltTomlChange(): KoltTomlEffect {
    when (val outcome = dispatchKoltTomlChange(currentConfig, parseProjectConfig())) {
      is KoltTomlChangeOutcome.ParseError -> {
        eprintln(
          "$NOTIFICATION_MARKER kolt.toml parse error: ${outcome.message}; " +
            "retaining previous configuration"
        )
        return KoltTomlEffect(
          breakInnerLoop = false,
          shouldRebuild = false,
          shouldRespawnOnly = false,
        )
      }
      KoltTomlChangeOutcome.NoChange ->
        return KoltTomlEffect(
          breakInnerLoop = false,
          shouldRebuild = false,
          shouldRespawnOnly = false,
        )
      is KoltTomlChangeOutcome.NotifyOnly -> {
        outcome.notifications.forEach { eprintln(it) }
        return KoltTomlEffect(
          breakInnerLoop = false,
          shouldRebuild = false,
          shouldRespawnOnly = false,
        )
      }
      is KoltTomlChangeOutcome.Reload -> {
        currentConfig = outcome.newConfig
        if (outcome.watcherRebuildNeeded) {
          watcher.close()
          val rebuilt =
            setupWatches(collectWatchPaths(currentConfig, "run"), currentConfig)
              ?: run {
                eprintln("error: failed to rebuild watcher after kolt.toml change; exiting watch")
                return KoltTomlEffect(
                  breakInnerLoop = true,
                  shouldRebuild = false,
                  shouldRespawnOnly = false,
                )
              }
          watcher = rebuilt.watcher
          wdKinds.clear()
          wdKinds.putAll(rebuilt.wdKinds)
        }
        if (outcome.rebuild) {
          return KoltTomlEffect(
            breakInnerLoop = true,
            shouldRebuild = true,
            shouldRespawnOnly = false,
          )
        }
        // rebuild=false: only respawn if [run.sys_props] is among changes.
        val runSysPropsChanged = outcome.changedSections.any { it.sectionName == "[run.sys_props]" }
        return KoltTomlEffect(
          breakInnerLoop = runSysPropsChanged,
          shouldRebuild = false,
          shouldRespawnOnly = runSysPropsChanged,
        )
      }
    }
  }

  var shouldRebuild = true
  var lastBuildResult: BuildResult? = null

  while (!shouldExit()) {
    val buildResult: BuildResult =
      if (shouldRebuild || lastBuildResult == null) {
        doBuild(useDaemon = useDaemon, profile = profile).getOrElse {
          eprintln("build failed, waiting for changes...")
          waitForRelevantChange(watcher, wdKinds)
          continue
        }
      } else {
        // Respawn-only path: reuse classpath / javaPath from the prior build, but pick up the
        // latest config so the spawn picks up new [run.sys_props]. doBuild is skipped because no
        // compile inputs changed for a [run.sys_props]-only window.
        lastBuildResult!!.copy(config = currentConfig)
      }
    lastBuildResult = buildResult

    val projectRoot =
      currentWorkingDirectory()
        ?: run {
          eprintln("error: could not determine current working directory")
          break
        }
    val runCmd =
      if (buildResult.config.build.target in NATIVE_TARGETS) {
        nativeRunCommand(buildResult.config, appArgs, profile)
      } else {
        runJvmCommandFor(buildResult, projectRoot, appArgs, profile, cliSysProps)
      }
    val childPid = spawnInProcessGroup(runCmd.args)
    if (childPid < 0) {
      eprintln("error: failed to spawn application")
      break
    }

    var childAlive = true
    val outcome: InnerLoopOutcome = run {
      while (!shouldExit()) {
        val events = watcher.pollEvents(timeoutMs = 200).getOrElse { emptyList() }
        if (shouldExit())
          return@run InnerLoopOutcome(shouldRebuild = false, shouldRespawnOnly = false)

        if (events.isNotEmpty()) {
          autoAddNewDirs(events, wdKinds, watcher)
          if (hasRelevantEvents(events, wdKinds)) {
            settleAndDrain(watcher, wdKinds)
            if (hasKoltTomlEvent(events, wdKinds)) {
              val effect = applyKoltTomlChange()
              if (effect.breakInnerLoop) {
                return@run InnerLoopOutcome(
                  shouldRebuild = effect.shouldRebuild,
                  shouldRespawnOnly = effect.shouldRespawnOnly,
                )
              }
              // ParseError / NoChange / NotifyOnly / Reload-without-rebuild-without-run-sysprops:
              // app keeps running; continue inner loop.
              continue
            } else {
              return@run InnerLoopOutcome(shouldRebuild = true, shouldRespawnOnly = false)
            }
          }
        }

        if (childAlive && reapChild(childPid)) {
          childAlive = false
          waitForRelevantChange(watcher, wdKinds)
          return@run InnerLoopOutcome(shouldRebuild = true, shouldRespawnOnly = false)
        }
      }
      InnerLoopOutcome(shouldRebuild = false, shouldRespawnOnly = false)
    }

    if (childAlive) {
      killProcessGroup(childPid)
    }

    if (shouldExit()) break
    if (outcome.shouldRebuild) {
      eprintln("\n--- change detected, rebuilding ---")
      shouldRebuild = true
    } else if (outcome.shouldRespawnOnly) {
      // No rebuild line: respawn only with the new sysprops. The notification (if any) was
      // already emitted by applyKoltTomlChange.
      shouldRebuild = false
    } else {
      shouldRebuild = false
    }
  }

  watcher.close()
  eprintln("\nwatch mode stopped")
}

private fun waitForRelevantChange(watcher: InotifyWatcher, wdKinds: MutableMap<Int, WatchKind>) {
  while (!shouldExit()) {
    val events =
      watcher.pollEvents(timeoutMs = 500).getOrElse {
        return
      }
    if (events.isEmpty()) continue
    autoAddNewDirs(events, wdKinds, watcher)
    if (hasRelevantEvents(events, wdKinds)) {
      settleAndDrain(watcher, wdKinds)
      return
    }
  }
}
