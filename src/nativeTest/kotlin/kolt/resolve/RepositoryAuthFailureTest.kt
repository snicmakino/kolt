package kolt.resolve

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.Repository
import kolt.config.RepositoryAuth
import kolt.infra.downloadFile
import kolt.infra.testfixture.LoopbackHttpServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove

// End-to-end loopback-HTTP coverage for Req 6 (401/403 hard error + iteration
// stop) and Req 7 (per-state hint matrix). Complements
// `DownloadFromRepositoriesAuthTest` (task 4.1) which uses a lambda-mock
// download: this test drives the real `downloadFile` against pthread-backed
// loopback servers so the iteration-stop guarantee is observed at the socket
// layer (a server's access log staying empty proves no request was sent), and
// the hint matrix is pinned through the real `formatResolveError` renderer.
@OptIn(ExperimentalForeignApi::class)
class RepositoryAuthFailureTest {

  // Iteration stop (Req 6.1, 6.2, 6.4): a 401 from the first repo must short-
  // circuit; the second (200-ready) repo must never see a connection. The
  // access-log timeout sentinel ("<timeout waiting for request>") is what
  // proves no request arrived — the worker thread's `completed` flag only
  // flips after serving exactly one request.
  @Test
  fun stopsIterationOn401AndDoesNotContactSubsequentRepo() {
    val destPath = "/tmp/kolt_authfail_stop401_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOrFail(LoopbackHttpServer.Response.withStatus(401, "Unauthorized", ""))
      .use { repo401 ->
        LoopbackHttpServer.startOrFail(LoopbackHttpServer.Response.ok("payload")).use { repo200 ->
          try {
            val repos =
              listOf(
                Repository(name = "private", url = "http://127.0.0.1:${repo401.port}"),
                Repository(name = "central", url = "http://127.0.0.1:${repo200.port}"),
              )

            val result =
              downloadFromRepositories(
                repos = repos,
                destPath = destPath,
                urlBuilder = { repo -> "${repo.url}/foo.jar" },
                download = { url, dst, headers -> downloadFile(url, dst, headers) },
              )

            val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
            assertEquals("private", failure.repositoryName)
            assertEquals(401, failure.statusCode)
            assertEquals(AuthStateProjection.NotConfigured, failure.authState)

            // The 200-repo's worker is parked in accept(); awaitAccessLog will
            // hit its timeout sentinel because no client ever connected.
            val centralLog = repo200.awaitAccessLog(timeoutMillis = 500)
            assertTrue(
              centralLog.rawHeaderBlock.startsWith("<timeout"),
              "central must not receive a request when private returns 401; got:\n${centralLog.rawHeaderBlock}",
            )
          } finally {
            remove(destPath)
          }
        }
      }
  }

  // 404 → 401 fall-through then stop (Req 6.3 + 6.1): the loop must fall
  // through 404 (declaration-order semantics, unchanged) and stop at the
  // 401 from the middle repo. The third (200-ready) repo must not be
  // contacted.
  @Test
  fun fallsThrough404ThenStopsAt401() {
    val destPath = "/tmp/kolt_authfail_404then401_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOrFail(LoopbackHttpServer.Response.withStatus(404, "Not Found", ""))
      .use { repo404 ->
        LoopbackHttpServer.startOrFail(
            LoopbackHttpServer.Response.withStatus(401, "Unauthorized", "")
          )
          .use { repo401 ->
            LoopbackHttpServer.startOrFail(LoopbackHttpServer.Response.ok("payload")).use { repo200
              ->
              try {
                val repos =
                  listOf(
                    Repository(name = "mirror", url = "http://127.0.0.1:${repo404.port}"),
                    Repository(name = "private", url = "http://127.0.0.1:${repo401.port}"),
                    Repository(name = "central", url = "http://127.0.0.1:${repo200.port}"),
                  )

                val result =
                  downloadFromRepositories(
                    repos = repos,
                    destPath = destPath,
                    urlBuilder = { repo -> "${repo.url}/foo.jar" },
                    download = { url, dst, headers -> downloadFile(url, dst, headers) },
                  )

                val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
                assertEquals("private", failure.repositoryName)
                assertEquals(401, failure.statusCode)
                assertEquals(AuthStateProjection.NotConfigured, failure.authState)

                val centralLog = repo200.awaitAccessLog(timeoutMillis = 500)
                assertTrue(
                  centralLog.rawHeaderBlock.startsWith("<timeout"),
                  "central must not receive a request after private returns 401; got:\n${centralLog.rawHeaderBlock}",
                )
              } finally {
                remove(destPath)
              }
            }
          }
      }
  }

