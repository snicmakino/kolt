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

// End-to-end parametric matrix driving parseConfig (with overlay) so that the
// full chain — ktoml decode -> rejectBaseCredentialFields -> mergeOverlay ->
// repositorySourceMap -> liftRepositoriesMap — composes with realistic
// kolt.toml / kolt.local.toml fixtures. Per-validator unit branches live in
// LiftRepositoriesMapAuthValidatorTest; this file pins source attribution
// (ParseFailed.path) and surface-level message shape that only the chain can
// expose.
class RepositoryAuthConfigTest {

  // Base stem: everything a kolt.toml needs to parse minus the `[repositories]`
  // block, which each scenario appends inline.
  private val baseStem =
    """
      name = "demo"
      version = "0.0.0"

      [kotlin]
      version = "2.3.20"

      [build]
      target = "jvm"
      main = "com.example.main"
      sources = ["src"]

    """
      .trimIndent()

  // Expected outcome encoded as a sealed class — each row in the matrix
  // declares exactly one of these, and the runner switches on it.
  private sealed class Expected {
    // Successful parse. The lambda inspects the resulting repository map.
    data class Ok(val assert: (Map<String, Repository>) -> Unit) : Expected()

    // Parse failure. `path` is asserted on ParseFailed.path. Each substring
    // in `messageContains` must appear in the message; each in `messageAbsent`
    // must NOT appear (credential-value leak guard).
    data class Err(
      val path: String?,
      val messageContains: List<String> = emptyList(),
      val messageAbsent: List<String> = emptyList(),
    ) : Expected()
  }

  private data class Scenario(
    val label: String,
    val baseRepos: String,
    val overlayRepos: String? = null,
    val expected: Expected,
  )

  private fun buildBase(reposBlock: String): String = baseStem + reposBlock.trimIndent()

  // Single shared anonymous repo declaration that scenarios reuse as the
  // base side of a kolt.toml + kolt.local.toml pair.
  private val baseUrlOnly =
    """

      [repositories.x]
      url = "https://nexus.example.com/repository/internal"
    """

