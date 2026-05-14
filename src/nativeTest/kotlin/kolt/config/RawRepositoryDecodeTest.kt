package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.Serializable

internal class RawRepositoryDecodeTest {

  @Serializable
  private data class Wrapper(val repositories: Map<String, RawRepository> = emptyMap())

  private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

  @Test
  fun decodesUrlOnlySubTable() {
    val input =
      """
        [repositories.central]
        url = "https://repo1.maven.org/maven2"
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val repo = assertNotNull(parsed.repositories["central"])
    assertEquals("https://repo1.maven.org/maven2", repo.url)
    assertNull(repo.token)
    assertNull(repo.user)
    assertNull(repo.password)
  }

  @Test
  fun decodesTokenCredentialAlongsideUrl() {
    val input =
      """
        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        token = { literal = "abc" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val repo = assertNotNull(parsed.repositories["x"])
    assertEquals("https://nexus.example.com/repository/internal", repo.url)
    val token = repo.token
    assertIs<RawCredentialField.Literal>(token)
    assertEquals("abc", token.value)
    assertNull(repo.user)
    assertNull(repo.password)
  }

  @Test
  fun decodesUserPasswordCredentialAlongsideUrl() {
    val input =
      """
        [repositories.x]
        url = "https://nexus.example.com/repository/internal"
        user = { literal = "alice" }
        password = { env = "NEXUS_PASS" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val repo = assertNotNull(parsed.repositories["x"])
    val user = repo.user
    val password = repo.password
    assertIs<RawCredentialField.Literal>(user)
    assertEquals("alice", user.value)
    assertIs<RawCredentialField.Env>(password)
    assertEquals("NEXUS_PASS", password.varName)
    assertNull(repo.token)
  }
}
