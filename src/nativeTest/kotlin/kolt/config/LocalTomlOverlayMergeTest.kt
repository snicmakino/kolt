package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalTomlOverlayMergeTest {

  private val baseToml =
    """
        name = "demo"
        version = "0.0.0"

        [kotlin]
        version = "2.0.0"

        [build]
        target = "jvm"
        main = "com.example.main"
        sources = ["src"]

    """
      .trimIndent()

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

  @Test
  fun parseConfigOverlayNullBranchProducesSameResultAsBaseOnly() {
    val baseOnlyResult = parseConfig(baseToml, path = "kolt.toml")
    val baseOnly =
      assertNotNull(
        baseOnlyResult.get(),
        "base-only parseConfig failed: ${baseOnlyResult.getError()}",
      )
    val withNullOverlayResult =
      parseConfig(baseToml, path = "kolt.toml", overlayString = null, overlayPath = null)
    val withNullOverlay =
      assertNotNull(
        withNullOverlayResult.get(),
        "overlay-null parseConfig failed: ${withNullOverlayResult.getError()}",
      )
    assertEquals(baseOnly, withNullOverlay)
  }

  @Test
  fun parseConfigOverlayPresentMergesTestSysProps() {
    val base =
      baseToml +
        """
            [test.sys_props]
            logger = { literal = "base-test" }
            "base.only" = { literal = "base-test-only" }
        """
          .trimIndent()
    val overlay =
      """
        [test.sys_props]
        logger = { literal = "overlay-test" }
        "overlay.only" = { literal = "overlay-test-only" }
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get()
      )

    assertEquals(
      SysPropValue.Literal("overlay-test"),
      config.testSection.sysProps["logger"],
      "expected overlay value to replace base value under [test.sys_props]",
    )
    assertEquals(SysPropValue.Literal("base-test-only"), config.testSection.sysProps["base.only"])
    assertEquals(
      SysPropValue.Literal("overlay-test-only"),
      config.testSection.sysProps["overlay.only"],
    )
  }

  @Test
  fun parseConfigOverlayPresentMergesRunSysProps() {
    val base =
      baseToml +
        """
            [run.sys_props]
            logger = { literal = "base-run" }
        """
          .trimIndent()
    val overlay =
      """
        [run.sys_props]
        logger = { literal = "overlay-run" }
        port = { literal = "9090" }
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get()
      )

    assertEquals(SysPropValue.Literal("overlay-run"), config.runSection.sysProps["logger"])
    assertEquals(SysPropValue.Literal("9090"), config.runSection.sysProps["port"])
  }

  @Test
  fun parseConfigRejectsMergedRawWithEmptyRepositoryUrl() {
    val raw =
      mapOf("custom" to RawRepository(url = ""), "central" to RawRepository(url = "https://x"))
    val err = liftRepositoriesMap(raw).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    assertTrue(
      "custom" in parseFailed.message && "url" in parseFailed.message,
      "expected error naming repository 'custom' and 'url'; actual: ${parseFailed.message}",
    )
  }

  @Test
  fun parseConfigRejectsOverlayStringWithoutOverlayPath() {
    val err =
      parseConfig(baseToml, path = "kolt.toml", overlayString = "", overlayPath = null).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    assertTrue(
      "overlayString" in parseFailed.message && "overlayPath" in parseFailed.message,
      "expected precondition error naming both overlay parameters; actual: ${parseFailed.message}",
    )
  }

  @Test
  fun parseConfigOverlayClasspathRefResolvesAgainstBaseBundle() {
    val base =
      baseToml +
        """
            [classpaths.bta-impl]
            "org.jetbrains.kotlin:kotlin-build-tools-impl" = "2.3.20"
        """
          .trimIndent()
    val overlay =
      """
        [test.sys_props]
        "kolt.ic.btaImplClasspath" = { classpath = "bta-impl" }
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get(),
        "expected overlay classpath-ref to resolve against base bundle post-merge",
      )

    val value =
      assertIs<SysPropValue.ClasspathRef>(
        config.testSection.sysProps["kolt.ic.btaImplClasspath"],
        "overlay-sourced sysprop must lift to ClasspathRef in the merged config",
      )
    assertEquals("bta-impl", value.bundleName)
  }

  @Test
  fun parseConfigOverlayRepositoryUrlReplacedByOverlay() {
    val base =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"
        """
          .trimIndent()
    val overlay =
      """
        [repositories.central]
        url = "https://mirror.example.com/m2"
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get(),
        "expected overlay url to replace base url for matching repository name",
      )

    assertEquals(
      "https://mirror.example.com/m2",
      config.repositories["central"]?.url,
      "expected overlay url to replace base url for repository 'central'",
    )
  }

  @Test
  fun parseConfigOverlayOnlyRepositoryNameIsRejected() {
    val base =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"
        """
          .trimIndent()
    val overlay =
      """
        [repositories.unknown]
        url = "https://private.example.com/m2"
      """
        .trimIndent()

    val err =
      parseConfig(
          base,
          path = "kolt.toml",
          overlayString = overlay,
          overlayPath = "kolt.local.toml",
        )
        .getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      "unknown" in rendered && "kolt.local.toml" in rendered,
      "expected error naming 'unknown' and 'kolt.local.toml'; actual: $rendered",
    )
  }

  @Test
  fun parseConfigOverlayPreservesBaseRepositoryDeclarationOrder() {
    val base =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"

            [repositories.internal]
            url = "https://internal.example.com/m2"

            [repositories.jitpack]
            url = "https://jitpack.io"
        """
          .trimIndent()
    val overlay =
      """
        [repositories.internal]
        url = "https://mirror.example.com/internal"
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get(),
        "expected overlay overriding a middle entry to preserve base declaration order",
      )

    assertEquals(
      listOf("central", "internal", "jitpack"),
      config.repositories.keys.toList(),
      "merged repositories must keep base declaration order",
    )
    assertEquals(
      "https://mirror.example.com/internal",
      config.repositories["internal"]?.url,
      "overlay must still replace the middle repository's url",
    )
  }

  @Test
  fun parseConfigOverlayEmptyUrlIsRejectedPostMerge() {
    val base =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"
        """
          .trimIndent()
    val overlay =
      """
        [repositories.central]
        url = ""
      """
        .trimIndent()

    val err =
      parseConfig(
          base,
          path = "kolt.toml",
          overlayString = overlay,
          overlayPath = "kolt.local.toml",
        )
        .getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    assertTrue(
      "central" in parseFailed.message && "has no url" in parseFailed.message,
      "expected post-merge empty-url rejection naming 'central'; actual: ${parseFailed.message}",
    )
  }

  @Test
  fun parseConfigOverlayEnvLiteralIsPreservedVerbatim() {
    val overlay =
      """
        [test.sys_props]
        "kolt.fixture.home" = { literal = "${'$'}{env.HOME}/fixtures" }
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            baseToml,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get(),
        "overlay literal containing \${env.X} must pass through the merged-result " +
          "validation pipeline unchanged (env-agnostic rule = no interpolation performed)",
      )

    val value =
      assertIs<SysPropValue.Literal>(
        config.testSection.sysProps["kolt.fixture.home"],
        "overlay literal must lift to SysPropValue.Literal",
      )
    assertEquals(
      "\${env.HOME}/fixtures",
      value.value,
      "overlay literal must be preserved verbatim; no env interpolation occurs at parse time",
    )
  }

  @Test
  fun parseConfigOverlayMergesWhenBaseKeyIsQuotedButOverlayKeyIsBare() {
    val base =
      baseToml +
        """
          [repositories."central"]
          url = "https://repo1.maven.org/maven2"
        """
          .trimIndent()
    val overlay =
      """
        [repositories.central]
        url = "https://mirror.example.com/m2"
      """
        .trimIndent()

    val config =
      assertNotNull(
        parseConfig(
            base,
            path = "kolt.toml",
            overlayString = overlay,
            overlayPath = "kolt.local.toml",
          )
          .get(),
        "merging quoted-base + bare-overlay key must succeed without falsely " +
          "rejecting 'central' as an overlay-only name",
      )

    assertEquals("https://mirror.example.com/m2", config.repositories["central"]?.url)
  }
}
