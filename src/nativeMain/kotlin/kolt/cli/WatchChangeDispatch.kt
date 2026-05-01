package kolt.cli

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.ConfigError
import kolt.config.KoltConfig
import kolt.config.SectionChange
import kolt.config.classifyChange
import kolt.config.planDispatch

/**
 * Outcome of classifying a kolt.toml change against the matrix. Watch loops translate this into
 * notifications, config updates, watcher reconstruction, and conditional rebuild invocation.
 */
internal sealed class KoltTomlChangeOutcome {

  /** Config parse failed. The watch loop should emit the parse error and retain its old config. */
  data class ParseError(val message: String) : KoltTomlChangeOutcome()

  /** kolt.toml content changed but no observable section diff. The watch loop does nothing. */
  data object NoChange : KoltTomlChangeOutcome()

  /**
   * Notify-only sections (or a mixed window where notify-only prevails). The watch loop emits the
   * notification lines verbatim and skips both reload and rebuild.
   */
  data class NotifyOnly(val notifications: List<String>) : KoltTomlChangeOutcome()

  /**
   * Auto-reload sections only. The watch loop replaces its in-memory config with [newConfig],
   * reconstructs the watcher when [watcherRebuildNeeded], and invokes commandRunner when [rebuild].
   * [changedSections] is exposed so loops with extra responsibilities (run-loop respawn) can act on
   * specific sections without re-classifying.
   */
  data class Reload(
    val newConfig: KoltConfig,
    val watcherRebuildNeeded: Boolean,
    val rebuild: Boolean,
    val changedSections: List<SectionChange>,
  ) : KoltTomlChangeOutcome()
}

private val WATCHER_REBUILD_TRIGGERS: Set<String> =
  setOf("[build] sources", "[build] test_sources", "[build] resources", "[build] test_resources")

/**
 * Classify a fresh kolt.toml parse result against the previously loaded config and decide what the
 * watch loop should do. Pure function: side-effect-free, takes the parse Result so the caller owns
 * I/O.
 */
internal fun dispatchKoltTomlChange(
  oldConfig: KoltConfig,
  newConfigResult: Result<KoltConfig, ConfigError>,
): KoltTomlChangeOutcome {
  val newConfig =
    newConfigResult.getOrElse { err ->
      return KoltTomlChangeOutcome.ParseError(formatParseError(err))
    }
  val changes = classifyChange(oldConfig, newConfig)
  if (changes.isEmpty()) return KoltTomlChangeOutcome.NoChange
  val plan = planDispatch(changes)
  if (plan.notifications.isNotEmpty()) {
    return KoltTomlChangeOutcome.NotifyOnly(plan.notifications)
  }
  val watcherRebuildNeeded = changes.any { it.sectionName in WATCHER_REBUILD_TRIGGERS }
  return KoltTomlChangeOutcome.Reload(
    newConfig = newConfig,
    watcherRebuildNeeded = watcherRebuildNeeded,
    rebuild = plan.rebuild,
    changedSections = changes,
  )
}

private fun formatParseError(err: ConfigError): String =
  when (err) {
    is ConfigError.ParseFailed -> err.message
  }
