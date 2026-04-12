package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.MAVEN_CENTRAL_BASE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MetadataTest {

    @Test
    fun parseReleaseVersionFromMetadataXml() {
        val xml = """
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
        """.trimIndent()

        val result = parseMetadataXml(xml)
        assertEquals("1.10.2", assertNotNull(result.get()))
    }

    @Test
    fun parseMetadataWithoutReleaseUsesLatest() {
        val xml = """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
              <versioning>
                <latest>2.0.0</latest>
                <versions>
                  <version>1.0.0</version>
                  <version>2.0.0</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()

        val result = parseMetadataXml(xml)
        assertEquals("2.0.0", assertNotNull(result.get()))
    }

    @Test
    fun parseMetadataWithNoVersioningReturnsErr() {
        val xml = """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
            </metadata>
        """.trimIndent()

        val result = parseMetadataXml(xml)
        assertIs<MetadataParseError>(result.getError())
    }

    @Test
    fun parseEmptyXmlReturnsErr() {
        val result = parseMetadataXml("")
        assertIs<MetadataParseError>(result.getError())
    }

    @Test
    fun parsePreReleaseVersion() {
        val xml = """
            <metadata>
              <versioning>
                <release>2.0.0-beta.1</release>
              </versioning>
            </metadata>
        """.trimIndent()

        val result = parseMetadataXml(xml)
        assertEquals("2.0.0-beta.1", assertNotNull(result.get()))
    }

    @Test
    fun parseVersionWithWhitespaceInTag() {
        val xml = """
            <metadata>
              <versioning>
                <release>
                  1.5.0
                </release>
              </versioning>
            </metadata>
        """.trimIndent()

        val result = parseMetadataXml(xml)
        assertEquals("1.5.0", assertNotNull(result.get()))
    }

    @Test
    fun buildMetadataUrl() {
        val url = buildMetadataDownloadUrl("org.jetbrains.kotlinx", "kotlinx-coroutines-core", MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml",
            url
        )
    }

    @Test
    fun buildMetadataUrlWithCustomBaseUrl() {
        val url = buildMetadataDownloadUrl(
            "com.example",
            "lib",
            "https://nexus.example.com/repository/maven-public"
        )
        assertEquals(
            "https://nexus.example.com/repository/maven-public/com/example/lib/maven-metadata.xml",
            url
        )
    }
}
