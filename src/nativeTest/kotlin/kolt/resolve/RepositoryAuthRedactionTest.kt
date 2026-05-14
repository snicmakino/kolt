package kolt.resolve

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.cli.HomeBreakdown
import kolt.cli.InfoSnapshot
import kolt.cli.JdkInfo
import kolt.cli.KotlinInfo
import kolt.cli.ProjectInfo
import kolt.cli.formatInfoJson
import kolt.config.Repository
import kolt.config.RepositoryAuth
import kolt.infra.downloadFile
import kolt.infra.testfixture.LoopbackHttpServer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove

// Cross-cutting redaction matrix (Req 8). Each assertion pins a distinct
// leak surface so a single architectural regression cannot silently widen the
// blast radius:
//   (a) 401 fetch with Bearer auth → rendered diagnostic carries no token
//   (b) 401 fetch with Basic auth → rendered diagnostic carries no user, no
//       password, no base64(user:password)
//   (c) `RepositoryAuth.Basic.toString()` redacts the password
//   (d) `Repository.toString()` chains through `RepositoryAuth.toString` so
//       even `"$repo"` interpolation leaves no credential trace
//   (e) `Lockfile` serialized JSON has no credential-shaped field — pins the
//       Req 10.x invariant that the lockfile schema is unchanged
//   (f) `kolt info --format=json` payload has no credential-shaped field —
//       pins that the InfoJson schema does not surface auth state
//
// All six are green on first run by design (tasks 1.1, 4.x, 5.x already
// implement the redaction); this file's role is the regression pin.
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class RepositoryAuthRedactionTest {

  // (a) Bearer token must not leak through the 401 rendering path. Drives
  // the real loopback server + real `downloadFile` + real `formatResolveError`
  // so any leak introduced anywhere in that chain (header echo, error
  // message reflection, hint text accidentally interpolating the auth value)
  // would surface as a substring hit.
  @Test
  fun bearerTokenIsAbsentFromRendered401Diagnostic() {
    val token = "super-secret-tok-12345"
    val rendered = renderAuthFailure(status = 401, auth = RepositoryAuth.Bearer(token))
    assertFalse(token in rendered, "Bearer token literal must not appear in:\n$rendered")
  }

  // (b) Basic user, password, and their base64(user:password) form must all
  // be absent from the 401 rendering path. Three independent substring
  // assertions because each one catches a different regression: leaking
  // `user` would come from a misplaced `repo.toString()`; leaking `password`
  // from a missing `RepositoryAuth.Basic.toString()` override; leaking the
  // base64 from echoing the literal `Authorization: Basic <b64>` header.
  @Test
  fun basicCredentialsAreAbsentFromRendered401Diagnostic() {
    val user = "alice-distinctive"
    val password = "s3cret-password-87"
    val base64 = Base64.encode("$user:$password".encodeToByteArray())
    val rendered = renderAuthFailure(status = 401, auth = RepositoryAuth.Basic(user, password))
    assertFalse(user in rendered, "Basic user literal must not appear in:\n$rendered")
    assertFalse(password in rendered, "Basic password literal must not appear in:\n$rendered")
    assertFalse(base64 in rendered, "Basic base64 literal must not appear in:\n$rendered")
  }

  // (c) `RepositoryAuth.Basic.toString()` is the load-bearing override. If
  // the data-class default is restored, both `"$auth"` and any
  // `Repository.toString()` chain leak. Distinctive password literal avoids
  // false negatives from coincidental short substrings.
  @Test
  fun repositoryAuthBasicToStringRedactsPassword() {
    val password = "super-secret-pw-c"
    val rendered = RepositoryAuth.Basic("u", password).toString()
    assertTrue("<redacted>" in rendered, "expected <redacted> placeholder in: $rendered")
    assertFalse(password in rendered, "password literal must not appear in: $rendered")
  }

  // (d) `Repository.toString()` is the data-class default that delegates the
  // auth-field rendering to `RepositoryAuth.Basic.toString()`. This pins the
  // chain end-to-end so a future code change like overriding
  // `Repository.toString()` to print fields manually cannot regress.
  @Test
  fun repositoryToStringRedactsBasicPassword() {
    val password = "s3cret-d-distinctive"
    val rendered =
      Repository(name = "x", url = "y", auth = RepositoryAuth.Basic("alice", password)).toString()
    assertTrue("<redacted>" in rendered, "expected <redacted> in: $rendered")
    assertFalse(password in rendered, "password literal must not appear in: $rendered")
  }

  // (e) `kolt.lock` schema is unchanged by this feature (Req 8.4, Req 10.x).
  // We construct a representative `Lockfile`, serialize it via the real
  // writer, and assert that none of the credential-shaped field names
  // surface. The hash-shaped placeholder values are chosen to NOT contain
  // any of the forbidden substrings.
  @Test
  fun serializedLockfileHasNoCredentialField() {
    val lockfile =
      Lockfile(
        version = LOCKFILE_VERSION,
        kotlin = "2.1.0",
        jvmTarget = "17",
        dependencies = mapOf("com.example:lib" to LockEntry(version = "1.0", sha256 = "abc123")),
      )
    val serialized = serializeLockfile(lockfile)
    assertFalse("token" in serialized, "lockfile must not carry 'token' field: $serialized")
    assertFalse("password" in serialized, "lockfile must not carry 'password' field: $serialized")
    assertFalse(
      "Authorization" in serialized,
      "lockfile must not carry 'Authorization' field: $serialized",
    )
    // `user` is too generic to be safe against false matches in other field
    // names; assert the exact JSON shape would not nest it under a known
    // credential context by checking that no `"user"` key appears.
    assertFalse("\"user\"" in serialized, "lockfile must not carry a 'user' JSON key: $serialized")
  }

  // (f) `kolt info --format=json` is the diagnostic surface most likely to
  // grow a "show me my repositories" verbose mode. The InfoJson schema must
  // never surface credential-shaped fields; this test pins the schema.
  // Building the snapshot with strings unrelated to credentials prevents
  // false positives from the project name or path matching a literal.
  @Test
  fun koltInfoJsonHasNoCredentialField() {
    val snap =
      InfoSnapshot(
        koltVersion = "0.20.0",
        koltPath = "/usr/local/bin/kolt",
        koltHomeDisplay = "~/.kolt",
        koltHomeBytes = 0L,
        kotlin = KotlinInfo(version = "2.1.0", mode = "daemon", path = "~/.kolt/k/2.1.0"),
        jdk = JdkInfo(version = "21", path = "~/.kolt/jdk/21"),
        host = "linux-x86_64",
        project = ProjectInfo(name = "demo", version = "0.1.0", kind = "app", target = "jvm"),
        koltHomeBreakdown =
          HomeBreakdown(
            cacheBytes = 0L,
            toolchainsBytes = 0L,
            daemonBytes = 0L,
            toolsBytes = 0L,
            cachePath = "~/.kolt/cache",
            toolchainsPath = "~/.kolt/toolchains",
            daemonPath = "~/.kolt/daemon",
            toolsPath = "~/.kolt/tools",
          ),
      )
    val json = formatInfoJson(snap)
    assertFalse("token" in json, "info json must not carry 'token' field: $json")
    assertFalse("password" in json, "info json must not carry 'password' field: $json")
    assertFalse("Authorization" in json, "info json must not carry 'Authorization' field: $json")
    assertFalse("\"user\"" in json, "info json must not carry a 'user' JSON key: $json")
  }

  private fun renderAuthFailure(status: Int, auth: RepositoryAuth): String {
    val destPath = "/tmp/kolt_redaction_${status}_${getpid()}.bin"
    remove(destPath)
    val reason = if (status == 401) "Unauthorized" else "Forbidden"
    return LoopbackHttpServer.startOrFailRedaction(
        LoopbackHttpServer.Response.withStatus(status, reason, "")
      )
      .use { server ->
        try {
          val repo =
            Repository(name = "private", url = "http://127.0.0.1:${server.port}", auth = auth)

          val result =
            downloadFromRepositories(
              repos = listOf(repo),
              destPath = destPath,
              urlBuilder = { r -> "${r.url}/foo.jar" },
              download = { url, dst, headers -> downloadFile(url, dst, headers) },
            )

          val failure = assertIs<RepositoryDownloadFailure.AuthFailed>(result.getError())
          val diagnostic =
            formatResolveError(ResolveError.DownloadFailed("com.example:lib", failure))
          (listOf(diagnostic.headline) + diagnostic.context + listOfNotNull(diagnostic.hint))
            .joinToString("\n")
        } finally {
          remove(destPath)
        }
      }
  }
}

private fun LoopbackHttpServer.Companion.startOrFailRedaction(
  response: LoopbackHttpServer.Response
): LoopbackHttpServer = start(response).getOrElse { fail("LoopbackHttpServer.start failed: $it") }
