package keel

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
}
