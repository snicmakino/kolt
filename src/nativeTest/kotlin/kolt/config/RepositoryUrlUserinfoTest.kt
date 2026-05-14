package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// End-to-end driver for Req 3.x (URL userinfo rejection). Exercises the real
// parseConfig chain so the userinfo validator inside liftRepositoriesMap is
// wired through ktoml decode + rejectBaseCredentialFields + sourceMap path
// attribution. Per-validator branches live in LiftRepositoriesMapAuthValidatorTest;
// this file pins (a) reject for user:password@ form, (b) reject for user@ form,
// (c) accept for `@` inside path, and (d) the Req 3.3 leak guard: the userinfo
// substring is never echoed back into the rejection message.
class RepositoryUrlUserinfoTest {

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

  private fun buildConfig(reposBlock: String): String = baseStem + reposBlock.trimIndent()

  @Test
  fun rejectsUserPasswordUserinfo() {
    val toml =
      buildConfig(
        """

          [repositories.x]
          url = "https://u:p@host/x"
        """
      )

    val result = parseConfig(tomlString = toml, path = "kolt.toml")

    val error = assertIs<ConfigError.ParseFailed>(result.getError())
    assertEquals("kolt.toml", error.path)
  }

  @Test
  fun rejectsUserOnlyUserinfo() {
    val toml =
      buildConfig(
        """

          [repositories.x]
          url = "https://u@host/x"
        """
      )

    val result = parseConfig(tomlString = toml, path = "kolt.toml")

    val error = assertIs<ConfigError.ParseFailed>(result.getError())
    assertEquals("kolt.toml", error.path)
  }

  @Test
  fun acceptsAtSignInsidePath() {
    val toml =
      buildConfig(
        """

          [repositories.x]
          url = "https://host/foo@bar/baz"
        """
      )

    val result = parseConfig(tomlString = toml, path = "kolt.toml")

    val config = assertNotNull(result.get(), "expected Ok but got: ${result.getError()}")
    val repo = assertNotNull(config.repositories["x"])
    assertEquals("https://host/foo@bar/baz", repo.url)
  }

  // Req 3.3 leak guard. The literal characters `u`, `p`, `:`, `@` each appear in
  // unrelated tokens of the rejection message (e.g. "url", "password",
  // "https://", "repositories"), so the spec phrasing "no character of the
  // userinfo component" is asserted pragmatically: the verbatim userinfo
  // substring `u:p@host` (and `u:p@`) must not appear anywhere in the message.
  @Test
  fun rejectionMessageDoesNotEchoUserinfo() {
    val toml =
      buildConfig(
        """

          [repositories.x]
          url = "https://u:p@host/x"
        """
      )

    val result = parseConfig(tomlString = toml, path = "kolt.toml")

    val error = assertIs<ConfigError.ParseFailed>(result.getError())
    assertFalse(
      "u:p@host" in error.message,
      "message must not echo the userinfo substring: ${error.message}",
    )
    assertFalse(
      "u:p@" in error.message,
      "message must not echo the user:password@ fragment: ${error.message}",
    )
    assertFalse(
      "u@host" in error.message,
      "message must not echo a user@host fragment: ${error.message}",
    )
  }
}
