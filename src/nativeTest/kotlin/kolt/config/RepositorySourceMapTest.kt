package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositorySourceMapTest {

  @Test
  fun attributesBaseOnlyFieldsToBasePath() {
    val base = mapOf("central" to RawRepository(url = "https://repo.maven.apache.org/maven2"))
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = null,
        basePath = "kolt.toml",
        overlayPath = null,
      )
    assertEquals("kolt.toml", map["central"]?.get("url"))
    val inner = map["central"]!!
    assertFalse("token" in inner, "token must be absent when unset: $inner")
    assertFalse("user" in inner, "user must be absent when unset: $inner")
    assertFalse("password" in inner, "password must be absent when unset: $inner")
  }

  @Test
  fun overlayContributedFieldsResolveToOverlayPath() {
    // Base supplies url; overlay supplies token. Per task 2.5 observable-completion:
    // map["repo"]["url"] == basePath, map["repo"]["token"] == overlayPath,
    // map["repo"]["user"] is absent.
    val base = mapOf("repo" to RawRepository(url = "https://nexus.example.com/repository/internal"))
    val overlay = mapOf("repo" to RawRepository(token = RawCredentialField.Literal("abc")))
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = overlay,
        basePath = "kolt.toml",
        overlayPath = "kolt.local.toml",
      )
    val inner = map["repo"]!!
    assertEquals("kolt.toml", inner["url"])
    assertEquals("kolt.local.toml", inner["token"])
    assertFalse("user" in inner, "user must be absent: $inner")
    assertFalse("password" in inner, "password must be absent: $inner")
  }

  @Test
  fun overlayWinsOverBaseOnConflict() {
    // Last-write-wins: overlay's url replaces base's url path attribution.
    val base = mapOf("repo" to RawRepository(url = "https://old.example.com"))
    val overlay = mapOf("repo" to RawRepository(url = "https://new.example.com"))
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = overlay,
        basePath = "kolt.toml",
        overlayPath = "kolt.local.toml",
      )
    assertEquals("kolt.local.toml", map["repo"]?.get("url"))
  }

  @Test
  fun overlayUserPasswordAttributedToOverlay() {
    val base = mapOf("repo" to RawRepository(url = "https://nexus.example.com/repository/internal"))
    val overlay =
      mapOf(
        "repo" to
          RawRepository(
            user = RawCredentialField.Literal("alice"),
            password = RawCredentialField.Literal("s3cret"),
          )
      )
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = overlay,
        basePath = "kolt.toml",
        overlayPath = "kolt.local.toml",
      )
    val inner = map["repo"]!!
    assertEquals("kolt.toml", inner["url"])
    assertEquals("kolt.local.toml", inner["user"])
    assertEquals("kolt.local.toml", inner["password"])
    assertFalse("token" in inner, "token must be absent: $inner")
  }

  @Test
  fun nullPathsPassThrough() {
    // Internal-test scenario: parseConfig called without `path`. The contributing
    // file path is unknown, but the inner key must still be present to signal
    // "this field is set on this repo".
    val base = mapOf("repo" to RawRepository(url = "https://example.com"))
    val overlay = mapOf("repo" to RawRepository(token = RawCredentialField.Literal("abc")))
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = overlay,
        basePath = null,
        overlayPath = null,
      )
    val inner = map["repo"]!!
    assertTrue("url" in inner, "url key must be present even when path is null")
    assertNull(inner["url"])
    assertTrue("token" in inner, "token key must be present even when path is null")
    assertNull(inner["token"])
  }

  @Test
  fun preservesDeclarationOrder() {
    // ktoml preserves declaration order; the source map iterates in the same
    // order so downstream diagnostics can name the first offending field.
    val base =
      mapOf(
        "alpha" to RawRepository(url = "https://a.example.com"),
        "beta" to RawRepository(url = "https://b.example.com"),
        "gamma" to RawRepository(url = "https://c.example.com"),
      )
    val map =
      repositorySourceMap(
        baseRepositories = base,
        overlayRepositories = null,
        basePath = "kolt.toml",
        overlayPath = null,
      )
    assertEquals(listOf("alpha", "beta", "gamma"), map.keys.toList())
  }
}
