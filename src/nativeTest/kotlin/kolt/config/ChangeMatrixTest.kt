package kolt.config

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeMatrixTest {
  @Test
  fun planDispatchOfEmptyChangesReturnsEmptyPlan() {
    val plan = planDispatch(emptyList())

    assertEquals(false, plan.reload)
    assertEquals(false, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
    assertTrue(plan.changedSections.isEmpty())
  }

  @Test
  fun notificationMarkerIsExposed() {
    assertEquals("[watch] ⚠", NOTIFICATION_MARKER)
  }

  // --- classifyChange identity ---

  @Test
  fun classifyChangeReturnsEmptyListForEqualConfigs() {
    val config = testConfig()
    assertEquals(emptyList(), classifyChange(config, config))
  }

  // --- classifyChange: top-level scalars ---

  @Test
  fun classifyChangeDetectsNameChange() {
    val old = testConfig(name = "old-name")
    val new = old.copy(name = "new-name")
    assertEquals(
      listOf(SectionChange("name", SectionAction.AutoReload(rebuild = true))),
      classifyChange(old, new),
    )
  }

  @Test
  fun classifyChangeDetectsVersionChange() {
    val old = testConfig()
    val new = old.copy(version = "0.2.0")
    assertEquals(
      listOf(SectionChange("version", SectionAction.AutoReload(rebuild = true))),
      classifyChange(old, new),
    )
  }

  @Test
  fun classifyChangeDetectsKindChange() {
    val old = testConfig().copy(kind = "app")
    val new = old.copy(kind = "lib")
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("kind", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  // --- classifyChange: [kotlin] sub-fields ---

  @Test
  fun classifyChangeDetectsKotlinCompilerChange() {
    val old = testConfig(kotlinCompiler = "2.1.0")
    val new = old.copy(kotlin = old.kotlin.copy(compiler = "2.2.0"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[kotlin] compiler", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsKotlinVersionChange() {
    val old = testConfig(kotlinVersion = "2.1.0")
    val new = old.copy(kotlin = old.kotlin.copy(version = "2.2.0"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[kotlin] version", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsKotlinPluginsChange() {
    val old = testConfig()
    val new = old.copy(kotlin = old.kotlin.copy(plugins = mapOf("serialization" to true)))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[kotlin.plugins]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  // --- classifyChange: [build] sub-fields ---

  @Test
  fun classifyChangeDetectsBuildTargetChange() {
    val old = testConfig(target = "jvm")
    val new = old.copy(build = old.build.copy(target = "linuxX64"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] target", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsBuildJvmTargetChange() {
    val old = testConfig(jvmTarget = "17")
    val new = old.copy(build = old.build.copy(jvmTarget = "21"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] jvm_target", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsBuildJdkChange() {
    val old = testConfig(jdk = null)
    val new = old.copy(build = old.build.copy(jdk = "21"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] jdk", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsBuildMainChange() {
    val old = testConfig()
    val new = old.copy(build = old.build.copy(main = "com.example.NewMain"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] main", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = true), result[0].action)
  }

  @Test
  fun classifyChangeDetectsBuildSourcesChange() {
    val old = testConfig(sources = listOf("src"))
    val new = old.copy(build = old.build.copy(sources = listOf("src", "extra")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] sources", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = true), result[0].action)
  }

  @Test
  fun classifyChangeDetectsBuildTestSourcesChange() {
    val old = testConfig(testSources = listOf("test"))
    val new = old.copy(build = old.build.copy(testSources = listOf("test", "integration-test")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] test_sources", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = true), result[0].action)
  }

  @Test
  fun classifyChangeDetectsBuildResourcesChange() {
    val old = testConfig()
    val new = old.copy(build = old.build.copy(resources = listOf("resources")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] resources", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = true), result[0].action)
  }

  @Test
  fun classifyChangeDetectsBuildTestResourcesChange() {
    val old = testConfig()
    val new = old.copy(build = old.build.copy(testResources = listOf("test/resources")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[build] test_resources", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = true), result[0].action)
  }

  // --- classifyChange: [fmt] ---

  @Test
  fun classifyChangeDetectsFmtChangeAsNoOp() {
    val old = testConfig()
    val new = old.copy(fmt = FmtSection(style = "kotlin"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[fmt]", result[0].sectionName)
    assertEquals(SectionAction.NoOp, result[0].action)
  }

  // --- classifyChange: dependency-related sections ---

  @Test
  fun classifyChangeDetectsDependenciesChange() {
    val old = testConfig()
    val new = old.copy(dependencies = mapOf("io.example:foo" to "1.0.0"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[dependencies]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsTestDependenciesChange() {
    val old = testConfig()
    val new = old.copy(testDependencies = mapOf("io.example:bar" to "2.0.0"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[test-dependencies]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsRepositoriesChange() {
    val old = testConfig()
    val new = old.copy(repositories = mapOf("central" to "https://repo.example/maven"))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[repositories]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsCinteropChange() {
    val old = testConfig()
    val new = old.copy(cinterop = listOf(CinteropConfig(name = "libfoo", def = "libfoo.def")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[[cinterop]]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  @Test
  fun classifyChangeDetectsClasspathsChange() {
    val old = testConfig()
    val new = old.copy(classpaths = mapOf("bta" to mapOf("org.example:bta" to "1.0.0")))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[classpaths]", result[0].sectionName)
    assertTrue(result[0].action is SectionAction.NotifyOnly)
  }

  // --- classifyChange: sys_props sections ---

  @Test
  fun classifyChangeDetectsTestSysPropsChangeAsRuntimeOnly() {
    val old = testConfig()
    val new =
      old.copy(testSection = TestSection(sysProps = mapOf("foo" to SysPropValue.Literal("bar"))))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[test.sys_props]", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = false), result[0].action)
  }

  @Test
  fun classifyChangeDetectsRunSysPropsChangeAsRuntimeOnly() {
    val old = testConfig()
    val new =
      old.copy(runSection = RunSection(sysProps = mapOf("port" to SysPropValue.Literal("8080"))))
    val result = classifyChange(old, new)
    assertEquals(1, result.size)
    assertEquals("[run.sys_props]", result[0].sectionName)
    assertEquals(SectionAction.AutoReload(rebuild = false), result[0].action)
  }

  // --- planDispatch: branches ---

  @Test
  fun planDispatchOfAllNoOpProducesNoActionPlanWithChangesIntact() {
    val changes = listOf(SectionChange("[fmt]", SectionAction.NoOp))
    val plan = planDispatch(changes)
    assertEquals(false, plan.reload)
    assertEquals(false, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
    assertEquals(changes, plan.changedSections)
  }

  @Test
  fun planDispatchOfSingleAutoReloadRebuildSetsReloadAndRebuild() {
    val changes = listOf(SectionChange("[build] sources", SectionAction.AutoReload(rebuild = true)))
    val plan = planDispatch(changes)
    assertEquals(true, plan.reload)
    assertEquals(true, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
    assertEquals(changes, plan.changedSections)
  }

  @Test
  fun planDispatchOfSingleAutoReloadNoRebuildSetsReloadOnly() {
    val changes =
      listOf(SectionChange("[run.sys_props]", SectionAction.AutoReload(rebuild = false)))
    val plan = planDispatch(changes)
    assertEquals(true, plan.reload)
    assertEquals(false, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
    assertEquals(changes, plan.changedSections)
  }

  @Test
  fun planDispatchOfMixedAutoReloadCombinesRebuildFlagToTrueIfAny() {
    val changes =
      listOf(
        SectionChange("[run.sys_props]", SectionAction.AutoReload(rebuild = false)),
        SectionChange("[build] sources", SectionAction.AutoReload(rebuild = true)),
      )
    val plan = planDispatch(changes)
    assertEquals(true, plan.reload)
    assertEquals(true, plan.rebuild)
    assertTrue(plan.notifications.isEmpty())
  }

  @Test
  fun planDispatchOfSingleNotifyOnlyEmitsNotificationAndSkipsReload() {
    val changes =
      listOf(SectionChange("[dependencies]", SectionAction.NotifyOnly("Run kolt deps install")))
    val plan = planDispatch(changes)
    assertEquals(false, plan.reload)
    assertEquals(false, plan.rebuild)
    assertEquals(1, plan.notifications.size)
    assertEquals(changes, plan.changedSections)
  }

  @Test
  fun planDispatchNotificationFormatIncludesMarkerSectionAndRecommendation() {
    val changes =
      listOf(SectionChange("[dependencies]", SectionAction.NotifyOnly("Run kolt deps install")))
    val plan = planDispatch(changes)
    assertEquals("[watch] ⚠ [dependencies] changed; Run kolt deps install", plan.notifications[0])
  }

  @Test
  fun planDispatchOfMixedWindowPrevailsAsNotifyOnly() {
    val changes =
      listOf(
        SectionChange("[build] sources", SectionAction.AutoReload(rebuild = true)),
        SectionChange("[dependencies]", SectionAction.NotifyOnly("Run kolt deps install")),
      )
    val plan = planDispatch(changes)
    assertEquals(false, plan.reload)
    assertEquals(false, plan.rebuild)
    assertEquals(1, plan.notifications.size)
    assertEquals("[watch] ⚠ [dependencies] changed; Run kolt deps install", plan.notifications[0])
    assertEquals(2, plan.changedSections.size)
  }

  @Test
  fun planDispatchEmitsOneNotificationLinePerNotifyOnlySection() {
    val changes =
      listOf(
        SectionChange("[dependencies]", SectionAction.NotifyOnly("Run kolt deps install")),
        SectionChange(
          "[kotlin] compiler",
          SectionAction.NotifyOnly("Run kolt daemon stop --all and restart watch"),
        ),
      )
    val plan = planDispatch(changes)
    assertEquals(2, plan.notifications.size)
    assertTrue(plan.notifications.any { it.contains("[dependencies]") })
    assertTrue(plan.notifications.any { it.contains("[kotlin] compiler") })
  }

  @Test
  fun planDispatchInvariantNotificationsImplyNoReloadOrRebuild() {
    val changes =
      listOf(
        SectionChange("[dependencies]", SectionAction.NotifyOnly("Run kolt deps install")),
        SectionChange("[build] sources", SectionAction.AutoReload(rebuild = true)),
        SectionChange("[run.sys_props]", SectionAction.AutoReload(rebuild = false)),
      )
    val plan = planDispatch(changes)
    if (plan.notifications.isNotEmpty()) {
      assertEquals(false, plan.reload)
      assertEquals(false, plan.rebuild)
    }
  }

  // --- classifyChange: defensive fallback for schema-unknown section names ---

  @Test
  fun actionFallsBackToNotifyOnlyForUnknownSectionName() {
    val unknown = action("[future.section]")
    assertTrue(unknown is SectionAction.NotifyOnly)
    assertEquals(DEFERRED_RECOMMENDATION, (unknown as SectionAction.NotifyOnly).recommendation)
  }

  // --- classifyChange: multi-section ---

  @Test
  fun classifyChangeDetectsMultipleSimultaneousChanges() {
    val old = testConfig()
    val new =
      old.copy(
        name = "renamed",
        dependencies = mapOf("io.example:foo" to "1.0.0"),
        build = old.build.copy(sources = listOf("src", "extra")),
      )

    val changes = classifyChange(old, new)
    assertEquals(3, changes.size)
    val names = changes.map { it.sectionName }.toSet()
    assertEquals(setOf("name", "[dependencies]", "[build] sources"), names)
  }

  // --- schema-coverage cross-validation ---

  @Test
  fun matrixCoverageMatchesKoltConfigSections() {
    // Hardcoded list of every section / top-level scalar that a contributor must classify in
    // SECTION_ACTIONS when adding to KoltConfig. Kept independent from SECTION_ACTIONS so that
    // updating one without the other fails this test loudly. The second defensive line is the
    // ADR 0033 maintenance clause + CONTRIBUTING note near the kolt.toml schema definition.
    val expected =
      setOf(
        "name",
        "version",
        "kind",
        "[kotlin] compiler",
        "[kotlin] version",
        "[kotlin.plugins]",
        "[build] target",
        "[build] jvm_target",
        "[build] jdk",
        "[build] main",
        "[build] sources",
        "[build] test_sources",
        "[build] resources",
        "[build] test_resources",
        "[fmt]",
        "[dependencies]",
        "[test-dependencies]",
        "[repositories]",
        "[[cinterop]]",
        "[classpaths]",
        "[test.sys_props]",
        "[run.sys_props]",
      )
    val actual = SECTION_ACTIONS.keys

    assertEquals(
      expected,
      actual,
      "drift between KoltConfig section list and SECTION_ACTIONS — " +
        "missing in matrix: ${expected - actual}; extra in matrix: ${actual - expected}",
    )
  }
}
