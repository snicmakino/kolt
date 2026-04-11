package keel.config

import kotlin.test.Test
import kotlin.test.assertEquals

class KeelPathsTest {

    @Test
    fun cacheBaseConstructedFromHome() {
        val paths = KeelPaths("/home/user")
        assertEquals("/home/user/.keel/cache", paths.cacheBase)
    }

    @Test
    fun toolsDirConstructedFromHome() {
        val paths = KeelPaths("/home/user")
        assertEquals("/home/user/.keel/tools", paths.toolsDir)
    }

    @Test
    fun toolchainsDirConstructedFromHome() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // Then: toolchainsDir is under ~/.keel/toolchains
        assertEquals("/home/user/.keel/toolchains", paths.toolchainsDir)
    }

    @Test
    fun kotlincPathBuildsVersionedDirectory() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // When: kotlincPath is called for a specific version
        val path = paths.kotlincPath("2.1.0")

        // Then: path points to the versioned directory under toolchains
        assertEquals("/home/user/.keel/toolchains/kotlinc/2.1.0", path)
    }

    @Test
    fun kotlincBinBuildsVersionedBinPath() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // When: kotlincBin is called for a specific version
        val bin = paths.kotlincBin("2.1.0")

        // Then: bin path points to bin/kotlinc inside the versioned directory
        assertEquals("/home/user/.keel/toolchains/kotlinc/2.1.0/bin/kotlinc", bin)
    }

    @Test
    fun kotlincPathDifferentVersion() {
        val paths = KeelPaths("/home/alice")

        assertEquals("/home/alice/.keel/toolchains/kotlinc/2.3.20", paths.kotlincPath("2.3.20"))
    }

    @Test
    fun kotlincBinDifferentHome() {
        val paths = KeelPaths("/root")

        assertEquals("/root/.keel/toolchains/kotlinc/2.3.20/bin/kotlinc", paths.kotlincBin("2.3.20"))
    }

    // --- jdkPath ---

    @Test
    fun jdkPathBuildsVersionedDirectory() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // When: jdkPath is called for a specific major version
        val path = paths.jdkPath("21")

        // Then: path points to the versioned directory under toolchains/jdk
        assertEquals("/home/user/.keel/toolchains/jdk/21", path)
    }

    @Test
    fun jdkPathDifferentVersion() {
        val paths = KeelPaths("/home/alice")

        assertEquals("/home/alice/.keel/toolchains/jdk/17", paths.jdkPath("17"))
    }

    // --- javaBin ---

    @Test
    fun javaBinBuildsVersionedBinPath() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // When: javaBin is called for a specific major version
        val bin = paths.javaBin("21")

        // Then: bin path points to bin/java inside the versioned directory
        assertEquals("/home/user/.keel/toolchains/jdk/21/bin/java", bin)
    }

    @Test
    fun javaBinDifferentHome() {
        val paths = KeelPaths("/root")

        assertEquals("/root/.keel/toolchains/jdk/17/bin/java", paths.javaBin("17"))
    }

    // --- jarBin ---

    @Test
    fun jarBinBuildsVersionedBinPath() {
        // Given: KeelPaths with a known home
        val paths = KeelPaths("/home/user")

        // When: jarBin is called for a specific major version
        val bin = paths.jarBin("21")

        // Then: bin path points to bin/jar inside the versioned directory
        assertEquals("/home/user/.keel/toolchains/jdk/21/bin/jar", bin)
    }

    @Test
    fun jarBinDifferentHome() {
        val paths = KeelPaths("/root")

        assertEquals("/root/.keel/toolchains/jdk/17/bin/jar", paths.jarBin("17"))
    }

    @Test
    fun jdkJavaBinAndJarBinShareSameVersionedDirectory() {
        // Given: jdkPath, javaBin, and jarBin for the same version
        val paths = KeelPaths("/home/user")

        // Then: javaBin and jarBin are both under jdkPath
        val base = paths.jdkPath("21")
        assertEquals("$base/bin/java", paths.javaBin("21"))
        assertEquals("$base/bin/jar", paths.jarBin("21"))
    }
}
