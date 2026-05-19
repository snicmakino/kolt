package kolt.selfupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SelfUpdateErrorTest {

  @Test
  fun networkCarriesUrlAndDetail() {
    val error: SelfUpdateError =
      SelfUpdateError.Network("https://api.github.com/x", "connect timed out")
    val network = assertIs<SelfUpdateError.Network>(error)
    assertEquals("https://api.github.com/x", network.url)
    assertEquals("connect timed out", network.detail)
  }

  @Test
  fun metadataCarriesDetail() {
    val error: SelfUpdateError = SelfUpdateError.Metadata("tag_name missing")
    val metadata = assertIs<SelfUpdateError.Metadata>(error)
    assertEquals("tag_name missing", metadata.detail)
  }

  @Test
  fun assetCarriesNameAndDetail() {
    val error: SelfUpdateError =
      SelfUpdateError.Asset("kolt-0.21.0-linux-x64.tar.gz", "not published")
    val asset = assertIs<SelfUpdateError.Asset>(error)
    assertEquals("kolt-0.21.0-linux-x64.tar.gz", asset.name)
    assertEquals("not published", asset.detail)
  }

  @Test
  fun extractCarriesDetail() {
    val error: SelfUpdateError = SelfUpdateError.Extract("libarchive rejected absolute path")
    val extract = assertIs<SelfUpdateError.Extract>(error)
    assertEquals("libarchive rejected absolute path", extract.detail)
  }

  @Test
  fun layoutCarriesDetectedPathAndDetail() {
    val error: SelfUpdateError =
      SelfUpdateError.Layout("/usr/local/bin/kolt", "not an installer-managed symlink")
    val layout = assertIs<SelfUpdateError.Layout>(error)
    assertEquals("/usr/local/bin/kolt", layout.detectedPath)
    assertEquals("not an installer-managed symlink", layout.detail)
  }

  @Test
  fun platformCarriesSysnameAndMachine() {
    val error: SelfUpdateError = SelfUpdateError.Platform("Darwin", "arm64")
    val platform = assertIs<SelfUpdateError.Platform>(error)
    assertEquals("Darwin", platform.sysname)
    assertEquals("arm64", platform.machine)
  }

  @Test
  fun homeCarriesDetail() {
    val error: SelfUpdateError = SelfUpdateError.Home("\$HOME is not set")
    val home = assertIs<SelfUpdateError.Home>(error)
    assertEquals("\$HOME is not set", home.detail)
  }
}