  // Hint matrix row 1 of 4 (Req 7.4): anonymous repo + 401 →
  // "the repository requires authentication; add credentials to kolt.local.toml".
  // Drives the real `downloadFromRepositories` so the `AuthStateProjection`
  // is computed from the live `Repository.auth = null`, then renders through
  // the real `formatResolveError` so the hint text is pinned end-to-end.
  @Test
  fun hintRow401NotConfigured() {
    val context = runAuthFailureAndRender(status = 401, auth = null, repoName = "private")
    assertEquals(
      "hint: the repository requires authentication; add credentials to kolt.local.toml",
      context.last(),
    )
  }

  // Hint matrix row 2 of 4: token-configured repo + 401 →
  // "the credentials may be invalid or expired".
  @Test
  fun hintRow401ConfiguredToken() {
    val context =
      runAuthFailureAndRender(
        status = 401,
        auth = RepositoryAuth.Bearer("tok"),
        repoName = "private",
      )
    assertEquals("hint: the credentials may be invalid or expired", context.last())
  }

  // Hint matrix row 2 of 4 (second `Configured` flavor): basic-configured
  // repo + 401 → same wording per Req 7.4 (the table folds token and basic
  // into a single hint row for each status).
  @Test
  fun hintRow401ConfiguredBasic() {
    val context =
      runAuthFailureAndRender(
        status = 401,
        auth = RepositoryAuth.Basic("alice", "s3cret"),
        repoName = "private",
      )
    assertEquals("hint: the credentials may be invalid or expired", context.last())
  }

  // Hint matrix row 3 of 4: anonymous repo + 403 → "authentication is required".
  @Test
  fun hintRow403NotConfigured() {
    val context = runAuthFailureAndRender(status = 403, auth = null, repoName = "private")
    assertEquals("hint: authentication is required", context.last())
  }

  // Hint matrix row 4 of 4: token-configured repo + 403 →
  // "the credentials are valid but lack permission for this repository".
  @Test
  fun hintRow403ConfiguredToken() {
    val context =
      runAuthFailureAndRender(
        status = 403,
        auth = RepositoryAuth.Bearer("tok"),
        repoName = "private",
      )
    assertEquals(
      "hint: the credentials are valid but lack permission for this repository",
      context.last(),
    )
  }

  // Shared driver for the hint-matrix rows. Builds a single-repo loopback
  // server returning the requested status, calls `downloadFromRepositories`
  // with the real `downloadFile`, wraps the resulting `AuthFailed` into
  // `ResolveError.DownloadFailed`, and returns the rendered `context` lines
  // so each row can assert the trailing `hint:` line.
  private fun runAuthFailureAndRender(
    status: Int,
    auth: RepositoryAuth?,
    repoName: String,
  ): List<String> {
    val destPath = "/tmp/kolt_authfail_hint_${status}_${getpid()}.bin"
    remove(destPath)
    val reason = if (status == 401) "Unauthorized" else "Forbidden"
    return LoopbackHttpServer.startOrFail(
        LoopbackHttpServer.Response.withStatus(status, reason, "")
      )
      .use { server ->
        try {
          val repo =
            Repository(name = repoName, url = "http://127.0.0.1:${server.port}", auth = auth)

          val result =
            downloadFromRepositories(
              repos = listOf(repo),
              destPath = destPath,
              urlBuilder = { r -> "${r.url}/foo.jar" },
              download = { url, dst, headers -> downloadFile(url, dst, headers) },
            )

          val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
          assertEquals(status, failure.statusCode)
          val diagnostic =
            formatResolveError(ResolveError.DownloadFailed("com.example:lib", failure))
          assertEquals("failed to download com.example:lib", diagnostic.headline)
          diagnostic.context
        } finally {
          remove(destPath)
        }
      }
  }
}

private fun LoopbackHttpServer.Companion.startOrFail(
  response: LoopbackHttpServer.Response
): LoopbackHttpServer = start(response).getOrElse { fail("LoopbackHttpServer.start failed: $it") }
