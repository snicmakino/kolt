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
 * Authoritative section name → SectionAction map. The keys here are the canonical section names
 * surfaced in user-facing notifications and used by all classifyChange callers. Adding a new
 * KoltConfig field requires updating both this map and the schema-coverage test.
 */
internal val SECTION_ACTIONS: Map<String, SectionAction> =
  mapOf(
    "name" to SectionAction.AutoReload(rebuild = true),
    "version" to SectionAction.AutoReload(rebuild = true),
    "kind" to SectionAction.NotifyOnly(RECOMMEND_RESTART_KIND),
    "[kotlin] compiler" to SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART),
    "[kotlin] version" to SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART),
    "[kotlin.plugins]" to SectionAction.NotifyOnly(RECOMMEND_DAEMON_RESTART),
    "[build] target" to SectionAction.NotifyOnly(RECOMMEND_RESTART_TARGET),
    "[build] jvm_target" to SectionAction.NotifyOnly(RECOMMEND_RESTART_JVM_TARGET),
    "[build] jdk" to SectionAction.NotifyOnly(RECOMMEND_RESTART_JDK),
    "[build] main" to SectionAction.AutoReload(rebuild = true),
    "[build] sources" to SectionAction.AutoReload(rebuild = true),
    "[build] test_sources" to SectionAction.AutoReload(rebuild = true),
    "[build] resources" to SectionAction.AutoReload(rebuild = true),
    "[build] test_resources" to SectionAction.AutoReload(rebuild = true),
    "[fmt]" to SectionAction.NoOp,
    "[dependencies]" to SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL),
    "[test-dependencies]" to SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL),
    "[repositories]" to SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL),
    "[[cinterop]]" to SectionAction.NotifyOnly(RECOMMEND_RESTART_CINTEROP),
    "[classpaths]" to SectionAction.NotifyOnly(RECOMMEND_DEPS_INSTALL),
    "[test.sys_props]" to SectionAction.AutoReload(rebuild = false),
    "[run.sys_props]" to SectionAction.AutoReload(rebuild = false),
  )

internal const val DEFERRED_RECOMMENDATION =
  "This section is not yet classified; please file an issue or update ChangeMatrix.kt"

internal fun action(name: String): SectionAction =
  SECTION_ACTIONS[name] ?: SectionAction.NotifyOnly(DEFERRED_RECOMMENDATION)

/**
 * Classify the section-level diff between two KoltConfig snapshots. Returns one SectionChange per
 * differing top-level scalar or section. Equal configs produce an empty list.
 */
fun classifyChange(old: KoltConfig, new: KoltConfig): List<SectionChange> {
  if (old == new) return emptyList()

  val changes = mutableListOf<SectionChange>()

  if (old.name != new.name) changes += SectionChange("name", action("name"))
  if (old.version != new.version) changes += SectionChange("version", action("version"))
  if (old.kind != new.kind) changes += SectionChange("kind", action("kind"))

  if (old.kotlin.compiler != new.kotlin.compiler) {
    changes += SectionChange("[kotlin] compiler", action("[kotlin] compiler"))
  }
  if (old.kotlin.version != new.kotlin.version) {
    changes += SectionChange("[kotlin] version", action("[kotlin] version"))
  }
  if (old.kotlin.plugins != new.kotlin.plugins) {
    changes += SectionChange("[kotlin.plugins]", action("[kotlin.plugins]"))
  }

  if (old.build.target != new.build.target) {
    changes += SectionChange("[build] target", action("[build] target"))
  }
  if (old.build.jvmTarget != new.build.jvmTarget) {
    changes += SectionChange("[build] jvm_target", action("[build] jvm_target"))
  }
  if (old.build.jdk != new.build.jdk) {
    changes += SectionChange("[build] jdk", action("[build] jdk"))
  }
  if (old.build.main != new.build.main) {
    changes += SectionChange("[build] main", action("[build] main"))
  }
  if (old.build.sources != new.build.sources) {
    changes += SectionChange("[build] sources", action("[build] sources"))
  }
  if (old.build.testSources != new.build.testSources) {
    changes += SectionChange("[build] test_sources", action("[build] test_sources"))
  }
  if (old.build.resources != new.build.resources) {
    changes += SectionChange("[build] resources", action("[build] resources"))
  }
  if (old.build.testResources != new.build.testResources) {
    changes += SectionChange("[build] test_resources", action("[build] test_resources"))
  }

  if (old.fmt != new.fmt) changes += SectionChange("[fmt]", action("[fmt]"))

  if (old.dependencies != new.dependencies) {
    changes += SectionChange("[dependencies]", action("[dependencies]"))
  }
  if (old.testDependencies != new.testDependencies) {
    changes += SectionChange("[test-dependencies]", action("[test-dependencies]"))
  }
  if (old.repositories != new.repositories) {
    changes += SectionChange("[repositories]", action("[repositories]"))
  }
  if (old.cinterop != new.cinterop) {
    changes += SectionChange("[[cinterop]]", action("[[cinterop]]"))
  }
  if (old.classpaths != new.classpaths) {
    changes += SectionChange("[classpaths]", action("[classpaths]"))
  }
  if (old.testSection.sysProps != new.testSection.sysProps) {
    changes += SectionChange("[test.sys_props]", action("[test.sys_props]"))
  }
  if (old.runSection.sysProps != new.runSection.sysProps) {
    changes += SectionChange("[run.sys_props]", action("[run.sys_props]"))
  }

  return changes
}

/**
 * Decide reload / rebuild / notification dispatch for a list of section changes.
 *
 * Stub: returns an empty plan.
 */
fun planDispatch(changes: List<SectionChange>): DispatchPlan {
  return DispatchPlan(
    reload = false,
    rebuild = false,
    notifications = emptyList(),
    changedSections = emptyList(),
  )
}
