package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.MAVEN_CENTRAL_BASE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataTest {

  @Test
  fun parseLatestStableFromVersionsList() {
    val xml =
      """
            <metadata>
              <groupId>org.jetbrains.kotlinx</groupId>
              <artifactId>kotlinx-coroutines-core</artifactId>
              <versioning>
                <latest>1.10.2</latest>
                <release>1.10.2</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.10.2</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.10.2", resolution.version)
    assertFalse(resolution.fallbackToPrerelease)
  }

  // Issue #354: kolt-usage docs the no-version path as "latest stable"
  // but the pre-fix code trusted <release>, which Maven Central populates
  // with the latest non-SNAPSHOT version including -rc qualifiers.
  @Test
  fun versionsListFiltersPrereleaseRcQualifier() {
    val xml =
      """
            <metadata>
              <versioning>
                <release>1.11.0-rc02</release>
                <versions>
                  <version>1.10.0</version>
                  <version>1.11.0-rc02</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.10.0", resolution.version)
    assertFalse(resolution.fallbackToPrerelease)
  }

  @Test
  fun versionsListPicksMaxByVersionNotByXmlOrder() {
    val xml =
      """
            <metadata>
              <versioning>
                <versions>
                  <version>0.9.0</version>
                  <version>1.5.0</version>
                  <version>1.0.0</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.5.0", resolution.version)
  }

  @Test
  fun allPreReleaseFallsBackWithFlag() {
    val xml =
      """
            <metadata>
              <versioning>
                <versions>
                  <version>2.0.0-beta01</version>
                  <version>2.0.0-rc01</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("2.0.0-rc01", resolution.version)
    assertTrue(resolution.fallbackToPrerelease)
  }

  @Test
  fun milestoneMQualifierIsFiltered() {
    val xml =
      """
            <metadata>
              <versioning>
                <versions>
                  <version>1.0.0-M5</version>
                  <version>1.0.0</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.0.0", resolution.version)
  }

  @Test
  fun eapQualifierIsFiltered() {
    val xml =
      """
            <metadata>
              <versioning>
                <versions>
                  <version>1.0.0-eap</version>
                  <version>0.9.0</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("0.9.0", resolution.version)
  }

  @Test
  fun parseMetadataWithoutVersionsListUsesLatestTag() {
    val xml =
      """
            <metadata>
              <versioning>
                <latest>2.0.0</latest>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("2.0.0", resolution.version)
    assertFalse(resolution.fallbackToPrerelease)
  }

  @Test
  fun parseMetadataWithNoVersioningReturnsErr() {
    val xml =
      """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
            </metadata>
        """
        .trimIndent()

    val result = parseMetadataXml(xml)
    assertIs<MetadataParseError>(result.getError())
  }

  @Test
  fun parseEmptyXmlReturnsErr() {
    val result = parseMetadataXml("")
    assertIs<MetadataParseError>(result.getError())
  }

  // No <versions> list, only a pre-release in <release>: we honour the
  // legacy fallback to keep working against malformed metadata, but mark
  // the resolution so the caller can warn.
  @Test
  fun releaseFallbackFlagsPrerelease() {
    val xml =
      """
            <metadata>
              <versioning>
                <release>2.0.0-beta.1</release>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("2.0.0-beta.1", resolution.version)
    assertTrue(resolution.fallbackToPrerelease)
  }

  // Guava-style multi-flavor tie: two stable variants share the numeric
  // tuple `33.6.0` and unknown qualifier tokens (`jre` / `android`) both
  // sort as RELEASE under `compareVersions`. The publisher's `<release>`
  // is the authoritative tiebreak so `kolt add com.google.guava:guava`
  // keeps yielding `-jre` (Maven Central's declared default) instead of
  // flipping with XML order.
  @Test
  fun multiFlavorTieDefersToReleaseTag() {
    val xml =
      """
            <metadata>
              <versioning>
                <release>33.6.0-jre</release>
                <versions>
                  <version>33.6.0-android</version>
                  <version>33.6.0-jre</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("33.6.0-jre", resolution.version)
    assertFalse(resolution.fallbackToPrerelease)
  }

  // Snapshot must lose to the stable sibling even though they share the
  // numeric tuple — guards against a regression where the SNAPSHOT
  // accidentally classifies as stable or the comparator ties.
  @Test
  fun snapshotLosesToStableAtSameNumericTuple() {
    val xml =
      """
            <metadata>
              <versioning>
                <versions>
                  <version>0.1.0-SNAPSHOT</version>
                  <version>0.1.0</version>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("0.1.0", resolution.version)
    assertFalse(resolution.fallbackToPrerelease)
  }

  // An empty `<versions></versions>` block (vs. tag absent entirely)
  // must fall through to <release>; the pre-release flag fires because
  // <release> happens to be a candidate-release.
  @Test
  fun emptyVersionsBlockFallsThroughToReleaseTag() {
    val xml =
      """
            <metadata>
              <versioning>
                <release>1.0.0-rc1</release>
                <versions>
                </versions>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.0.0-rc1", resolution.version)
    assertTrue(resolution.fallbackToPrerelease)
  }

  @Test
  fun parseVersionWithWhitespaceInTag() {
    val xml =
      """
            <metadata>
              <versioning>
                <release>
                  1.5.0
                </release>
              </versioning>
            </metadata>
        """
        .trimIndent()

    val resolution = assertNotNull(parseMetadataXml(xml).get())
    assertEquals("1.5.0", resolution.version)
  }

  @Test
  fun buildMetadataUrl() {
    val url =
      buildMetadataDownloadUrl(
        "org.jetbrains.kotlinx",
        "kotlinx-coroutines-core",
        MAVEN_CENTRAL_BASE,
      )
    assertEquals(
      "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml",
      url,
    )
  }

  @Test
  fun buildMetadataUrlWithCustomBaseUrl() {
    val url =
      buildMetadataDownloadUrl(
        "com.example",
        "lib",
        "https://nexus.example.com/repository/maven-public",
      )
    assertEquals(
      "https://nexus.example.com/repository/maven-public/com/example/lib/maven-metadata.xml",
      url,
    )
  }
}
