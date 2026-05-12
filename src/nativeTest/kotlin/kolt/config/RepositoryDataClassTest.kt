package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryDataClassTest {

  @Test
  fun repositoryHoldsUrlField() {
    val repo = Repository(url = "https://example.com")
    assertEquals("https://example.com", repo.url)
  }

  @Test
  fun rawRepositoryDefaultsUrlToNull() {
    val raw = RawRepository()
    assertEquals(null, raw.url)
  }

  @Test
  fun rawRepositoryHoldsUrlField() {
    val raw = RawRepository(url = "https://example.com")
    assertEquals("https://example.com", raw.url)
  }
}
