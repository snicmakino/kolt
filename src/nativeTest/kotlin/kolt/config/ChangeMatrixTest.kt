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
}
