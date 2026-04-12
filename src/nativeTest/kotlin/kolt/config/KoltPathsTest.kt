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
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // Then: toolchainsDir is under ~/.kolt/toolchains
        assertEquals("/home/user/.kolt/toolchains", paths.toolchainsDir)
    }

    @Test
    fun kotlincPathBuildsVersionedDirectory() {
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // When: kotlincPath is called for a specific version
        val path = paths.kotlincPath("2.1.0")

        // Then: path points to the versioned directory under toolchains
        assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0", path)
    }

    @Test
    fun kotlincBinBuildsVersionedBinPath() {
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // When: kotlincBin is called for a specific version
        val bin = paths.kotlincBin("2.1.0")

        // Then: bin path points to bin/kotlinc inside the versioned directory
        assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", bin)
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

    // --- jdkPath ---

    @Test
    fun jdkPathBuildsVersionedDirectory() {
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // When: jdkPath is called for a specific major version
        val path = paths.jdkPath("21")

        // Then: path points to the versioned directory under toolchains/jdk
        assertEquals("/home/user/.kolt/toolchains/jdk/21", path)
    }

    @Test
    fun jdkPathDifferentVersion() {
        val paths = KoltPaths("/home/alice")

        assertEquals("/home/alice/.kolt/toolchains/jdk/17", paths.jdkPath("17"))
    }

    // --- javaBin ---

    @Test
    fun javaBinBuildsVersionedBinPath() {
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // When: javaBin is called for a specific major version
        val bin = paths.javaBin("21")

        // Then: bin path points to bin/java inside the versioned directory
        assertEquals("/home/user/.kolt/toolchains/jdk/21/bin/java", bin)
    }

    @Test
    fun javaBinDifferentHome() {
        val paths = KoltPaths("/root")

        assertEquals("/root/.kolt/toolchains/jdk/17/bin/java", paths.javaBin("17"))
    }

    // --- jarBin ---

    @Test
    fun jarBinBuildsVersionedBinPath() {
        // Given: KoltPaths with a known home
        val paths = KoltPaths("/home/user")

        // When: jarBin is called for a specific major version
        val bin = paths.jarBin("21")

        // Then: bin path points to bin/jar inside the versioned directory
        assertEquals("/home/user/.kolt/toolchains/jdk/21/bin/jar", bin)
    }

    @Test
    fun jarBinDifferentHome() {
        val paths = KoltPaths("/root")

        assertEquals("/root/.kolt/toolchains/jdk/17/bin/jar", paths.jarBin("17"))
    }

    // --- konancPath ---

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

    // --- konancBin ---

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
        // Given: jdkPath, javaBin, and jarBin for the same version
        val paths = KoltPaths("/home/user")

        // Then: javaBin and jarBin are both under jdkPath
        val base = paths.jdkPath("21")
        assertEquals("$base/bin/java", paths.javaBin("21"))
        assertEquals("$base/bin/jar", paths.jarBin("21"))
    }
}
