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
}
