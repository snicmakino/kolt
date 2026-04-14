package kolt.infra

import com.github.michaelbull.result.get
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelfExeTest {

    @Test
    fun readSelfExeReturnsAbsolutePathToExistingFile() {
        // In a nativeTest binary, /proc/self/exe resolves to the test
        // kexe itself. We do not assert the exact suffix (the Gradle
        // test binary name is a moving target across Kotlin versions),
        // only that the returned value is a non-empty absolute path
        // that points at a real file on disk.
        val path = assertNotNull(readSelfExe().get())
        assertTrue(path.startsWith("/"), "expected absolute path, got: $path")
        assertTrue(fileExists(path), "expected file to exist at: $path")
    }
}
