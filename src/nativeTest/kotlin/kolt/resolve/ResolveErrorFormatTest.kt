package kolt.resolve

import kolt.infra.DownloadError
import kolt.infra.Sha256Error
import kolt.infra.output.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolveErrorFormatTest {

  @Test
  fun invalidDependencyFormat() {
    val error = ResolveError.InvalidDependency("bad:dep")
    val diag = formatResolveError(error)
    assertEquals(Severity.Error, diag.severity)
    assertEquals("invalid dependency 'bad:dep'", diag.headline)
    assertEquals(emptyList(), diag.context)
    assertNull(diag.hint)
  }

  @Test
  fun sha256MismatchIncludesExpectedAndActual() {
    val error =
      ResolveError.Sha256Mismatch(
        groupArtifact = "com.example:lib",
        expected = "abc123",
        actual = "def456",
      )
    val diag = formatResolveError(error)
    assertEquals("sha256 mismatch for com.example:lib", diag.headline)
    assertEquals(listOf("expected: abc123", "got:      def456"), diag.context)
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
    val diag = formatResolveError(error)
    assertEquals("failed to download com.example:lib", diag.headline)
    assertEquals(
      listOf("https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar -> 404"),
      diag.context,
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
    val diag = formatResolveError(error)
    assertEquals("failed to download com.example:lib", diag.headline)
    assertEquals(2, diag.context.size)
    assertTrue(
      diag.context.any {
        it.contains("https://repo1.maven.org/maven2/com/example/lib/1.0.0/lib-1.0.0.jar -> 404")
      }
    )
    assertTrue(
      diag.context.any {
        it.contains("https://jitpack.io/com/example/lib/1.0.0/lib-1.0.0.jar -> 404")
      }
    )
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
    val diag = formatResolveError(error)
    assertEquals("failed to download com.example:lib", diag.headline)
    assertEquals(
      listOf("https://example.com/lib-1.0.0.jar -> Could not resolve host"),
      diag.context,
    )
  }

  @Test
  fun downloadFailedNoRepositoriesConfiguredRendersConfigHint() {
    val error =
      ResolveError.DownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.NoRepositoriesConfigured,
      )
    val diag = formatResolveError(error)
    assertEquals("failed to download com.example:lib", diag.headline)
    assertEquals(
      listOf("no repositories configured (add a `[repositories]` entry to kolt.toml)"),
      diag.context,
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
    val diag = formatResolveError(error)
    assertTrue(diag.context.any { it.contains("local write failed (/cache/lib-1.0.0.jar.tmp.42)") })
    assertTrue(diag.context.none { it.contains("write failed: ") }, "old framing must not regress")
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
    val diag = formatResolveError(error)
    assertEquals("could not fetch metadata for com.example:lib", diag.headline)
    assertEquals(
      listOf("https://repo1.maven.org/maven2/com/example/lib/maven-metadata.xml -> 404"),
      diag.context,
    )
  }

  @Test
  fun metadataDownloadFailedNoReposConfiguredAlsoRendersConfigHint() {
    val error =
      ResolveError.MetadataDownloadFailed(
        "com.example:lib",
        RepositoryDownloadFailure.NoRepositoriesConfigured,
      )
    val diag = formatResolveError(error)
    assertEquals("could not fetch metadata for com.example:lib", diag.headline)
    assertEquals(
      listOf("no repositories configured (add a `[repositories]` entry to kolt.toml)"),
      diag.context,
    )
  }

  @Test
  fun hashComputeFailedFormat() {
    val error = ResolveError.HashComputeFailed("com.example:lib", Sha256Error("/path/to/file"))
    val diag = formatResolveError(error)
    assertEquals("failed to compute hash for com.example:lib", diag.headline)
    assertEquals(emptyList(), diag.context)
  }

  @Test
  fun directoryCreateFailedFormat() {
    val error = ResolveError.DirectoryCreateFailed("/cache/dir")
    val diag = formatResolveError(error)
    assertEquals("could not create directory /cache/dir", diag.headline)
  }

  @Test
  fun noNativeVariantFormat() {
    val error = ResolveError.NoNativeVariant("com.example:lib", "linux_x64")
    val diag = formatResolveError(error)
    assertEquals(
      "com.example:lib has no Kotlin/Native variant for target 'linux_x64'",
      diag.headline,
    )
  }

  @Test
  fun metadataParseFailedFormat() {
    val error = ResolveError.MetadataParseFailed("com.example:lib")
    val diag = formatResolveError(error)
    assertEquals("failed to parse Gradle module metadata for com.example:lib", diag.headline)
  }

  @Test
  fun metadataFetchFailedFormat() {
    val error = ResolveError.MetadataFetchFailed("com.example:lib")
    val diag = formatResolveError(error)
    assertEquals("failed to read Gradle module metadata for com.example:lib", diag.headline)
  }

  @Test
  fun strictVersionConflictBothStrict() {
    val error =
      ResolveError.StrictVersionConflict(
        groupArtifact = "com.example:lib",
        strictVersion = "1.0",
        otherVersion = "2.0",
        otherIsStrict = true,
      )
    val diag = formatResolveError(error)
    assertEquals("conflicting strict versions on com.example:lib: 1.0 and 2.0", diag.headline)
  }

  @Test
  fun strictVersionConflictMixed() {
    val error =
      ResolveError.StrictVersionConflict(
        groupArtifact = "com.example:lib",
        strictVersion = "1.0",
        otherVersion = "2.0",
        otherIsStrict = false,
      )
    val diag = formatResolveError(error)
    assertEquals(
      "strict version conflict on com.example:lib: " +
        "1.0 required strictly, but 2.0 also requested",
      diag.headline,
    )
  }

  @Test
  fun rejectedVersionResolvedFormat() {
    val error = ResolveError.RejectedVersionResolved("com.example:lib", "1.0", "<2.0")
    val diag = formatResolveError(error)
    assertEquals("resolved com.example:lib:1.0 is rejected by constraint '<2.0'", diag.headline)
  }

  @Test
  fun severityIsAlwaysError() {
    val variants =
      listOf(
        ResolveError.InvalidDependency("a:b"),
        ResolveError.Sha256Mismatch("a:b", "x", "y"),
        ResolveError.HashComputeFailed("a:b", Sha256Error("/p")),
        ResolveError.DirectoryCreateFailed("/p"),
        ResolveError.MetadataParseFailed("a:b"),
        ResolveError.MetadataFetchFailed("a:b"),
      )
    for (v in variants) {
      assertEquals(Severity.Error, formatResolveError(v).severity, v::class.simpleName)
    }
  }
}
