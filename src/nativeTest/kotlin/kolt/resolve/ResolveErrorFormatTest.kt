package kolt.resolve

import kolt.infra.DownloadError
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveErrorFormatTest {

    @Test
    fun invalidDependencyFormat() {
        val error = ResolveError.InvalidDependency("bad:dep")
        assertEquals("error: invalid dependency 'bad:dep'", formatResolveError(error))
    }

    @Test
    fun sha256MismatchIncludesExpectedAndActual() {
        val error = ResolveError.Sha256Mismatch(
            groupArtifact = "com.example:lib",
            expected = "abc123",
            actual = "def456"
        )
        val result = formatResolveError(error)
        assertTrue(result.contains("sha256 mismatch for com.example:lib"))
        assertTrue(result.contains("expected: abc123"))
        assertTrue(result.contains("got:      def456"))
    }

    @Test
    fun downloadFailedFormat() {
        val error = ResolveError.DownloadFailed(
            "com.example:lib",
            DownloadError.NetworkError("https://example.com", "timeout")
        )
        assertEquals("error: failed to download com.example:lib", formatResolveError(error))
    }

    @Test
    fun hashComputeFailedFormat() {
        val error = ResolveError.HashComputeFailed(
            "com.example:lib",
            Sha256Error("/path/to/file")
        )
        assertEquals("error: failed to compute hash for com.example:lib", formatResolveError(error))
    }

    @Test
    fun directoryCreateFailedFormat() {
        val error = ResolveError.DirectoryCreateFailed("/cache/dir")
        assertEquals("error: could not create directory /cache/dir", formatResolveError(error))
    }

    @Test
    fun noNativeVariantFormat() {
        val error = ResolveError.NoNativeVariant("com.example:lib", "linux_x64")
        assertEquals(
            "error: com.example:lib has no Kotlin/Native variant for target 'linux_x64'",
            formatResolveError(error)
        )
    }

    @Test
    fun metadataParseFailedFormat() {
        val error = ResolveError.MetadataParseFailed("com.example:lib")
        assertEquals(
            "error: failed to parse Gradle module metadata for com.example:lib",
            formatResolveError(error)
        )
    }

    @Test
    fun metadataFetchFailedFormat() {
        val error = ResolveError.MetadataFetchFailed("com.example:lib")
        assertEquals(
            "error: failed to read Gradle module metadata for com.example:lib",
            formatResolveError(error)
        )
    }
}
