package kolt.config

import kolt.resolve.AuthStateProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryAuthTest {

  @Test
  fun basicToStringRedactsPasswordAndKeepsUser() {
    val rendered = RepositoryAuth.Basic("alice", "s3cret").toString()
    assertTrue("<redacted>" in rendered, "expected `<redacted>` in $rendered")
    assertFalse("s3cret" in rendered, "expected `s3cret` to be absent from $rendered")
    assertTrue("alice" in rendered, "expected user `alice` to be visible in $rendered")
  }

  @Test
  fun bearerToStringRedactsToken() {
    val rendered = RepositoryAuth.Bearer("ghp_xyz123").toString()
    assertTrue("<redacted>" in rendered, "expected `<redacted>` in $rendered")
    assertFalse("ghp_xyz123" in rendered, "expected token to be absent from $rendered")
  }

  @Test
  fun bearerToHeadersProducesAuthorizationBearer() {
    val headers = RepositoryAuth.Bearer("abc123").toHeaders()
    assertEquals(mapOf("Authorization" to "Bearer abc123"), headers)
  }

  @Test
  fun basicToHeadersProducesAuthorizationBasicBase64() {
    val headers = RepositoryAuth.Basic("alice", "s3cret").toHeaders()
    // RFC 7617 base64 of "alice:s3cret" — pinned literal so a swap to
    // Base64.UrlSafe / Base64.Mime would be caught.
    assertEquals(mapOf("Authorization" to "Basic YWxpY2U6czNjcmV0"), headers)
  }

  @Test
  fun nullableProjectsToNotConfigured() {
    val auth: RepositoryAuth? = null
    assertEquals(AuthStateProjection.NotConfigured, auth.toStateProjection())
  }

  @Test
  fun bearerProjectsToConfiguredToken() {
    val auth: RepositoryAuth = RepositoryAuth.Bearer("t")
    assertEquals(AuthStateProjection.ConfiguredToken, auth.toStateProjection())
  }

  @Test
  fun basicProjectsToConfiguredBasic() {
    val auth: RepositoryAuth = RepositoryAuth.Basic("u", "p")
    assertEquals(AuthStateProjection.ConfiguredBasic, auth.toStateProjection())
  }

  @Test
  fun authStateProjectionDisplayStringsMatchRequirement7_3() {
    assertEquals("not configured", AuthStateProjection.NotConfigured.toDisplayString())
    assertEquals(
      "configured (token, from kolt.local.toml)",
      AuthStateProjection.ConfiguredToken.toDisplayString(),
    )
    assertEquals(
      "configured (basic, from kolt.local.toml)",
      AuthStateProjection.ConfiguredBasic.toDisplayString(),
    )
  }
}
