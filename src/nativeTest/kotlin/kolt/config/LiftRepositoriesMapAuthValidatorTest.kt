package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pins each `liftRepositoriesMap` validator branch with a minimal fixture.
// The big parametric matrix lives in task 2.7 (RepositoryAuthConfigTest).
class LiftRepositoriesMapAuthValidatorTest {

  // Helper: source map indicating overlay-only credential contributions, base url.
  private fun overlayCredSource(
    name: String,
    fields: List<String>,
  ): Map<String, Map<String, String?>> {
    val inner = LinkedHashMap<String, String?>()
    inner["url"] = "kolt.toml"
    for (f in fields) inner[f] = "kolt.local.toml"
    return mapOf(name to inner)
  }

  @Test
  fun rejectsEmptyUrl() {
    // Existing behavior — first validator in fail-fast order.
    val raw = mapOf("custom" to RawRepository(url = ""))
    val src = mapOf("custom" to mapOf<String, String?>("url" to "kolt.toml"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("custom" in pf.message, "message must name repository: ${pf.message}")
    assertTrue("url" in pf.message, "message must mention url: ${pf.message}")
    assertEquals("kolt.toml", pf.path)
  }

  @Test
  fun rejectsUserinfoInUrlWithoutLeakingCredentials() {
    val raw = mapOf("nexus" to RawRepository(url = "https://alice:s3cret@nexus.example.com/repo"))
    val src = mapOf("nexus" to mapOf<String, String?>("url" to "kolt.toml"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repository: ${pf.message}")
    // Redaction guarantee Req 3.3: no userinfo character leaks.
    assertFalse("alice" in pf.message, "message must not leak user: ${pf.message}")
    assertFalse("s3cret" in pf.message, "message must not leak password: ${pf.message}")
    assertFalse("alice:s3cret" in pf.message, "message must not leak combo: ${pf.message}")
    assertEquals("kolt.toml", pf.path)
  }

  @Test
  fun acceptsAtSignInsidePath() {
    // `https://host/foo@bar` has no userinfo (the `@` is past the path separator).
    val raw = mapOf("ok" to RawRepository(url = "https://host.example.com/foo@bar"))
    val src = mapOf("ok" to mapOf<String, String?>("url" to "kolt.toml"))
    val result = liftRepositoriesMap(raw, src).get()
    assertNotNull(result)
    assertEquals("https://host.example.com/foo@bar", result["ok"]?.url)
  }

  @Test
  fun rejectsTokenAndUserMutex() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Literal("abc"),
            user = RawCredentialField.Literal("alice"),
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val src = overlayCredSource("nexus", listOf("token", "user", "password"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("token" in pf.message, "message must name token: ${pf.message}")
    assertTrue(
      "user" in pf.message || "password" in pf.message,
      "message must mention basic field(s): ${pf.message}",
    )
    assertTrue(
      "mutually exclusive" in pf.message || "exclusive" in pf.message,
      "message must explain mutex: ${pf.message}",
    )
    // Mutex rejection attributes to the contributing file of the token field.
    assertEquals("kolt.local.toml", pf.path)
    // Defense: no credential value in the message.
    assertFalse("abc" in pf.message, "message must not include token value: ${pf.message}")
    assertFalse("s3cret" in pf.message, "message must not include password value: ${pf.message}")
  }

  @Test
  fun rejectsUserWithoutPassword() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            user = RawCredentialField.Literal("alice"),
          )
      )
    val src = overlayCredSource("nexus", listOf("user"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("password" in pf.message, "message must name missing password: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsPasswordWithoutUser() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val src = overlayCredSource("nexus", listOf("password"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("user" in pf.message, "message must name missing user: ${pf.message}")
    assertFalse("s3cret" in pf.message, "message must not leak password value: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsEmptyLiteralToken() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Literal(""),
          )
      )
    val src = overlayCredSource("nexus", listOf("token"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("token" in pf.message, "message must name token: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsWhitespaceOnlyLiteralToken() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            // Unicode whitespace + ASCII whitespace mix.
            token = RawCredentialField.Literal(" \t \n"),
          )
      )
    val src = overlayCredSource("nexus", listOf("token"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("token" in pf.message, "message must name token: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsEmptyLiteralUser() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            user = RawCredentialField.Literal(""),
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val src = overlayCredSource("nexus", listOf("user", "password"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("user" in pf.message, "message must name user: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsEnvFormToken() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Env("NEXUS_TOKEN"),
          )
      )
    val src = overlayCredSource("nexus", listOf("token"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("token" in pf.message, "message must name token: ${pf.message}")
    assertTrue(
      "v1.0" in pf.message || "not supported" in pf.message,
      "message must indicate env unsupported in v1.0: ${pf.message}",
    )
    assertTrue("kolt.local.toml" in pf.message, "message must direct to overlay: ${pf.message}")
    // Defense (Req 8.2): env var name not echoed.
    assertFalse("NEXUS_TOKEN" in pf.message, "message must not include env var name: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun rejectsEnvFormUser() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            user = RawCredentialField.Env("NEXUS_USER"),
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val src = overlayCredSource("nexus", listOf("user", "password"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("nexus" in pf.message, "message must name repo: ${pf.message}")
    assertTrue("user" in pf.message, "message must name user: ${pf.message}")
    assertFalse("NEXUS_USER" in pf.message, "message must not include env var name: ${pf.message}")
    assertFalse("s3cret" in pf.message, "message must not include password value: ${pf.message}")
    assertEquals("kolt.local.toml", pf.path)
  }

  @Test
  fun mutexFiresBeforeNonEmpty() {
    // token + user + password all empty: order says mutex (#3) fires before
    // pair-completeness (#4) and non-empty (#5). User sees mutex message.
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Literal(""),
            user = RawCredentialField.Literal(""),
            password = RawCredentialField.Literal(""),
          )
      )
    val src = overlayCredSource("nexus", listOf("token", "user", "password"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue(
      "mutually exclusive" in pf.message || "exclusive" in pf.message,
      "mutex message must win: ${pf.message}",
    )
  }

  @Test
  fun pairCompletenessFiresBeforeNonEmpty() {
    // user without password — pair completeness wins over non-empty (the user
    // field is empty too, but pair-completeness sits earlier in the order).
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            user = RawCredentialField.Literal(""),
          )
      )
    val src = overlayCredSource("nexus", listOf("user"))
    val err = liftRepositoriesMap(raw, src).getError()
    val pf = assertIs<ConfigError.ParseFailed>(err)
    assertTrue("password" in pf.message, "pair-completeness must win: ${pf.message}")
  }

  @Test
  fun acceptsLiteralBearer() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Literal("abc"),
          )
      )
    val src = overlayCredSource("nexus", listOf("token"))
    val lifted = assertNotNull(liftRepositoriesMap(raw, src).get())
    val repo = assertNotNull(lifted["nexus"])
    assertEquals("nexus", repo.name)
    assertEquals("https://nexus.example.com/repo", repo.url)
    assertEquals(RepositoryAuth.Bearer("abc"), repo.auth)
  }

  @Test
  fun acceptsLiteralBasic() {
    val raw =
      mapOf(
        "nexus" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            user = RawCredentialField.Literal("alice"),
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val src = overlayCredSource("nexus", listOf("user", "password"))
    val lifted = assertNotNull(liftRepositoriesMap(raw, src).get())
    val repo = assertNotNull(lifted["nexus"])
    assertEquals("nexus", repo.name)
    assertEquals(RepositoryAuth.Basic("alice", "s3cret"), repo.auth)
  }

  @Test
  fun acceptsAnonymousRepository() {
    val raw = mapOf("central" to RawRepository(url = "https://repo1.maven.org/maven2"))
    val src = mapOf("central" to mapOf<String, String?>("url" to "kolt.toml"))
    val lifted = assertNotNull(liftRepositoriesMap(raw, src).get())
    val repo = assertNotNull(lifted["central"])
    assertEquals("central", repo.name)
    assertNull(repo.auth)
  }

  @Test
  fun nameInvariantHoldsForAllEntries() {
    // Multiple entries, including one whose key was quoted in the raw map.
    val raw =
      linkedMapOf(
        "central" to RawRepository(url = "https://repo1.maven.org/maven2"),
        "\"nexus\"" to
          RawRepository(
            url = "https://nexus.example.com/repo",
            token = RawCredentialField.Literal("abc"),
          ),
        "ghcr" to RawRepository(url = "https://maven.pkg.github.com/owner/repo"),
      )
    val src =
      mapOf(
        "central" to mapOf<String, String?>("url" to "kolt.toml"),
        "nexus" to mapOf<String, String?>("url" to "kolt.toml", "token" to "kolt.local.toml"),
        "ghcr" to mapOf<String, String?>("url" to "kolt.toml"),
      )
    val lifted = assertNotNull(liftRepositoriesMap(raw, src).get())
    for ((key, value) in lifted) {
      assertEquals(key, value.name, "Repository.name must equal map key for entry $key")
    }
    // Quote-stripped key is the canonical name.
    assertTrue("nexus" in lifted, "quoted key must be stripped: ${lifted.keys}")
  }

  @Test
  fun preservesDeclarationOrder() {
    val raw =
      linkedMapOf(
        "alpha" to RawRepository(url = "https://a.example.com"),
        "beta" to RawRepository(url = "https://b.example.com"),
        "gamma" to RawRepository(url = "https://c.example.com"),
      )
    val src =
      mapOf(
        "alpha" to mapOf<String, String?>("url" to "kolt.toml"),
        "beta" to mapOf<String, String?>("url" to "kolt.toml"),
        "gamma" to mapOf<String, String?>("url" to "kolt.toml"),
      )
    val lifted = assertNotNull(liftRepositoriesMap(raw, src).get())
    assertEquals(listOf("alpha", "beta", "gamma"), lifted.keys.toList())
  }
}
