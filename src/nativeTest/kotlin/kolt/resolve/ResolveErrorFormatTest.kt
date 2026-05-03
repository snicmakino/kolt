package kolt.resolve

import kolt.infra.DownloadError
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveErrorFormatTest {

  @Test
  fun invalidDependencyFormat() {
    val error = ResolveError.InvalidDependency("bad:dep")
    assertEquals("error: invalid dependency 'bad:dep'", formatResolveError(error))
  }

  @Test
  fun sha256MismatchIncludesExpectedAndActual() {
    val error =
      ResolveError.Sha256Mismatch(
        groupArtifact = "com.example:lib",
        expected = "abc123",
        actual = "def456",
      )
    val result = formatResolveError(error)
    assertTrue(result.contains("sha256 mismatch for com.example:lib"))
    assertTrue(result.contains("expected: abc123"))
    assertTrue(result.contains("got:      def456"))
  }

  @Test
  fun downloadFailedSingleHttpAttemptFormat() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.AllAttemptsFailed(
          listOf(
            RepositoryAttempt(
              "https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar",
              DownloadError.HttpFailed(
                "https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar",
                404,
              ),
            )
          )
        ),
      )
    assertEquals(
      "error: failed to download com.example:lib\n" +
        "  https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar -> 404",
      formatResolveError(error),
    )
  }

  @Test
  fun downloadFailedMultiRepoDumpsEachAttempt() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.AllAttemptsFailed(
          listOf(
            RepositoryAttempt(
              "https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar",
              DownloadError.HttpFailed("u1", 404),
            ),
            RepositoryAttempt(
              "https://jitpack.io/com/example/lib/1.0.0/lib-1.0.0.jar",
              DownloadError.HttpFailed("u2", 404),
            ),
          )
        ),
      )
    val rendered = formatResolveError(error)
    assertTrue(rendered.contains("error: failed to download com.example:lib"))
    assertTrue(
      rendered.contains(
        "https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar -> 404"
      )
    )
    assertTrue(rendered.contains("https://jitpack.io/com/example/lib/1.0.0/lib-1.0.0.jar -> 404"))
  }

  @Test
  fun downloadFailedNetworkErrorRendersMessage() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.AllAttemptsFailed(
          listOf(
            RepositoryAttempt(
              "https://example.com/lib-1.0.0.jar",
              DownloadError.NetworkError(
                "https://example.com/lib-1.0.0.jar",
                "Could not resolve host",
              ),
            )
          )
        ),
      )
    assertEquals(
      "error: failed to download com.example:lib\n" +
        "  https://example.com/lib-1.0.0.jar -> Could not resolve host",
      formatResolveError(error),
    )
  }

  @Test
  fun downloadFailedNoRepositoriesConfiguredRendersConfigHint() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.NoRepositoriesConfigured,
      )
    assertEquals(
      "error: failed to download com.example:lib\n" +
        "  no repositories configured (add a `[repositories]` entry to kolt.toml)",
      formatResolveError(error),
    )
  }

  // Regression guard: the URL on a `WriteFailed` reached the server fine —
  // the local tempfile write is what failed. The wording must not read as
  // network blame.
  @Test
  fun writeFailedAttemptDoesNotMisattributeBlameToTheUrl() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.AllAttemptsFailed(
          listOf(
            RepositoryAttempt(
              "https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar",
              DownloadError.WriteFailed("/cache/lib-1.0.0.jar.tmp.42"),
            )
          )
        ),
      )
    val rendered = formatResolveError(error)
    assertTrue(rendered.contains("local write failed (/cache/lib-1.0.0.jar.tmp.42)"))
    assertFalse(rendered.contains("write failed: "), "old framing must not regress")
  }

  @Test
  fun metadataDownloadFailedRendersDistinctFirstLineButReusesPerRepoDump() {
    val error =
      ResolveError.MetadataDownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.AllAttemptsFailed(
          listOf(
            RepositoryAttempt(
              "https://repo1.maven.org/maven2/com/example/lib/maven-metadata.xml",
              DownloadError.HttpFailed("u", 404),
            )
          )
        ),
      )
    assertEquals(
      "error: could not fetch metadata for com.example:lib\n" +
        "  https://repo1.maven.org/maven2/com/example/lib/maven-metadata.xml -> 404",
      formatResolveError(error),
    )
  }

  @Test
  fun metadataDownloadFailedNoReposConfiguredAlsoRendersConfigHint() {
    val error =
      ResolveError.MetadataDownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.NoRepositoriesConfigured,
      )
    assertEquals(
      "error: could not fetch metadata for com.example:lib\n" +
        "  no repositories configured (add a `[repositories]` entry to kolt.toml)",
      formatResolveError(error),
    )
  }

  @Test
  fun hashComputeFailedFormat() {
    val error = ResolveError.HashComputeFailed("com.example:lib", Sha256Error("/path/to/file"))
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
      formatResolveError(error),
    )
  }

  @Test
  fun metadataParseFailedFormat() {
    val error = ResolveError.MetadataParseFailed("com.example:lib")
    assertEquals(
      "error: failed to parse Gradle module metadata for com.example:lib",
      formatResolveError(error),
    )
  }

  @Test
  fun metadataFetchFailedFormat() {
    val error = ResolveError.MetadataFetchFailed("com.example:lib")
    assertEquals(
      "error: failed to read Gradle module metadata for com.example:lib",
      formatResolveError(error),
    )
  }
}
