package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import kolt.config.RepositoryAuth
import kolt.config.toHeaders
import kolt.infra.testfixture.LoopbackHttpServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove

@OptIn(ExperimentalForeignApi::class)
class DownloaderAuthHeaderTest {

  // Req 5.1: a Bearer-configured repository must produce an
  // "Authorization: Bearer <token>" header on the outbound HTTP request.
  @Test
  fun bearerAuthHeaderReachesServer() {
    val destPath = "/tmp/kolt_dl_auth_bearer_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOk(LoopbackHttpServer.Response.ok("ok")).use { server ->
      try {
        val headers = RepositoryAuth.Bearer("tok").toHeaders()
        val result = downloadFile("http://127.0.0.1:${server.port}/x", destPath, headers = headers)
        assertNotNull(result.get(), "downloadFile failed: $result")
        val log = server.awaitAccessLog()
        assertEquals(
          "Bearer tok",
          log.authorization(),
          "server access log must capture exact Bearer header value",
        )
      } finally {
        remove(destPath)
      }
    }
  }

  // Req 5.2: a Basic-configured repository must produce an
  // "Authorization: Basic <base64(user:password)>" header. The base64 of
  // "alice:s3cret" is "YWxpY2U6czNjcmV0" — pin the exact wire value.
  @Test
  fun basicAuthHeaderReachesServerWithExpectedBase64() {
    val destPath = "/tmp/kolt_dl_auth_basic_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOk(LoopbackHttpServer.Response.ok("ok")).use { server ->
      try {
        val headers = RepositoryAuth.Basic("alice", "s3cret").toHeaders()
        val result = downloadFile("http://127.0.0.1:${server.port}/x", destPath, headers = headers)
        assertNotNull(result.get(), "downloadFile failed: $result")
        val log = server.awaitAccessLog()
        assertEquals(
          "Basic YWxpY2U6czNjcmV0",
          log.authorization(),
          "server access log must capture exact Basic header value",
        )
      } finally {
        remove(destPath)
      }
    }
  }

  // Req 5 (anonymous baseline): when headers == null, the request must
  // carry no Authorization header at all. Asserting "the string
  // 'Authorization' does not appear anywhere in the access log" covers
  // both an absent header and a stray "Authorization: " empty line, and
  // implicitly verifies the slist itself was never built.
  @Test
  fun anonymousRequestEmitsNoAuthorizationHeader() {
    val destPath = "/tmp/kolt_dl_auth_anon_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOk(LoopbackHttpServer.Response.ok("ok")).use { server ->
      try {
        val result = downloadFile("http://127.0.0.1:${server.port}/x", destPath, headers = null)
        assertNotNull(result.get(), "downloadFile failed: $result")
        val log = server.awaitAccessLog()
        assertFalse(
          log.rawHeaderBlock.contains("Authorization", ignoreCase = true),
          "anonymous request must not carry any Authorization header; got:\n${log.rawHeaderBlock}",
        )
      } finally {
        remove(destPath)
      }
    }
  }

  // Req 5 (redirect policy): with CURLOPT_UNRESTRICTED_AUTH = 0L, libcurl
  // must NOT forward the Authorization header to a different-port redirect
  // target (host:port mismatch counts as cross-origin). Test asserts:
  //   - Server A receives Authorization
  //   - Server B receives no Authorization
  @Test
  fun crossOriginRedirectDropsAuthorizationHeader() {
    val destPath = "/tmp/kolt_dl_auth_redirect_${getpid()}.bin"
    remove(destPath)
    LoopbackHttpServer.startOk(LoopbackHttpServer.Response.ok("final")).use { serverB ->
      LoopbackHttpServer.startOk(
          LoopbackHttpServer.Response.redirect("http://127.0.0.1:${serverB.port}/final")
        )
        .use { serverA ->
          try {
            val headers = RepositoryAuth.Bearer("redirected-tok").toHeaders()
            val result =
              downloadFile("http://127.0.0.1:${serverA.port}/start", destPath, headers = headers)
            assertNotNull(result.get(), "downloadFile failed: $result")

            val logA = serverA.awaitAccessLog()
            val logB = serverB.awaitAccessLog()
            assertEquals(
              "Bearer redirected-tok",
              logA.authorization(),
              "initial server A must receive Authorization",
            )
            assertFalse(
              logB.rawHeaderBlock.contains("Authorization", ignoreCase = true),
              "redirect target server B must not receive Authorization; got:\n${logB.rawHeaderBlock}",
            )
          } finally {
            remove(destPath)
          }
        }
    }
  }
}

private fun LoopbackHttpServer.Companion.startOk(
  response: LoopbackHttpServer.Response
): LoopbackHttpServer = start(response).getOrElse { fail("LoopbackHttpServer.start failed: $it") }
