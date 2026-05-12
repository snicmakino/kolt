package kolt.config

import com.github.michaelbull.result.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalTomlOverlayMergeTest {

  private fun baseConfig(test: RawTestSection? = null, run: RawRunSection? = null): RawKoltConfig =
    RawKoltConfig(
      name = "demo",
      version = "0.0.0",
      kotlin = KotlinSection(version = "2.0.0"),
      build = RawBuildSection(target = "jvm", main = "Main", sources = listOf("src")),
      test = test,
      run = run,
    )

  @Test
  fun sameKeyReplacesBaseValueInBothSections() {
    val base =
      baseConfig(
        test = RawTestSection(sysProps = mapOf("logger" to RawSysPropValue(literal = "base-test"))),
        run = RawRunSection(sysProps = mapOf("logger" to RawSysPropValue(literal = "base-run"))),
      )
    val overlay =
      RawLocalOverlayConfig(
        test =
          RawTestSection(sysProps = mapOf("logger" to RawSysPropValue(literal = "overlay-test"))),
        run = RawRunSection(sysProps = mapOf("logger" to RawSysPropValue(literal = "overlay-run"))),
      )

    val merged = assertNotNull(mergeOverlay(base, overlay, overlayPath = "kolt.local.toml").get())

    assertEquals(
      "overlay-test",
      merged.test?.sysProps?.get("logger")?.literal,
      "expected overlay value to replace base value under [test.sys_props]",
    )
    assertEquals(
      "overlay-run",
      merged.run?.sysProps?.get("logger")?.literal,
      "expected overlay value to replace base value under [run.sys_props]",
    )
  }

  @Test
  fun newKeyIsUnionedAcrossBothSections() {
    val base =
      baseConfig(
        test =
          RawTestSection(sysProps = mapOf("base.only" to RawSysPropValue(literal = "base-test"))),
        run = RawRunSection(sysProps = mapOf("base.only" to RawSysPropValue(literal = "base-run"))),
      )
    val overlay =
      RawLocalOverlayConfig(
        test =
          RawTestSection(
            sysProps = mapOf("overlay.only" to RawSysPropValue(literal = "overlay-test"))
          ),
        run =
          RawRunSection(
            sysProps = mapOf("overlay.only" to RawSysPropValue(literal = "overlay-run"))
          ),
      )

    val merged = assertNotNull(mergeOverlay(base, overlay, overlayPath = "kolt.local.toml").get())

    val testProps = assertNotNull(merged.test?.sysProps)
    assertEquals("base-test", testProps["base.only"]?.literal)
    assertEquals("overlay-test", testProps["overlay.only"]?.literal)
    assertEquals(setOf("base.only", "overlay.only"), testProps.keys)

    val runProps = assertNotNull(merged.run?.sysProps)
    assertEquals("base-run", runProps["base.only"]?.literal)
    assertEquals("overlay-run", runProps["overlay.only"]?.literal)
    assertEquals(setOf("base.only", "overlay.only"), runProps.keys)
  }
}
