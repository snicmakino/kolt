package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalForeignApi::class)
class Sha256Test {

    @Test
    fun computeSha256OfKnownContent() {
        val path = "/tmp/kolt_test_sha256.txt"
        writeTestFile(path, "hello")
        try {
            val result = computeSha256(path)
            // SHA256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                assertNotNull(result.get())
            )
        } finally {
            remove(path)
        }
    }

    @Test
    fun computeSha256OfEmptyFile() {
        val path = "/tmp/kolt_test_sha256_empty.txt"
        writeTestFile(path, "")
        try {
            val result = computeSha256(path)
            // SHA256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                assertNotNull(result.get())
            )
        } finally {
            remove(path)
        }
    }

    @Test
    fun computeSha256OfNonExistentFileReturnsErr() {
        val result = computeSha256("/tmp/kolt_nonexistent_sha256.txt")
        assertIs<Sha256Error>(result.getError())
    }

    private fun writeTestFile(path: String, content: String) {
        val fp = platform.posix.fopen(path, "w") ?: error("could not create test file: $path")
        if (content.isNotEmpty()) {
            platform.posix.fputs(content, fp)
        }
        platform.posix.fclose(fp)
    }
}
