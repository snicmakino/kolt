package keel

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LockfileTest {

    @Test
    fun parseValidLockfile() {
        val json = """
            {
                "version": 1,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core": {
                        "version": "1.9.0",
                        "sha256": "abc123def456"
                    }
                }
            }
        """.trimIndent()
        val lockfile = assertNotNull(parseLockfile(json).get())
        assertEquals(1, lockfile.version)
        assertEquals("2.1.0", lockfile.kotlin)
        assertEquals("17", lockfile.jvmTarget)
        assertEquals(1, lockfile.dependencies.size)
        val entry = assertNotNull(lockfile.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
        assertEquals("1.9.0", entry.version)
        assertEquals("abc123def456", entry.sha256)
    }

    @Test
    fun parseEmptyDependencies() {
        val json = """
            {
                "version": 1,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {}
            }
        """.trimIndent()
        val lockfile = assertNotNull(parseLockfile(json).get())
        assertEquals(0, lockfile.dependencies.size)
    }

    @Test
    fun parseUnsupportedVersionReturnsErr() {
        val json = """
            {
                "version": 99,
                "kotlin": "2.1.0",
                "jvm_target": "17",
                "dependencies": {}
            }
        """.trimIndent()
        assertIs<LockfileError.UnsupportedVersion>(parseLockfile(json).getError())
    }

    @Test
    fun parseInvalidJsonReturnsErr() {
        assertIs<LockfileError.ParseFailed>(parseLockfile("not json").getError())
    }

    @Test
    fun serializeLockfileSortsDependencies() {
        val lockfile = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.squareup:okhttp" to LockEntry("4.12.0", "bbb222"),
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" to LockEntry("1.9.0", "aaa111")
            )
        )
        val serialized = serializeLockfile(lockfile)
        val reparsed = assertNotNull(parseLockfile(serialized).get())
        assertEquals(lockfile.version, reparsed.version)
        assertEquals(lockfile.kotlin, reparsed.kotlin)
        assertEquals(lockfile.jvmTarget, reparsed.jvmTarget)
        assertEquals(lockfile.dependencies, reparsed.dependencies)

        // Verify dependency keys are sorted alphabetically
        val comIndex = serialized.indexOf("com.squareup:okhttp")
        val orgIndex = serialized.indexOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        assertTrue(comIndex < orgIndex, "dependencies should be sorted alphabetically")
    }

    @Test
    fun serializeAndParseRoundTrip() {
        val lockfile = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "junit:junit" to LockEntry("4.13.2", "ccc333")
            )
        )
        val serialized = serializeLockfile(lockfile)
        val reparsed = assertNotNull(parseLockfile(serialized).get())
        assertEquals(lockfile, reparsed)
    }
}
