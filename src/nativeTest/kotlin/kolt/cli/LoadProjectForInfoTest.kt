package kolt.cli

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalForeignApi::class)
class LoadProjectForInfoTest {
    private var originalCwd: String = ""
    private var tmpDir: String = ""

    @BeforeTest
    fun setUp() {
        originalCwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
        }
        tmpDir = createTempDir("kolt-info-load-")
        check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
    }

    @AfterTest
    fun tearDown() {
        chdir(originalCwd)
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    @Test
    fun returnsNotAProjectWhenKoltTomlMissing() {
        val outcome = loadProjectForInfo()
        assertEquals(ProjectLoad.NotAProject, outcome)
    }

    @Test
    fun returnsLoadedWhenKoltTomlParses() {
        writeFileAsString(KOLT_TOML, VALID_TOML).getOrElse { error("seed failed: $it") }

        val outcome = loadProjectForInfo()

        val loaded = assertIs<ProjectLoad.Loaded>(outcome)
        assertEquals("my-app", loaded.config.name)
    }

    @Test
    fun returnsParseFailedWhenKoltTomlBroken() {
        writeFileAsString(KOLT_TOML, INVALID_TOML).getOrElse { error("seed failed: $it") }

        val outcome = loadProjectForInfo()

        val failed = assertIs<ProjectLoad.ParseFailed>(outcome)
        assertTrue(
            failed.message.isNotBlank(),
            "must surface a non-empty error so the caller can print it",
        )
    }

    @Test
    fun doInfoExitsWithConfigErrorWhenKoltTomlBroken() {
        writeFileAsString(KOLT_TOML, INVALID_TOML).getOrElse { error("seed failed: $it") }

        val exit = doInfo(emptyList()).fold(success = { null }, failure = { it })

        assertEquals(EXIT_CONFIG_ERROR, exit)
    }

    @Test
    fun doInfoExitsWithConfigErrorWhenKoltTomlBrokenInJsonMode() {
        writeFileAsString(KOLT_TOML, INVALID_TOML).getOrElse { error("seed failed: $it") }

        val exit = doInfo(listOf("--format=json")).fold(success = { null }, failure = { it })

        assertEquals(EXIT_CONFIG_ERROR, exit)
    }

    @Test
    fun doInfoExitsOkWhenKoltTomlMissing() {
        val outcome = doInfo(emptyList())

        outcome.getOrElse { fail("expected Ok, got exit=$it") }
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
            return result.toKString()
        }
    }

    companion object {
        private val VALID_TOML = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]
        """.trimIndent()

        private val INVALID_TOML = """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"
        """.trimIndent()
    }
}
