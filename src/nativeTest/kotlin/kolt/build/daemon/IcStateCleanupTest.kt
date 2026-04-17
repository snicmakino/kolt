package kolt.build.daemon

import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcStateCleanupTest {
    private val homeDir = "build_test_ic_cleanup_home"
    private val projectPath = "/work/some-project"

    @AfterTest
    fun cleanup() {
        if (fileExists(homeDir)) removeDirectoryRecursive(homeDir)
    }

    @Test
    fun projectIdMatchesDaemonAlgorithm() {
        // Pin the algorithm to IcStateLayout.projectIdFor (32 hex chars,
        // first 16 bytes of SHA-256 of the absolute path string).
        val id = daemonIcProjectIdOf("/abs/path")
        assertEquals(32, id.length)
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' }, "id must be lowercase hex, got: $id")
        // Same input on the daemon side produces the same value
        // (kept in sync via IcStateLayoutTest's "uses the absolute path string verbatim").
        assertEquals("6d80187b454107127bf995f2c31a2a92", daemonIcProjectIdOf("/abs/path"))
    }

    @Test
    fun removesIcStateForProjectAcrossAllKotlinVersions() {
        val paths = KoltPaths(homeDir)
        val projectId = daemonIcProjectIdOf(projectPath)
        val v1Dir = "${paths.daemonIcDir}/2.3.20/$projectId"
        val v2Dir = "${paths.daemonIcDir}/2.4.0/$projectId"
        val otherProjectDir = "${paths.daemonIcDir}/2.3.20/0123456789abcdef0123456789abcdef"
        ensureDirectoryRecursive(v1Dir)
        ensureDirectoryRecursive(v2Dir)
        ensureDirectoryRecursive(otherProjectDir)
        writeFileAsString("$v1Dir/marker", "x")
        writeFileAsString("$v2Dir/marker", "y")
        writeFileAsString("$otherProjectDir/marker", "z")

        cleanDaemonIcStateForProject(paths, projectPath)

        assertFalse(fileExists(v1Dir), "v1 IC state for this project must be removed")
        assertFalse(fileExists(v2Dir), "v2 IC state for this project must be removed")
        assertTrue(fileExists(otherProjectDir), "other project's IC state must be left intact")
    }

    @Test
    fun noOpWhenIcRootMissing() {
        val paths = KoltPaths(homeDir)

        val result = cleanDaemonIcStateForProject(paths, projectPath)

        assertNull(result.getError())
        assertFalse(fileExists(paths.daemonIcDir))
    }
}
