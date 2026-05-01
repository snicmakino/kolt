package kolt.cli

import kolt.build.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParseKoltArgsTest {

  @Test
  fun absentReleaseFlagYieldsDebugProfile() {
    val parsed = parseKoltArgs(listOf("build"))

    assertEquals(Profile.Debug, parsed.profile)
    assertEquals(listOf("build"), parsed.filteredArgs)
  }

  @Test
  fun presentReleaseFlagYieldsReleaseProfile() {
    val parsed = parseKoltArgs(listOf("--release", "build"))

    assertEquals(Profile.Release, parsed.profile)
  }

  @Test
  fun releaseFlagIsStrippedFromFilteredArgs() {
    val parsed = parseKoltArgs(listOf("--release", "build"))

    assertFalse(
      parsed.filteredArgs.contains("--release"),
      "filteredArgs must not leak --release into subcommand parsing: ${parsed.filteredArgs}",
    )
    assertEquals(listOf("build"), parsed.filteredArgs)
  }

  @Test
  fun releaseCombinesWithOtherKoltLevelFlags() {
    val parsed = parseKoltArgs(listOf("--no-daemon", "--release", "--watch", "test"))

    assertEquals(Profile.Release, parsed.profile)
    assertFalse(parsed.useDaemon)
    assertTrue(parsed.watch)
    assertEquals(listOf("test"), parsed.filteredArgs)
  }

  @Test
  fun releaseAfterDoubleDashIsTreatedAsPassthrough() {
    val parsed = parseKoltArgs(listOf("run", "--", "--release"))

    assertEquals(Profile.Debug, parsed.profile)
    assertEquals(listOf("run", "--", "--release"), parsed.filteredArgs)
  }

  @Test
  fun releaseFlagPositionDoesNotMatter() {
    val before = parseKoltArgs(listOf("--release", "build"))
    val after = parseKoltArgs(listOf("build", "--release"))

    assertEquals(before.profile, after.profile)
    assertEquals(before.filteredArgs, after.filteredArgs)
  }

  @Test
  fun absentDflagYieldsEmptyCliSysProps() {
    val parsed = parseKoltArgs(listOf("test"))

    assertEquals(emptyList<Pair<String, String>>(), parsed.cliSysProps)
  }

  @Test
  fun singleDflagIsExtractedAndStrippedFromFilteredArgs() {
    val parsed = parseKoltArgs(listOf("test", "-Dfoo=bar"))

    assertEquals(listOf("foo" to "bar"), parsed.cliSysProps)
    assertEquals(listOf("test"), parsed.filteredArgs)
  }

  @Test
  fun multipleDflagsPreserveCommandLineOrder() {
    val parsed = parseKoltArgs(listOf("test", "-Dfirst=1", "-Dsecond=2", "-Dthird=3"))

    assertEquals(listOf("first" to "1", "second" to "2", "third" to "3"), parsed.cliSysProps)
    assertEquals(listOf("test"), parsed.filteredArgs)
  }

  @Test
  fun dflagWithoutEqualsHasEmptyValue() {
    val parsed = parseKoltArgs(listOf("test", "-Dfoo"))

    assertEquals(listOf("foo" to ""), parsed.cliSysProps)
  }

  @Test
  fun dflagValueSplitsOnFirstEqualsOnly() {
    val parsed = parseKoltArgs(listOf("test", "-Dfoo=a=b=c"))

    assertEquals(listOf("foo" to "a=b=c"), parsed.cliSysProps)
  }

  @Test
  fun dflagWithEmptyValueAfterEqualsIsAllowed() {
    val parsed = parseKoltArgs(listOf("test", "-Dfoo="))

    assertEquals(listOf("foo" to ""), parsed.cliSysProps)
  }

  @Test
  fun bareDashDStaysInFilteredArgs() {
    val parsed = parseKoltArgs(listOf("test", "-D"))

    assertEquals(emptyList<Pair<String, String>>(), parsed.cliSysProps)
    assertTrue(
      parsed.filteredArgs.contains("-D"),
      "bare -D is not a valid sysprop flag and must reach the dispatcher: ${parsed.filteredArgs}",
    )
  }

  @Test
  fun dflagWithEmptyKeyStaysInFilteredArgs() {
    val parsed = parseKoltArgs(listOf("test", "-D=value"))

    assertEquals(emptyList<Pair<String, String>>(), parsed.cliSysProps)
    assertTrue(
      parsed.filteredArgs.contains("-D=value"),
      "-D=<value> with empty key is not a valid sysprop flag: ${parsed.filteredArgs}",
    )
  }

  @Test
  fun dflagAfterDoubleDashIsTreatedAsPassthrough() {
    val parsed = parseKoltArgs(listOf("run", "--", "-Dfoo=bar"))

    assertEquals(emptyList<Pair<String, String>>(), parsed.cliSysProps)
    assertEquals(listOf("run", "--", "-Dfoo=bar"), parsed.filteredArgs)
  }

  @Test
  fun dflagPositionDoesNotMatter() {
    val before = parseKoltArgs(listOf("-Dfoo=bar", "test"))
    val after = parseKoltArgs(listOf("test", "-Dfoo=bar"))

    assertEquals(before.cliSysProps, after.cliSysProps)
    assertEquals(before.filteredArgs, after.filteredArgs)
  }

  @Test
  fun dflagCombinesWithOtherKoltLevelFlags() {
    val parsed = parseKoltArgs(listOf("--no-daemon", "-Dfoo=bar", "--release", "test", "-Dbaz=qux"))

    assertEquals(Profile.Release, parsed.profile)
    assertFalse(parsed.useDaemon)
    assertEquals(listOf("foo" to "bar", "baz" to "qux"), parsed.cliSysProps)
    assertEquals(listOf("test"), parsed.filteredArgs)
  }
}