  // Each scenario row is one line in the data table. Labels are descriptive
  // so a failing row pinpoints the regression. Each row catches one unique
  // regression and is annotated with the requirement it pins.
  private val scenarios: List<Scenario> =
    listOf(
      // (a) Req 1.1 / 1.5: literal token in overlay -> Bearer.
      Scenario(
        label = "a/overlay-token-literal -> Bearer",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "abc" }
          """,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["x"])
            assertEquals(RepositoryAuth.Bearer("abc"), r.auth)
          },
      ),
      // (b) Req 1.1 / 1.5: literal user + password in overlay -> Basic.
      Scenario(
        label = "b/overlay-user-password-literal -> Basic",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            user = { literal = "alice" }
            password = { literal = "s3cret" }
          """,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["x"])
            assertEquals(RepositoryAuth.Basic("alice", "s3cret"), r.auth)
          },
      ),
      // (c) Req 1.2: token + user mutex; path attributes to overlay (token
      // contributing file). Credential values must not leak.
      Scenario(
        label = "c/overlay-token-user-mutex",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "abc" }
            user = { literal = "alice" }
            password = { literal = "s3cret" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("x", "token", "exclusive"),
            messageAbsent = listOf("abc", "alice", "s3cret"),
          ),
      ),
      // (d) Req 1.3: user alone -> pair-completeness reject.
      Scenario(
        label = "d/overlay-user-alone-pair-incomplete",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            user = { literal = "alice" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("x", "password"),
            messageAbsent = listOf("alice"),
          ),
      ),
      // (e) Req 1.4: empty literal -> non-empty reject; value `""` not echoed
      // (covered tautologically; assert message names the field).
      Scenario(
        label = "e/overlay-token-empty-literal",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "" }
          """,
        expected = Expected.Err(path = "kolt.local.toml", messageContains = listOf("x", "token")),
      ),
      // (e') Req 1.4: Unicode/ASCII whitespace-only literal -> non-empty reject.
      Scenario(
        label = "e'/overlay-token-whitespace-literal",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "   " }
          """,
        expected = Expected.Err(path = "kolt.local.toml", messageContains = listOf("x", "token")),
      ),
      // (f) Req 1.6: inline-table with neither literal nor env -> ktoml
      // SerializationException via RawCredentialFieldSerializer. Bubbled into
      // ParseFailed via parseLocalOverlay's catch path.
      Scenario(
        label = "f/overlay-token-empty-inline-table",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = {}
          """,
        expected =
          Expected.Err(path = "kolt.local.toml", messageContains = listOf("literal", "env")),
      ),
      // (f') Req 1.7: inline-table with both literal AND env -> SerializationException.
      Scenario(
        label = "f'/overlay-token-literal-and-env",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "x", env = "Y" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("literal", "env"),
            // `x` is too short to safely assert-absent; assert env var name absent only.
            messageAbsent = listOf("Y"),
          ),
      ),
      // (g) Req 2.1 / 2.4: literal token in BASE kolt.toml -> placement reject
      // attributed to kolt.toml. Credential value must NOT appear in message.
      Scenario(
        label = "g/base-token-literal-placement",
        baseRepos =
          """

            [repositories.x]
            url = "https://nexus.example.com/repository/internal"
            token = { literal = "abc" }
          """,
        overlayRepos = null,
        expected =
          Expected.Err(
            path = "kolt.toml",
            messageContains = listOf("kolt.toml", "x", "token", "kolt.local.toml"),
            messageAbsent = listOf("abc"),
          ),
      ),
      // (h) Req 2.2: literal token in overlay accepted; placement validator
      // does NOT fire because base side has no auth fields.
      Scenario(
        label = "h/overlay-token-literal-accepted",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { literal = "abc" }
          """,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["x"])
            assertEquals(RepositoryAuth.Bearer("abc"), r.auth)
          },
      ),
      // (i) Req 2.3: overlay-sourced env-form -> env-reject. Path attributes
      // to kolt.local.toml; message names kolt.local.toml. Env var name absent.
      Scenario(
        label = "i/overlay-token-env-reject",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            token = { env = "GITHUB_TOKEN" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("x", "token", "kolt.local.toml"),
            messageAbsent = listOf("GITHUB_TOKEN"),
          ),
      ),
      // (k) Req 2.3 multi-field: env-form user/password also rejected.
      // user is the first env-form field in the entry; we pin user here and
      // password in a second row.
      Scenario(
        label = "k/overlay-user-env-reject",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            user = { env = "NEXUS_USER" }
            password = { literal = "s3cret" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("x", "user", "kolt.local.toml"),
            messageAbsent = listOf("NEXUS_USER", "s3cret"),
          ),
      ),
      Scenario(
        label = "k/overlay-password-env-reject",
        baseRepos = baseUrlOnly,
        overlayRepos =
          """
            [repositories.x]
            user = { literal = "alice" }
            password = { env = "NEXUS_PASSWORD" }
          """,
        expected =
          Expected.Err(
            path = "kolt.local.toml",
            messageContains = listOf("x", "password", "kolt.local.toml"),
            // env var name absent; "alice" is a valid literal user — it may
            // be echoed in messages that name the user field but we are
            // asserting on the password reject, so neither should leak.
            messageAbsent = listOf("NEXUS_PASSWORD"),
          ),
      ),
      // (k') Req 2.1 + Req 2.3 ordering: env-form in BASE produces the
      // placement message, NOT the env-not-supported message. Confirms
      // rejectBaseCredentialFields runs before liftRepositoriesMap env-reject.
      Scenario(
        label = "k'/base-token-env-form -> placement (not env-reject)",
        baseRepos =
          """

            [repositories.x]
            url = "https://nexus.example.com/repository/internal"
            token = { env = "NEXUS_TOKEN" }
          """,
        overlayRepos = null,
        expected =
          Expected.Err(
            path = "kolt.toml",
            messageContains =
              listOf(
                "kolt.toml",
                "x",
                "token",
                "kolt.local.toml",
                // The placement validator says "literal <field> field"; this
                // word distinguishes placement from env-not-supported text.
                "literal",
              ),
            messageAbsent = listOf("NEXUS_TOKEN", "not supported", "v1.0"),
          ),
      ),
      // (l) Req 1.8 backward compat: `[repositories.central] url = "..."` only
      // in kolt.toml (no overlay, no auth fields) -> Ok, anonymous.
      Scenario(
        label = "l/base-anonymous-only -> anonymous",
        baseRepos =
          """

            [repositories.central]
            url = "https://repo1.maven.org/maven2"
          """,
        overlayRepos = null,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["central"])
            assertEquals("central", r.name)
            assertNull(r.auth)
          },
      ),
      // (m') Req 1.8 backward compat: same name in both files, no auth fields
      // on either side -> Ok, anonymous. Pins that overlay decode of an
      // auth-less `[repositories.<name>]` still parses and merges cleanly.
      Scenario(
        label = "m'/base-and-overlay-anonymous -> anonymous",
        baseRepos =
          """

            [repositories.x]
            url = "https://nexus.example.com/repository/internal"
          """,
        overlayRepos =
          """
            [repositories.x]
            url = "https://nexus.example.com/repository/internal"
          """,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["x"])
            assertNull(r.auth)
          },
      ),
      // (n) Name invariant: quoted repo name + dotted key. After lift,
      // map_key == Repository.name and quotes are stripped.
      Scenario(
        label = "n/quoted-name -> name == map_key",
        baseRepos =
          """

            [repositories."my.repo"]
            url = "https://nexus.example.com/repository/internal"
          """,
        overlayRepos =
          """
            [repositories."my.repo"]
            token = { literal = "t" }
          """,
        expected =
          Expected.Ok { repos ->
            val r = assertNotNull(repos["my.repo"])
            assertEquals("my.repo", r.name)
            assertEquals(RepositoryAuth.Bearer("t"), r.auth)
            for ((key, value) in repos) {
              assertEquals(key, value.name, "Repository.name must equal map key for entry '$key'")
            }
          },
      ),
    )

  @Test
  fun parametricMatrix() {
    for (s in scenarios) {
      val baseToml = buildBase(s.baseRepos)
      val overlay = s.overlayRepos?.trimIndent()
      val result =
        parseConfig(
          tomlString = baseToml,
          path = "kolt.toml",
          overlayString = overlay,
          overlayPath = if (overlay != null) "kolt.local.toml" else null,
        )
      when (val expected = s.expected) {
        is Expected.Ok -> {
          val config =
            assertNotNull(
              result.get(),
              "[${s.label}] expected Ok but got Err: ${result.getError()}",
            )
          expected.assert(config.repositories)
        }
        is Expected.Err -> {
          val error =
            assertIs<ConfigError.ParseFailed>(
              result.getError(),
              "[${s.label}] expected Err(ParseFailed) but got: $result",
            )
          assertEquals(
            expected.path,
            error.path,
            "[${s.label}] ParseFailed.path mismatch (message=${error.message})",
          )
          for (needle in expected.messageContains) {
            assertTrue(
              needle in error.message,
              "[${s.label}] expected message to contain '$needle': ${error.message}",
            )
          }
          for (forbidden in expected.messageAbsent) {
            assertFalse(
              forbidden in error.message,
              "[${s.label}] expected message to NOT contain '$forbidden': ${error.message}",
            )
          }
        }
      }
    }
  }
}
