package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kolt.config.ConfigError
import kolt.config.SectionAction
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchChangeDispatchTest {
  @Test
  fun parseFailureProducesParseErrorOutcome() {
    val current = testConfig()
    val outcome =
      dispatchKoltTomlChange(current, Err(ConfigError.ParseFailed("unexpected token at line 5")))

    val parseError = assertIs<KoltTomlChangeOutcome.ParseError>(outcome)
    assertTrue(parseError.message.contains("unexpected token at line 5"))
  }

  @Test
  fun configEqualToCurrentProducesNoChangeOutcome() {
    val current = testConfig()
    val outcome = dispatchKoltTomlChange(current, Ok(current))

    assertEquals(KoltTomlChangeOutcome.NoChange, outcome)
  }

  @Test
  fun notifyOnlySectionChangeProducesNotifyOnlyOutcome() {
    val current = testConfig()
    val newConfig = current.copy(dependencies = mapOf("io.example:foo" to "1.0.0"))
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val notify = assertIs<KoltTomlChangeOutcome.NotifyOnly>(outcome)
    assertEquals(1, notify.notifications.size)
    assertTrue(notify.notifications[0].contains("[dependencies]"))
    assertTrue(notify.notifications[0].contains("Run kolt deps install"))
  }

  @Test
  fun mixedWindowDispatchPrevailsAsNotifyOnly() {
    val current = testConfig()
    val newConfig =
      current.copy(
        dependencies = mapOf("io.example:foo" to "1.0.0"),
        build = current.build.copy(sources = listOf("src", "extra")),
      )
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val notify = assertIs<KoltTomlChangeOutcome.NotifyOnly>(outcome)
    assertEquals(1, notify.notifications.size)
    assertTrue(notify.notifications[0].contains("[dependencies]"))
  }

  @Test
  fun pureAutoReloadRebuildRequiredProducesReloadWithRebuild() {
    val current = testConfig(sources = listOf("src"))
    val newConfig = current.copy(build = current.build.copy(sources = listOf("src", "extra")))
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val reload = assertIs<KoltTomlChangeOutcome.Reload>(outcome)
    assertEquals(newConfig, reload.newConfig)
    assertEquals(true, reload.rebuild)
    assertEquals(true, reload.watcherRebuildNeeded)
  }

  @Test
  fun pureAutoReloadNoRebuildProducesReloadWithoutRebuild() {
    val current = testConfig()
    val newConfig =
      current.copy(
        runSection =
          kolt.config.RunSection(
            sysProps = mapOf("port" to kolt.config.SysPropValue.Literal("8080"))
          )
      )
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val reload = assertIs<KoltTomlChangeOutcome.Reload>(outcome)
    assertEquals(false, reload.rebuild)
    assertEquals(false, reload.watcherRebuildNeeded)
    assertTrue(reload.changedSections.any { it.sectionName == "[run.sys_props]" })
  }

  @Test
  fun reloadWatcherRebuildIsTriggeredByBuildSourcesOrResourcesChange() {
    val current = testConfig()
    val newConfig =
      current.copy(build = current.build.copy(resources = listOf("resources", "extra-res")))
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val reload = assertIs<KoltTomlChangeOutcome.Reload>(outcome)
    assertEquals(true, reload.watcherRebuildNeeded)
  }

  @Test
  fun reloadWatcherRebuildIsNotTriggeredByOtherAutoReloadSections() {
    val current = testConfig()
    val newConfig = current.copy(name = "renamed")
    val outcome = dispatchKoltTomlChange(current, Ok(newConfig))

    val reload = assertIs<KoltTomlChangeOutcome.Reload>(outcome)
    assertEquals(true, reload.rebuild)
    assertEquals(false, reload.watcherRebuildNeeded)
    assertTrue(reload.changedSections.any { it.sectionName == "name" })
    assertTrue(reload.changedSections.all { it.action is SectionAction.AutoReload })
  }
}
