package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.KOTLIN_VERSION_FLOOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InfoCommandTest {
  private val withProject =
    InfoSnapshot(
      koltVersion = "0.16.3",
      koltPath = "/usr/local/bin/kolt",
      koltHomeDisplay = "~/.kolt",
      koltHomeBytes = 142L * 1024 * 1024,
      kotlin = KotlinInfo("2.3.20", "daemon", "~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc"),
      jdk = JdkInfo("21", "~/.kolt/toolchains/jdk/21/bin/java"),
      host = "linux-x86_64",
      project = ProjectInfo("my-app", "0.1.0", "app", "jvm"),
    )

  private val verboseProject =
    withProject.copy(
      koltHomeBytes = (100L + 30L + 10L + 2L) * 1024 * 1024,
      koltHomeBreakdown =
        HomeBreakdown(
          cacheBytes = 100L * 1024 * 1024,
          toolchainsBytes = 30L * 1024 * 1024,
          daemonBytes = 10L * 1024 * 1024,
          toolsBytes = 2L * 1024 * 1024,
          cachePath = "~/.kolt/cache",
          toolchainsPath = "~/.kolt/toolchains",
          daemonPath = "~/.kolt/daemon",
          toolsPath = "~/.kolt/tools",
        ),
      kotlin =
        KotlinInfo(
          version = "2.3.20",
          mode = "daemon",
          path = "~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc",
          requestedVersion = "2.3.0",
          daemonBaseline = "2.3.0",
          subprocessFallbackReason = null,
        ),
      jdk =
        JdkInfo(version = "21", path = "~/.kolt/toolchains/jdk/21/bin/java", source = "managed"),
      project =
        ProjectInfo(
          name = "my-app",
          version = "0.1.0",
          kind = "app",
          target = "jvm",
          manifestPath = "/abs/path/kolt.toml",
          dependencyCount = 3,
          testDependencyCount = 1,
          enabledPlugins = listOf("serialization"),
        ),
    )

  @Test
  fun formatsAllFieldsWhenInsideProject() {
    val lines = formatInfo(withProject).lines()

    assertEquals("kolt        v0.16.3 (/usr/local/bin/kolt)", lines[0])
    assertEquals("kolt home   ~/.kolt (142.0 MB)", lines[1])
    assertEquals(
      "kotlin      2.3.20 (daemon, ~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc)",
      lines[2],
    )
    assertEquals("jdk         21 (~/.kolt/toolchains/jdk/21/bin/java)", lines[3])
    assertEquals("host        linux-x86_64", lines[4])
    assertEquals("", lines[5])
    assertEquals("project     my-app v0.1.0", lines[6])
    assertEquals("kind        app", lines[7])
    assertEquals("target      jvm", lines[8])
  }

  @Test
  fun showsOutsideProjectHintInsteadOfProjectSection() {
    val snap = withProject.copy(kotlin = null, jdk = null, project = null)
    val lines = formatInfo(snap).lines()

    assertTrue(lines[0].startsWith("kolt        v0.16.3"))
    assertTrue(lines.any { it.startsWith("host") })
    assertFalse(lines.any { it.startsWith("kotlin") })
    assertFalse(lines.any { it.startsWith("project") })
    assertTrue(
      lines.any { it.contains("not in a kolt project") },
      "must tell the user why the project section is absent",
    )
  }

  @Test
  fun hidesHomeSizeWhenKoltHomeMissing() {
    val snap = withProject.copy(koltHomeBytes = null)
    val line = formatInfo(snap).lines()[1]
    assertEquals("kolt home   ~/.kolt", line)
  }

  @Test
  fun kotlinLineShowsFloorHintWhenSubprocess() {
    // Anchor on the live KOTLIN_VERSION_FLOOR so this test follows the
    // floor when the daemon family bumps, instead of going stale silently.
    val snap =
      withProject.copy(
        kotlin =
          KotlinInfo(
            "2.2.0",
            "subprocess [<$KOTLIN_VERSION_FLOOR]",
            "~/.kolt/toolchains/kotlinc/2.2.0/bin/kotlinc",
          )
      )
    val kotlinLine = formatInfo(snap).lines().first { it.startsWith("kotlin") }
    assertTrue(
      kotlinLine.contains("<$KOTLIN_VERSION_FLOOR"),
      "subprocess mode must disclose the daemon floor: $kotlinLine",
    )
  }

  @Test
  fun abbreviateHomePathReplacesHomeWithTilde() {
    assertEquals("~/.kolt", abbreviateHomePath("/home/alice/.kolt", "/home/alice"))
    assertEquals("/etc/kolt", abbreviateHomePath("/etc/kolt", "/home/alice"))
    assertEquals("~", abbreviateHomePath("/home/alice", "/home/alice"))
  }

  @Test
  fun abbreviateHomePathLeavesPathAloneWhenHomeIsEmpty() {
    // Guards against a bug where empty home would turn prefix into "/"
    // and mangle every absolute path into "~/..." form.
    assertEquals("/usr/local/bin/kolt", abbreviateHomePath("/usr/local/bin/kolt", ""))
    assertEquals("/home/alice/.kolt", abbreviateHomePath("/home/alice/.kolt", ""))
  }

  @Test
  fun verboseExpandsKoltHomeIntoBreakdown() {
    val lines = formatInfo(verboseProject, verbose = true).lines()

    val homeLineIdx = lines.indexOfFirst { it.startsWith("kolt home") }
    assertTrue(homeLineIdx >= 0)
    assertEquals("  cache         ~/.kolt/cache (100.0 MB)", lines[homeLineIdx + 1])
    assertEquals("  toolchains    ~/.kolt/toolchains (30.0 MB)", lines[homeLineIdx + 2])
    assertEquals("  daemon        ~/.kolt/daemon (10.0 MB)", lines[homeLineIdx + 3])
    assertEquals("  tools         ~/.kolt/tools (2.0 MB)", lines[homeLineIdx + 4])
  }

  @Test
  fun verboseExpandsKotlinSection() {
    val lines = formatInfo(verboseProject, verbose = true).lines()
    val kotlinIdx = lines.indexOfFirst { it.startsWith("kotlin") }

    assertTrue(kotlinIdx >= 0)
    assertEquals("  requested     2.3.0", lines[kotlinIdx + 1])
    assertEquals("  resolved      2.3.20", lines[kotlinIdx + 2])
    assertEquals(
      "  compiler      ~/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc",
      lines[kotlinIdx + 3],
    )
    assertEquals("  daemon base   2.3.0", lines[kotlinIdx + 4])
  }

  @Test
  fun verboseExpandsKotlinSubprocessFallback() {
    val snap =
      verboseProject.copy(
        kotlin =
          verboseProject.kotlin!!.copy(
            version = "2.2.0",
            mode = "subprocess [<$KOTLIN_VERSION_FLOOR]",
            path = "~/.kolt/toolchains/kotlinc/2.2.0/bin/kotlinc",
            requestedVersion = "2.2.0",
            subprocessFallbackReason =
              "compiler 2.2.0 is below daemon baseline $KOTLIN_VERSION_FLOOR",
          )
      )
    val lines = formatInfo(snap, verbose = true).lines()

    assertTrue(lines.any { it.trim().startsWith("fallback") && it.contains(KOTLIN_VERSION_FLOOR) })
  }

  @Test
  fun verboseExpandsJdkSection() {
    val lines = formatInfo(verboseProject, verbose = true).lines()
    val jdkIdx = lines.indexOfFirst { it.startsWith("jdk") }

    assertTrue(jdkIdx >= 0)
    assertEquals("  path          ~/.kolt/toolchains/jdk/21/bin/java", lines[jdkIdx + 1])
    assertEquals("  source        managed", lines[jdkIdx + 2])
  }

  @Test
  fun verboseExpandsProjectSection() {
    val lines = formatInfo(verboseProject, verbose = true).lines()
    val projectIdx = lines.indexOfFirst { it.startsWith("project") }

    assertTrue(projectIdx >= 0)
    assertEquals("  manifest      /abs/path/kolt.toml", lines[projectIdx + 1])
    assertEquals("  kind          app", lines[projectIdx + 2])
    assertEquals("  target        jvm", lines[projectIdx + 3])
    assertEquals("  dependencies  3", lines[projectIdx + 4])
    assertEquals("  test deps     1", lines[projectIdx + 5])
    assertEquals("  plugins       serialization", lines[projectIdx + 6])
  }

  @Test
  fun verboseOmitsPluginsLineWhenNoneEnabled() {
    val snap =
      verboseProject.copy(project = verboseProject.project!!.copy(enabledPlugins = emptyList()))
    val lines = formatInfo(snap, verbose = true).lines()

    assertFalse(lines.any { it.trim().startsWith("plugins") })
  }

  @Test
  fun verboseOutsideProjectStillShowsHomeBreakdown() {
    val snap = verboseProject.copy(kotlin = null, jdk = null, project = null)
    val lines = formatInfo(snap, verbose = true).lines()

    assertTrue(lines.any { it.trim().startsWith("cache") })
    assertFalse(lines.any { it.startsWith("kotlin") })
    assertFalse(lines.any { it.startsWith("project") })
    assertTrue(lines.any { it.contains("not in a kolt project") })
  }

  @Test
  fun verboseFallsBackToSingleLineHomeWhenBreakdownMissing() {
    val snap = verboseProject.copy(koltHomeBreakdown = null)
    val lines = formatInfo(snap, verbose = true).lines()

    val homeLine = lines.first { it.startsWith("kolt home") }
    assertEquals("kolt home     ~/.kolt (142.0 MB)", homeLine)
    val nextLine = lines[lines.indexOf(homeLine) + 1]
    assertFalse(nextLine.trim().startsWith("cache"))
  }

  @Test
  fun jsonFormatEmitsAllFields() {
    val json = formatInfoJson(verboseProject)

    assertTrue(json.contains("\"version\": \"0.16.3\""))
    assertTrue(json.contains("\"homeBytes\""))
    assertTrue(json.contains("\"cacheBytes\": 104857600"))
    assertTrue(json.contains("\"requestedVersion\": \"2.3.0\""))
    assertTrue(json.contains("\"resolvedVersion\": \"2.3.20\""))
    assertTrue(json.contains("\"daemonBaseline\": \"2.3.0\""))
    assertTrue(json.contains("\"source\": \"managed\""))
    assertTrue(json.contains("\"manifestPath\": \"/abs/path/kolt.toml\""))
    assertTrue(json.contains("\"dependencyCount\": 3"))
    assertTrue(json.contains("\"enabledPlugins\""))
    assertTrue(json.contains("\"host\": \"linux-x86_64\""))
  }

  @Test
  fun jsonFormatOmitsUnavailableFields() {
    val snap = verboseProject.copy(kotlin = null, jdk = null, project = null)
    val json = formatInfoJson(snap)

    assertFalse(json.contains("\"kotlin\""))
    assertFalse(json.contains("\"jdk\""))
    assertFalse(json.contains("\"project\""))
    assertFalse(json.contains("null"))
  }

  @Test
  fun jsonFormatOmitsSubprocessFallbackReasonWhenAbsent() {
    val json = formatInfoJson(verboseProject)
    assertFalse(json.contains("subprocessFallbackReason"))
  }

  @Test
  fun parseInfoArgsAcceptsEmpty() {
    val opts = parseInfoArgs(emptyList()).getOrElse { fail("expected Ok, got $it") }
    assertFalse(opts.verbose)
    assertFalse(opts.json)
  }

  @Test
  fun parseInfoArgsAcceptsVerbose() {
    val opts = parseInfoArgs(listOf("--verbose")).getOrElse { fail("expected Ok, got $it") }
    assertTrue(opts.verbose)
    assertFalse(opts.json)
  }

  @Test
  fun parseInfoArgsAcceptsFormatJson() {
    val opts = parseInfoArgs(listOf("--format=json")).getOrElse { fail("expected Ok, got $it") }
    assertFalse(opts.verbose)
    assertTrue(opts.json)
  }

  @Test
  fun parseInfoArgsAcceptsBoth() {
    val opts =
      parseInfoArgs(listOf("--verbose", "--format=json")).getOrElse { fail("expected Ok, got $it") }
    assertTrue(opts.verbose)
    assertTrue(opts.json)
  }

  @Test
  fun parseInfoArgsRejectsUnknownFlag() {
    val result = parseInfoArgs(listOf("--bogus"))
    result.getOrElse {
      assertTrue(it.contains("usage:"))
      return
    }
    fail("expected Err, got Ok")
  }

  @Test
  fun jsonFormatIsParseable() {
    val parsed = Json.parseToJsonElement(formatInfoJson(verboseProject)).jsonObject

    val kolt = parsed["kolt"]?.jsonObject ?: fail("missing kolt object")
    assertEquals("0.16.3", kolt["version"]?.jsonPrimitive?.content)
    val kotlin = parsed["kotlin"]?.jsonObject ?: fail("missing kotlin object")
    assertEquals("2.3.20", kotlin["resolvedVersion"]?.jsonPrimitive?.content)
    val project = parsed["project"]?.jsonObject ?: fail("missing project object")
    assertEquals("my-app", project["name"]?.jsonPrimitive?.content)
  }

  @Test
  fun jsonFormatOmitsKoltPathWhenUnknown() {
    val snap = verboseProject.copy(koltPath = null)
    val parsed = Json.parseToJsonElement(formatInfoJson(snap)).jsonObject
    val kolt = parsed["kolt"] as? JsonObject ?: fail("missing kolt object")
    assertFalse(kolt.containsKey("path"))
  }

  @Test
  fun verboseOmitsJdkSectionForNativeTarget() {
    val snap =
      verboseProject.copy(jdk = null, project = verboseProject.project!!.copy(target = "linuxX64"))
    val lines = formatInfo(snap, verbose = true).lines()

    assertFalse(lines.any { it.startsWith("jdk") })
    assertFalse(lines.any { it.trim().startsWith("source") })
    assertNotNull(lines.firstOrNull { it.startsWith("kotlin") })
  }

  @Test
  fun jsonOmitsJdkSectionForNativeTarget() {
    val snap =
      verboseProject.copy(jdk = null, project = verboseProject.project!!.copy(target = "linuxX64"))
    val parsed = Json.parseToJsonElement(formatInfoJson(snap)).jsonObject

    assertFalse(parsed.containsKey("jdk"))
    assertTrue(parsed.containsKey("project"))
  }

  @Test
  fun parseInfoArgsRejectsUnsupportedFormat() {
    val result = parseInfoArgs(listOf("--format=yaml"))
    result.getOrElse {
      assertTrue(it.contains("--format"))
      return
    }
    fail("expected Err, got Ok")
  }

  @Test
  fun parseErrorBannerReplacesNotInProjectBanner() {
    val snap =
      withProject.copy(
        kotlin = null,
        jdk = null,
        project = null,
        parseError = "kolt.toml:3: expected '='",
      )
    val text = formatInfo(snap)
    assertFalse(
      text.contains("not in a kolt project"),
      "parse-error must not masquerade as missing kolt.toml",
    )
    assertTrue(
      text.contains("kolt.toml failed to parse"),
      "must tell the user the project section is absent because the file is broken",
    )
  }

  @Test
  fun verboseParseErrorBannerReplacesNotInProjectBanner() {
    val snap =
      verboseProject.copy(
        kotlin = null,
        jdk = null,
        project = null,
        parseError = "kolt.toml:3: expected '='",
      )
    val text = formatInfo(snap, verbose = true)
    assertFalse(text.contains("not in a kolt project"))
    assertTrue(text.contains("kolt.toml failed to parse"))
  }

  @Test
  fun jsonEmitsParseErrorFieldWhenPresent() {
    val snap =
      verboseProject.copy(
        kotlin = null,
        jdk = null,
        project = null,
        parseError = "kolt.toml:3: expected '='",
      )
    val parsed = Json.parseToJsonElement(formatInfoJson(snap)).jsonObject
    assertEquals("kolt.toml:3: expected '='", parsed["parseError"]?.jsonPrimitive?.content)
    assertFalse(parsed.containsKey("project"))
  }

  @Test
  fun jsonOmitsParseErrorFieldWhenAbsent() {
    val parsed = Json.parseToJsonElement(formatInfoJson(verboseProject)).jsonObject
    assertFalse(parsed.containsKey("parseError"))
  }
}
