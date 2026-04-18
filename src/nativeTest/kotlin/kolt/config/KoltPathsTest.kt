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

        assertEquals("/home/user/.kolt/toolchains/konanc/2.3.20/bin/cinterop", paths.cinteropBin("2.3.20"))
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
        assertEquals(
            "/home/user/.kolt/daemon/abc123/2.3.20",
            paths.daemonDir("abc123", "2.3.20"),
        )
    }

    @Test
    fun daemonSocketPathSegmentedByKotlinVersion() {
        val paths = KoltPaths("/home/user")
        assertEquals(
            "/home/user/.kolt/daemon/abc123/2.3.10/daemon.sock",
            paths.daemonSocketPath("abc123", "2.3.10"),
        )
    }

    @Test
    fun daemonLogPathSegmentedByKotlinVersion() {
        val paths = KoltPaths("/home/user")
        assertEquals(
            "/home/user/.kolt/daemon/abc123/2.3.0/daemon.log",
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
}
