package kolt.config

/**
 * Classification of how the watch loop should react when a kolt.toml section changes.
 *
 * The taxonomy is fixed at three top-level values per ADR 0033. AutoReload carries a sub-attribute
 * distinguishing rebuild-required from rebuild-not-required; NotifyOnly carries a per-section
 * user-recommended action string.
 */
sealed class SectionAction {
  data class AutoReload(val rebuild: Boolean) : SectionAction()

  data class NotifyOnly(val recommendation: String) : SectionAction()

  data object NoOp : SectionAction()
}

/** A single section-level change detected between two KoltConfig snapshots. */
data class SectionChange(val sectionName: String, val action: SectionAction)

/**
 * Aggregated dispatch decision for one debounce window.
 *
 * Invariant (enforced by planDispatch): notifications.isNotEmpty() iff (!reload && !rebuild). This
 * expresses the notify-only-prevail rule for mixed windows.
 */
data class DispatchPlan(
  val reload: Boolean,
  val rebuild: Boolean,
  val notifications: List<String>,
  val changedSections: List<SectionChange>,
)

/** Marker that prefixes every kolt.toml change-handling notification line on stderr. */
const val NOTIFICATION_MARKER: String = "[watch] ⚠"

private const val RECOMMEND_DEPS_INSTALL = "Run kolt deps install"
private const val RECOMMEND_DAEMON_RESTART = "Run kolt daemon stop --all and restart watch"
private const val RECOMMEND_RESTART_KIND = "Restart watch — kind change alters build pipeline"
private const val RECOMMEND_RESTART_TARGET = "Restart watch — target change alters build pipeline"
private const val RECOMMEND_RESTART_JVM_TARGET =
  "Restart watch — jvm_target change alters compiler bytecode pin"
private const val RECOMMEND_RESTART_JDK = "Restart watch — JDK pin change alters daemon process"
private const val RECOMMEND_RESTART_CINTEROP =
  "Restart watch — cinterop binding regeneration required"

/**
 * Single source of truth: each KoltConfig section is described once with its canonical name, its
 * SectionAction classification, and the field extractor that classifyChange compares between
 * snapshots. SECTION_ACTIONS is derived from this list so the section name cannot drift between the
 * matrix and the diff loop. Adding a new KoltConfig field is a one-entry edit here plus the mirror
 * entry in the schema-coverage test.
 */
private data class SectionDescriptor(
  val name: String,
  val action: SectionAction,
  val extract: (KoltConfig) -> Any?,
)

private val SECTIONS: List<SectionDescriptor> =
  listOf(
    SectionDescriptor("name", SectionAction.AutoReload(rebuild = true)) { it.name },
    SectionDescriptor("version", SectionAction.AutoReload(rebuild = true)) { it.version },
    SectionDescriptor("kind", SectionAction.NotifyOnly(RECOMMEND_RESTART_KIND)) { it.kind },
    SectionDescriptor("[kotlin] compiler", SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART)) {
      it.kotlin.compiler
    },
    SectionDescriptor("[kotlin] version", SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART)) {
      it.kotlin.version
    },
    SectionDescriptor("[kotlin.plugins]", SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART)) {
      it.kotlin.plugins
    },
    SectionDescriptor("[build] target", SectionAction.NotifyOnly(RECOMMEND_RESTART_TARGET)) {
      it.build.target
    },
    SectionDescriptor(
      "[build] jvm_target",
      SectionAction.NotifyOnly(RECOMMEND_RESTART_JVM_TARGET),
    ) {
      it.build.jvmTarget
    },
    SectionDescriptor("[build] jdk", SectionAction.NotifyOnly(RECOMMEND_RESTART_JDK)) {
      it.build.jdk
    },
    SectionDescriptor("[build] main", SectionAction.AutoReload(rebuild = true)) { it.build.main },
    SectionDescriptor("[build] sources", SectionAction.AutoReload(rebuild = true)) {
      it.build.sources
    },
    SectionDescriptor("[build] test_sources", SectionAction.AutoReload(rebuild = true)) {
      it.build.testSources
    },
    SectionDescriptor("[build] resources", SectionAction.AutoReload(rebuild = true)) {
      it.build.resources
    },
    SectionDescriptor("[build] test_resources", SectionAction.AutoReload(rebuild = true)) {
      it.build.testResources
    },
    SectionDescriptor("[fmt]", SectionAction.NoOp) { it.fmt },
    SectionDescriptor("[dependencies]", SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL)) {
      it.dependencies
    },
    SectionDescriptor("[test-dependencies]", SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL)) {
      it.testDependencies
    },
    SectionDescriptor("[repositories]", SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL)) {
      it.repositories
    },
    SectionDescriptor("[[cinterop]]", SectionAction.NotifyOnly(RECOMMEND_RESTART_CINTEROP)) {
      it.cinterop
    },
    SectionDescriptor("[classpaths]", SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL)) {
      it.classpaths
    },
    SectionDescriptor("[test.sys_props]", SectionAction.AutoReload(rebuild = false)) {
      it.testSection.sysProps
    },
    SectionDescriptor("[run.sys_props]", SectionAction.AutoReload(rebuild = false)) {
      it.runSection.sysProps
    },
  )

/**
 * Section name → SectionAction view derived from SECTIONS. Used by callers that only need the
 * action (e.g. notification formatting) and by the schema-coverage cross-validation test.
 */
internal val SECTION_ACTIONS: Map<String, SectionAction> =
  SECTIONS.associate { it.name to it.action }

internal const val DEFERRED_RECOMMENDATION =
  "This section is not yet classified; please file an issue or update ChangeMatrix.kt"

internal fun action(name: String): SectionAction =
  SECTION_ACTIONS[name] ?: SectionAction.NotifyOnly(DEFERRED_RECOMMENDATION)

/**
 * Classify the section-level diff between two KoltConfig snapshots. Returns one SectionChange per
 * differing section. Equal configs produce an empty list.
 */
fun classifyChange(old: KoltConfig, new: KoltConfig): List<SectionChange> {
  if (old == new) return emptyList()
  return SECTIONS.mapNotNull { desc ->
    if (desc.extract(old) != desc.extract(new)) SectionChange(desc.name, desc.action) else null
  }
}

/**
 * Decide reload / rebuild / notification dispatch for a list of section changes.
 *
 * Notify-only-prevail rule: if any change carries NotifyOnly, the entire window is treated as
 * notify-only — no reload, no rebuild, only notifications are emitted. AutoReload sections detected
 * in the same window are deferred until the user takes the recommended explicit action.
 */
fun planDispatch(changes: List<SectionChange>): DispatchPlan {
  val notifyOnly = changes.filter { it.action is SectionAction.NotifyOnly }
  if (notifyOnly.isNotEmpty()) {
    val notifications =
      notifyOnly.map {
        val recommendation = (it.action as SectionAction.NotifyOnly).recommendation
        "$NOTIFICATION_MARKER ${it.sectionName} changed; $recommendation"
      }
    return DispatchPlan(
      reload = false,
      rebuild = false,
      notifications = notifications,
      changedSections = changes,
    )
  }

  val autoReloads = changes.filter { it.action is SectionAction.AutoReload }
  if (autoReloads.isEmpty()) {
    return DispatchPlan(
      reload = false,
      rebuild = false,
      notifications = emptyList(),
      changedSections = changes,
    )
  }

  val rebuildRequired = autoReloads.any { (it.action as SectionAction.AutoReload).rebuild }
  return DispatchPlan(
    reload = true,
    rebuild = rebuildRequired,
    notifications = emptyList(),
    changedSections = changes,
  )
}
