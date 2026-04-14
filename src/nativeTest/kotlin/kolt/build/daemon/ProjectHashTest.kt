package kolt.build.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectHashTest {

    @Test
    fun hashIsSixteenLowercaseHex() {
        val hash = projectHashOf("/home/user/projects/foo")
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" }, "got: $hash")
    }

    @Test
    fun hashIsDeterministic() {
        assertEquals(
            projectHashOf("/home/user/projects/foo"),
            projectHashOf("/home/user/projects/foo"),
        )
    }

    @Test
    fun differentPathsProduceDifferentHashes() {
        assertNotEquals(
            projectHashOf("/home/user/projects/foo"),
            projectHashOf("/home/user/projects/bar"),
        )
    }

    @Test
    fun trailingSlashChangesHash() {
        // projectHashOf does not normalise — callers are responsible
        // for passing a canonical absolute path. Documenting the
        // contract in a test so the next person does not quietly add
        // normalisation and break daemon lookups mid-build.
        assertNotEquals(
            projectHashOf("/home/user/projects/foo"),
            projectHashOf("/home/user/projects/foo/"),
        )
    }
}
