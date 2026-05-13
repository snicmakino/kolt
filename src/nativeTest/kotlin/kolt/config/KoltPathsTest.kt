package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals

class KoltPathsTest {

  @Test
  fun cacheBaseConstructedFromHome() {
    val paths = KoltPaths("/home/user")
    assertEquals("/home/user/.kolt/cache", paths.cacheBase)
  }

  @Test
  fun toolsDirConstructedFromHome() {
    val paths = KoltPaths("/home/user")
    assertEquals("/home/user/.kolt/tools", paths.toolsDir)
  }

  @Test
  fun toolchainsDirConstructedFromHome() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains", paths.toolchainsDir)
  }

  @Test
  fun kotlincPathBuildsVersionedDirectory() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0", paths.kotlincPath("2.1.0"))
  }

  @Test
  fun kotlincBinBuildsVersionedBinPath() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", paths.kotlincBin("2.1.0"))
  }

  @Test
  fun kotlincPathDifferentVersion() {
    val paths = KoltPaths("/home/alice")

    assertEquals("/home/alice/.kolt/toolchains/kotlinc/2.3.20", paths.kotlincPath("2.3.20"))
  }

  @Test
  fun kotlincBinDifferentHome() {
    val paths = KoltPaths("/root")

    assertEquals("/root/.kolt/toolchains/kotlinc/2.3.20/bin/kotlinc", paths.kotlincBin("2.3.20"))
  }

  @Test
  fun jdkPathBuildsVersionedDirectory() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains/jdk/21", paths.jdkPath("21"))
  }

  @Test
  fun jdkPathDifferentVersion() {
    val paths = KoltPaths("/home/alice")

    assertEquals("/home/alice/.kolt/toolchains/jdk/17", paths.jdkPath("17"))
  }

  @Test
  fun javaBinBuildsVersionedBinPath() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains/jdk/21/bin/java", paths.javaBin("21"))
  }

  @Test
  fun javaBinDifferentHome() {
    val paths = KoltPaths("/root")

    assertEquals("/root/.kolt/toolchains/jdk/17/bin/java", paths.javaBin("17"))
  }

  @Test
  fun jarBinBuildsVersionedBinPath() {
    val paths = KoltPaths("/home/user")

    assertEquals("/home/user/.kolt/toolchains/jdk/21/bin/jar", paths.jarBin("21"))
  }

  @Test
  fun jarBinDifferentHome() {
    val paths = KoltPaths("/root")

    assertEquals("/root/.kolt/toolchains/jdk/17/bin/jar", paths.jarBin("17"))
  }

  @Test
  fun konancPathBuildsVersionedDirectory() {
    val paths = KoltPaths("/home/user")

    val path = paths.konancPath("2.3.20")

    assertEquals("/home/user/.kolt/toolchains/konanc/2.3.20", path)
  }

  @Test
  fun konancPathDifferentVersion() {
    val paths = KoltPaths("/home/alice")

    assertEquals("/home/alice/.kolt/toolchains/konanc/2.1.0", paths.konancPath("2.1.0"))
  }

  @Test
  fun konancBinBuildsVersionedBinPath() {
    val paths = KoltPaths("/home/user")

    val bin = paths.konancBin("2.3.20")

    assertEquals("/home/user/.kolt/toolchains/konanc/2.3.20/bin/konanc", bin)
  }

  @Test
  fun konancBinDifferentHome() {
    val paths = KoltPaths("/root")

    assertEquals("/root/.kolt/toolchains/konanc/2.3.20/bin/konanc", paths.konancBin("2.3.20"))
  }

  @Test
  fun jdkJavaBinAndJarBinShareSameVersionedDirectory() {
    val paths = KoltPaths("/home/user")
    val base = paths.jdkPath("21")
    assertEquals("$base/bin/java", paths.javaBin("21"))
    assertEquals("$base/bin/jar", paths.jarBin("21"))
  }

  @Test
  fun cinteropBinBuildsVersionedBinPath() {
    val paths = KoltPaths("/home/user")

    assertEquals(
      "/home/user/.kolt/toolchains/konanc/2.3.20/bin/cinterop",
      paths.cinteropBin("2.3.20"),
    )
  }

  @Test
  fun cinteropBinDifferentHome() {
    val paths = KoltPaths("/root")

    assertEquals("/root/.kolt/toolchains/konanc/2.1.0/bin/cinterop", paths.cinteropBin("2.1.0"))
  }

  @Test
  fun cinteropBinSharesKonancDirectory() {
    val paths = KoltPaths("/home/user")
    val version = "2.3.20"
    val konancDir = paths.konancPath(version)
    assertEquals("$konancDir/bin/cinterop", paths.cinteropBin(version))
  }

  @Test
  fun daemonDirSegmentedByProjectHashAndKotlinVersion() {
    val paths = KoltPaths("/home/user")
    assertEquals("/home/user/.kolt/daemon/abc123/2.3.20", paths.daemonDir("abc123", "2.3.20"))
  }

  @Test
  fun daemonSocketPathSegmentedByKotlinVersion() {
    val paths = KoltPaths("/home/user")
    assertEquals(
      "/home/user/.kolt/daemon/abc123/2.3.10/jvm-compiler-daemon.sock",
      paths.daemonSocketPath("abc123", "2.3.10"),
    )
  }

  @Test
  fun daemonLogPathSegmentedByKotlinVersion() {
    val paths = KoltPaths("/home/user")
    assertEquals(
      "/home/user/.kolt/daemon/abc123/2.3.0/jvm-compiler-daemon.log",
      paths.daemonLogPath("abc123", "2.3.0"),
    )
  }

  @Test
  fun differentKotlinVersionsProduceDifferentSocketPaths() {
    val paths = KoltPaths("/home/user")
    val a = paths.daemonSocketPath("hash", "2.3.0")
    val b = paths.daemonSocketPath("hash", "2.3.20")
    kotlin.test.assertNotEquals(a, b)
  }

  @Test
  fun toolsBundleDirSegmentedByAliasAndVersion() {
    val paths = KoltPaths("/home/user")
    assertEquals(
      "/home/user/.kolt/tools/bundles/ktlint/1.3.1",
      paths.toolsBundleDir("ktlint", "1.3.1"),
    )
  }

  @Test
  fun toolsBundleDirDifferentHome() {
    val paths = KoltPaths("/root")
    assertEquals(
      "/root/.kolt/tools/bundles/detekt/1.23.6",
      paths.toolsBundleDir("detekt", "1.23.6"),
    )
  }

  @Test
  fun toolsBundleDirDifferentVersionsProduceDifferentDirectories() {
    val paths = KoltPaths("/home/user")
    val a = paths.toolsBundleDir("ktlint", "1.3.0")
    val b = paths.toolsBundleDir("ktlint", "1.3.1")
    kotlin.test.assertNotEquals(a, b)
  }

  @Test
  fun toolsBundleJarPathBuildsUnderToolsBundleDir() {
    val paths = KoltPaths("/home/user")
    assertEquals(
      "/home/user/.kolt/tools/bundles/ktlint/1.3.1/ktlint-cli-1.3.1-all.jar",
      paths.toolsBundleJarPath("ktlint", "1.3.1", "ktlint-cli-1.3.1-all.jar"),
    )
  }

  @Test
  fun toolsBundleJarPathSharesToolsBundleDir() {
    val paths = KoltPaths("/home/user")
    val dir = paths.toolsBundleDir("detekt", "1.23.6")
    assertEquals(
      "$dir/detekt-cli-1.23.6.jar",
      paths.toolsBundleJarPath("detekt", "1.23.6", "detekt-cli-1.23.6.jar"),
    )
  }

  @Test
  fun toolsBundleDirDoesNotCollideWithFlatToolsLayout() {
    // Existing kolt-internal flat layout: ~/.kolt/tools/<filename> (e.g.
    // ktfmt-0.54-jar-with-dependencies.jar).
    // tools_bundles/<alias>/<version>/ must live under a separate subdir so that a user-declared
    // alias matching an internal tool name (e.g. "ktfmt") cannot collide with the flat artifact.
    val paths = KoltPaths("/home/user")
    val bundleDir = paths.toolsBundleDir("ktfmt", "0.54")
    val flatArtifact = "${paths.toolsDir}/ktfmt-0.54-jar-with-dependencies.jar"
    kotlin.test.assertNotEquals(bundleDir, flatArtifact)
    kotlin.test.assertTrue(bundleDir.startsWith("${paths.toolsDir}/bundles/"))
  }

  @Test
  fun koltLocalTomlConstantNamesOverlayFile() {
    assertEquals("kolt.local.toml", KOLT_LOCAL_TOML)
  }
}
