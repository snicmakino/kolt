package kolt.resolve

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.MAVEN_CENTRAL_BASE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DependencyTest {

    @Test
    fun parseValidCoordinate() {
        val result = parseCoordinate("org.jetbrains.kotlinx:kotlinx-coroutines-core", "1.9.0")
        val coord = assertNotNull(result.get())
        assertEquals("org.jetbrains.kotlinx", coord.group)
        assertEquals("kotlinx-coroutines-core", coord.artifact)
        assertEquals("1.9.0", coord.version)
    }

    @Test
    fun parseCoordinateWithSimpleGroup() {
        val result = parseCoordinate("com.squareup:okhttp", "4.12.0")
        val coord = assertNotNull(result.get())
        assertEquals("com.squareup", coord.group)
        assertEquals("okhttp", coord.artifact)
        assertEquals("4.12.0", coord.version)
    }

    @Test
    fun parseCoordinateMissingColonReturnsErr() {
        val result = parseCoordinate("invalid-no-colon", "1.0.0")
        assertIs<InvalidCoordinate>(result.getError())
    }

    @Test
    fun parseCoordinateMultipleColonsReturnsErr() {
        val result = parseCoordinate("group:artifact:extra", "1.0.0")
        assertIs<InvalidCoordinate>(result.getError())
    }

    @Test
    fun parseCoordinateEmptyGroupReturnsErr() {
        val result = parseCoordinate(":artifact", "1.0.0")
        assertIs<InvalidCoordinate>(result.getError())
    }

    @Test
    fun parseCoordinateEmptyArtifactReturnsErr() {
        val result = parseCoordinate("group:", "1.0.0")
        assertIs<InvalidCoordinate>(result.getError())
    }

    @Test
    fun buildDownloadUrlProducesCorrectMavenCentralUrl() {
        val coord = Coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0")
        val url = buildDownloadUrl(coord, MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.jar",
            url
        )
    }

    @Test
    fun buildDownloadUrlWithSingleSegmentGroup() {
        val coord = Coordinate("junit", "junit", "4.13.2")
        val url = buildDownloadUrl(coord, MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar",
            url
        )
    }

    @Test
    fun buildDownloadUrlWithCustomBaseUrl() {
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val url = buildDownloadUrl(coord, "https://nexus.example.com/repository/maven-public")
        assertEquals(
            "https://nexus.example.com/repository/maven-public/com/example/lib/1.0.0/lib-1.0.0.jar",
            url
        )
    }

    @Test
    fun buildCachePathProducesRelativePath() {
        val coord = Coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0")
        val path = buildCachePath(coord)
        assertEquals(
            "org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.jar",
            path
        )
    }

    @Test
    fun buildClasspathJoinsWithColon() {
        val paths = listOf("/home/user/.kolt/cache/a.jar", "/home/user/.kolt/cache/b.jar")
        assertEquals("/home/user/.kolt/cache/a.jar:/home/user/.kolt/cache/b.jar", buildClasspath(paths))
    }

    @Test
    fun buildClasspathSingleEntry() {
        val paths = listOf("/home/user/.kolt/cache/a.jar")
        assertEquals("/home/user/.kolt/cache/a.jar", buildClasspath(paths))
    }

    @Test
    fun buildClasspathEmptyReturnsEmpty() {
        assertEquals("", buildClasspath(emptyList()))
    }

    @Test
    fun buildPomDownloadUrlProducesCorrectMavenCentralUrl() {
        val coord = Coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0")
        val url = buildPomDownloadUrl(coord, MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.pom",
            url
        )
    }

    @Test
    fun buildPomDownloadUrlWithSingleSegmentGroup() {
        val coord = Coordinate("junit", "junit", "4.13.2")
        val url = buildPomDownloadUrl(coord, MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom",
            url
        )
    }

    @Test
    fun buildPomDownloadUrlWithCustomBaseUrl() {
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val url = buildPomDownloadUrl(coord, "https://nexus.example.com/repository/maven-public")
        assertEquals(
            "https://nexus.example.com/repository/maven-public/com/example/lib/1.0.0/lib-1.0.0.pom",
            url
        )
    }

    @Test
    fun buildModuleDownloadUrlWithCustomBaseUrl() {
        val coord = Coordinate("com.example", "lib", "1.0.0")
        val url = buildModuleDownloadUrl(coord, "https://nexus.example.com/repository/maven-public")
        assertEquals(
            "https://nexus.example.com/repository/maven-public/com/example/lib/1.0.0/lib-1.0.0.module",
            url
        )
    }

    @Test
    fun buildPomCachePathProducesRelativePath() {
        val coord = Coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0")
        val path = buildPomCachePath(coord)
        assertEquals(
            "org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.pom",
            path
        )
    }

    @Test
    fun buildKlibDownloadUrlProducesCorrectMavenCentralUrl() {
        val coord = Coordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core-linuxx64", "1.9.0")
        val url = buildKlibDownloadUrl(coord, MAVEN_CENTRAL_BASE)
        assertEquals(
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-linuxx64/1.9.0/kotlinx-coroutines-core-linuxx64-1.9.0.klib",
            url
        )
    }

    @Test
    fun buildKlibDownloadUrlWithCustomBaseUrl() {
        val coord = Coordinate("com.example", "lib-linuxx64", "1.0.0")
        val url = buildKlibDownloadUrl(coord, "https://nexus.example.com/repository/maven-public")
        assertEquals(
            "https://nexus.example.com/repository/maven-public/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib",
            url
        )
    }

    @Test
    fun buildKlibCachePathProducesRelativePath() {
        val coord = Coordinate("org.jetbrains.kotlinx", "atomicfu-linuxx64", "0.25.0")
        val path = buildKlibCachePath(coord)
        assertEquals(
            "org/jetbrains/kotlinx/atomicfu-linuxx64/0.25.0/atomicfu-linuxx64-0.25.0.klib",
            path
        )
    }
}
