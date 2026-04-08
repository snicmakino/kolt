package keel

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DownloaderTest {

    @Test
    fun downloadSmallFileFromMavenCentral() {
        val destPath = "/tmp/keel_test_download.xml"
        remove(destPath)
        try {
            // maven-metadata.xml is small and stable, suitable for testing
            val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
            val result = downloadFile(url, destPath)
            assertNotNull(result.get())
            assertTrue(fileExists(destPath))
            val content = assertNotNull(readFileAsString(destPath).get())
            assertTrue(content.contains("<artifactId>junit</artifactId>"))
        } finally {
            remove(destPath)
        }
    }

    @Test
    fun downloadToNonExistentDirectoryReturnsErr() {
        val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
        val result = downloadFile(url, "/nonexistent_root/subdir/file.xml")
        assertIs<DownloadError>(result.getError())
    }

    @Test
    fun downloadNonExistentUrlReturnsErr() {
        val destPath = "/tmp/keel_test_download_404.txt"
        try {
            val url = "https://repo1.maven.org/maven2/nonexistent/nonexistent/0.0.0/nonexistent-0.0.0.jar"
            val result = downloadFile(url, destPath)
            assertIs<DownloadError>(result.getError())
        } finally {
            remove(destPath)
        }
    }
}
