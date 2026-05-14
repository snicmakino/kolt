package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getError
import kolt.config.Repository
import kolt.config.RepositoryAuth
import kolt.infra.DownloadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// 401/403 hard-error branch (Req 6.1, 6.2, 6.4): a 401 or 403 from any
// repository must short-circuit iteration with AuthFailed; the remaining
// repos (including a hypothetical 200 successor) must never be contacted.
// Uses lambda-mock download to match the rest of the downloadFromRepositories
// unit suite in TransitiveResolverTest; the full loopback-server hint-matrix
// lives in task 5.2 (RepositoryAuthFailureTest).
class DownloadFromRepositoriesAuthTest {

  @Test
  fun returnsAuthFailedAndStopsIterationOn401() {
    val private401 = Repository(name = "private", url = "https://private.example.com")
    val central200 = Repository(name = "central", url = "https://central.example.com")
    val touched = mutableListOf<String>()

    val result =
      downloadFromRepositories(
        repos = listOf(private401, central200),
        destPath = "/cache/lib.jar",
        urlBuilder = { repo -> "${repo.url}/foo.jar" },
        download = { url, _, _ ->
          touched.add(url)
          if (url.startsWith(private401.url)) Err(DownloadError.HttpFailed(url, 401)) else Ok(Unit)
        },
      )

    val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
    assertEquals("private", failure.repositoryName)
    assertEquals(401, failure.statusCode)
    assertEquals(AuthStateProjection.NotConfigured, failure.authState)
    assertEquals("https://private.example.com/foo.jar", failure.url)
    assertEquals(
      listOf("https://private.example.com/foo.jar"),
      touched,
      "iteration must stop at 401; central must not be contacted",
    )
  }

  @Test
  fun returnsAuthFailedAndStopsIterationOn403() {
    val private403 = Repository(name = "private", url = "https://private.example.com")
    val central200 = Repository(name = "central", url = "https://central.example.com")
    val touched = mutableListOf<String>()

    val result =
      downloadFromRepositories(
        repos = listOf(private403, central200),
        destPath = "/cache/lib.jar",
        urlBuilder = { repo -> "${repo.url}/foo.jar" },
        download = { url, _, _ ->
          touched.add(url)
          if (url.startsWith(private403.url)) Err(DownloadError.HttpFailed(url, 403)) else Ok(Unit)
        },
      )

    val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
    assertEquals("private", failure.repositoryName)
    assertEquals(403, failure.statusCode)
    assertEquals(1, touched.size)
  }

  @Test
  fun authFailedCarriesConfiguredTokenStateWhenBearerSet() {
    val private401 =
      Repository(
        name = "private",
        url = "https://private.example.com",
        auth = RepositoryAuth.Bearer("tok"),
      )

    val result =
      downloadFromRepositories(
        repos = listOf(private401),
        destPath = "/cache/lib.jar",
        urlBuilder = { repo -> "${repo.url}/foo.jar" },
        download = { url, _, _ -> Err(DownloadError.HttpFailed(url, 401)) },
      )

    val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
    assertEquals(AuthStateProjection.ConfiguredToken, failure.authState)
  }

  @Test
  fun authHeadersFromRepoAreForwardedToDownloadLambda() {
    val privateRepo =
      Repository(
        name = "private",
        url = "https://private.example.com",
        auth = RepositoryAuth.Bearer("tok"),
      )
    val seen = mutableListOf<Map<String, String>?>()

    downloadFromRepositories(
      repos = listOf(privateRepo),
      destPath = "/cache/lib.jar",
      urlBuilder = { repo -> "${repo.url}/foo.jar" },
      download = { _, _, headers ->
        seen.add(headers)
        Ok(Unit)
      },
    )

    assertEquals(1, seen.size)
    assertEquals(mapOf("Authorization" to "Bearer tok"), seen.single())
  }

  @Test
  fun authFailedUrlIsRedactedWhenUpstreamUrlCarriedUserinfo() {
    val privateRepo = Repository(name = "private", url = "https://private.example.com")

    val result =
      downloadFromRepositories(
        repos = listOf(privateRepo),
        destPath = "/cache/lib.jar",
        urlBuilder = { repo -> "${repo.url}/foo.jar" },
        download = { _, _, _ ->
          // Simulate a defense-in-depth case where the download error reports
          // a URL with userinfo embedded. Even though Downloader.downloadFile
          // already redacts at the source, the resolver re-applies the
          // redaction so an internal regression cannot leak credentials.
          Err(DownloadError.HttpFailed("https://u:p@private.example.com/foo.jar", 401))
        },
      )

    val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
    assertEquals("https://private.example.com/foo.jar", failure.url)
  }
}
