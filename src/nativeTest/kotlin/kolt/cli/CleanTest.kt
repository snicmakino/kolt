package kolt.cli

import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CleanTest {
    private val testDir = "build_test_clean"

    @AfterTest
    fun cleanup() {
        if (fileExists(testDir)) {
            removeDirectoryRecursive(testDir)
        }
    }

    @Test
    fun removeDirectoryDeletesDirectoryAndContents() {
        ensureDirectoryRecursive("$testDir/sub")
        writeFileAsString("$testDir/file.txt", "hello")
        writeFileAsString("$testDir/sub/nested.txt", "world")
        assertTrue(fileExists(testDir))

        val result = removeDirectoryRecursive(testDir)
        assertNotNull(result.get())
        assertFalse(fileExists(testDir))
    }

    @Test
    fun removeDirectoryReturnsErrorForNonExistentPath() {
        val result = removeDirectoryRecursive("nonexistent_dir_xyz")
        val error = result.getError()
        assertNotNull(error)
        assertEquals("nonexistent_dir_xyz", error.path)
    }
}
