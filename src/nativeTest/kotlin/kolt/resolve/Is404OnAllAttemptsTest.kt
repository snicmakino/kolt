package kolt.resolve

import kolt.infra.DownloadError
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Is404OnAllAttemptsTest {

  @Test
  fun allAttemptsAre404ReturnsTrue() {
    val error =
      ResolveError.DownloadFailed(
        groupArtifact = "com.example:lib",
        failure =
          RepositoryDownloadFailure.AllAttemptsFailed(
            attempts =
              listOf(
                RepositoryAttempt(
                  repositoryName = "a",
                  url = "https://a.example/lib.module",
                  error = DownloadError.HttpFailed("https://a.example/lib.module", 404),
                ),
                RepositoryAttempt(
                  repositoryName = "b",
                  url = "https://b.example/lib.module",
                  error = DownloadError.HttpFailed("https://b.example/lib.module", 404),
                ),
              )
          ),
      )

    assertTrue(is404OnAllAttempts(error))
  }

  @Test
  fun nonNotFoundHttpStatusMixedReturnsFalse() {
    val error =
      ResolveError.DownloadFailed(
        groupArtifact = "com.example:lib",
        failure =
          RepositoryDownloadFailure.AllAttemptsFailed(
            attempts =
              listOf(
                RepositoryAttempt(
                  repositoryName = "a",
                  url = "https://a.example/lib.module",
                  error = DownloadError.HttpFailed("https://a.example/lib.module", 404),
                ),
                RepositoryAttempt(
                  repositoryName = "b",
                  url = "https://b.example/lib.module",
                  error = DownloadError.HttpFailed("https://b.example/lib.module", 503),
                ),
              )
          ),
      )

    assertFalse(is404OnAllAttempts(error))
  }

  @Test
  fun networkErrorOrWriteFailedMixedReturnsFalse() {
    val error =
      ResolveError.DownloadFailed(
        groupArtifact = "com.example:lib",
        failure =
          RepositoryDownloadFailure.AllAttemptsFailed(
            attempts =
              listOf(
                RepositoryAttempt(
                  repositoryName = "a",
                  url = "https://a.example/lib.module",
                  error = DownloadError.HttpFailed("https://a.example/lib.module", 404),
                ),
                RepositoryAttempt(
                  repositoryName = "b",
                  url = "https://b.example/lib.module",
                  error = DownloadError.NetworkError("https://b.example/lib.module", "timeout"),
                ),
                RepositoryAttempt(
                  repositoryName = "c",
                  url = "https://c.example/lib.module",
                  error = DownloadError.WriteFailed("/tmp/lib.module"),
                ),
              )
          ),
      )

    assertFalse(is404OnAllAttempts(error))
  }

  @Test
  fun noRepositoriesConfiguredReturnsFalse() {
    val error =
      ResolveError.DownloadFailed(
        groupArtifact = "com.example:lib",
        failure = RepositoryDownloadFailure.NoRepositoriesConfigured,
      )

    assertFalse(is404OnAllAttempts(error))
  }

  @Test
  fun emptyAttemptsReturnsFalse() {
    val error =
      ResolveError.DownloadFailed(
        groupArtifact = "com.example:lib",
        failure = RepositoryDownloadFailure.AllAttemptsFailed(attempts = emptyList()),
      )

    assertFalse(is404OnAllAttempts(error))
  }

  @Test
  fun nonDownloadFailedResolveErrorReturnsFalse() {
    val error = ResolveError.NoNativeVariant("com.example:lib", "linuxX64")

    assertFalse(is404OnAllAttempts(error))
  }
}
