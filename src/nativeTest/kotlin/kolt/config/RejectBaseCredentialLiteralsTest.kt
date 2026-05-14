package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RejectBaseCredentialLiteralsTest {

  // Minimal valid base kolt.toml stem; tests append `[repositories.x]` blocks.
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

  @Test
  fun rejectsLiteralTokenInBaseKoltToml() {
    val toml =
      baseStem +
        """

        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        token = { literal = "abc" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "kolt.toml").getError())
    assertEquals("kolt.toml", error.path)
    val msg = error.message
    assertTrue(msg.contains("kolt.toml"), "message must name kolt.toml: $msg")
    assertTrue(msg.contains("[repositories.x]"), "message must name the repository: $msg")
    assertTrue(msg.contains("literal token field"), "message must name the offending field: $msg")
    assertTrue(msg.contains("kolt.local.toml"), "message must direct user to kolt.local.toml: $msg")
    assertFalse(msg.contains("abc"), "message must not include credential value: $msg")
  }

  @Test
  fun rejectsLiteralUserInBaseKoltToml() {
    val toml =
      baseStem +
        """

        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        user = { literal = "alice" }
        password = { literal = "s3cret" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "kolt.toml").getError())
    assertEquals("kolt.toml", error.path)
    val msg = error.message
    assertTrue(msg.contains("[repositories.x]"), "message must name the repository: $msg")
    // user is declared before password in the entry; first violation wins on
    // declaration order, so the message names `user`.
    assertTrue(msg.contains("literal user field"), "message must name user: $msg")
    assertTrue(msg.contains("kolt.local.toml"), "message must direct user to kolt.local.toml: $msg")
    assertFalse(msg.contains("alice"), "message must not include user value: $msg")
    assertFalse(msg.contains("s3cret"), "message must not include password value: $msg")
  }

  @Test
  fun rejectsLiteralPasswordInBaseKoltToml() {
    val toml =
      baseStem +
        """

        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        password = { literal = "s3cret" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "kolt.toml").getError())
    assertEquals("kolt.toml", error.path)
    val msg = error.message
    assertTrue(msg.contains("[repositories.x]"), "message must name the repository: $msg")
    assertTrue(msg.contains("literal password field"), "message must name password: $msg")
    assertTrue(msg.contains("kolt.local.toml"), "message must direct user to kolt.local.toml: $msg")
    assertFalse(msg.contains("s3cret"), "message must not include password value: $msg")
  }

  @Test
  fun rejectsEnvFormInBaseKoltTomlShapeBlind() {
    // Placement-policy first: env form in kolt.toml is rejected by this
    // validator before the env-not-supported message (which fires in
    // liftRepositoriesMap, task 2.6) can run.
    val toml =
      baseStem +
        """

        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        token = { env = "NEXUS_TOKEN" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml, path = "kolt.toml").getError())
    assertEquals("kolt.toml", error.path)
    val msg = error.message
    assertTrue(msg.contains("[repositories.x]"), "message must name the repository: $msg")
    assertTrue(msg.contains("literal token field"), "message must name token: $msg")
    assertTrue(msg.contains("kolt.local.toml"), "message must direct user to kolt.local.toml: $msg")
    assertFalse(msg.contains("NEXUS_TOKEN"), "message must not include env var name: $msg")
  }

  @Test
  fun acceptsOverlayOnlyCredential() {
    // Base declares the repository without credentials; overlay supplies
    // the token. rejectBaseCredentialLiterals must not fire because it
    // inspects rawBase only, not the merged result.
    val baseToml =
      baseStem +
        """

        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
      """
          .trimIndent()
    val overlayToml =
      """
        [repositories.x]
        token = { literal = "abc" }
      """
        .trimIndent()
    // Later validators (liftRepositoriesMap env-not-supported, mutex,
    // userinfo, non-empty) are out of scope for task 2.4 — but a literal
    // token in the overlay must not be rejected by THIS validator. The
    // overall parseConfig may still fail downstream (e.g. once liftRepositoriesMap
    // is wired in task 2.6), so we only assert the validator under test
    // does not produce the placement-policy message.
    val result =
      parseConfig(
        baseToml,
        path = "kolt.toml",
        overlayString = overlayToml,
        overlayPath = "kolt.local.toml",
      )
    val error = result.getError()
    if (error is ConfigError.ParseFailed) {
      assertFalse(
        error.message.contains("kolt.toml is intended to be committed"),
        "placement-policy validator must not fire for overlay-only credential: ${error.message}",
      )
    } else {
      // No error at all is fine — even better, it means downstream
      // validators are not (yet) wired and the overlay-only path parses cleanly.
      assertNotNull(result.get())
    }
  }
}
