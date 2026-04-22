package kolt.build

import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.testConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdir
import platform.posix.mkdtemp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalForeignApi::class)
class RuntimeClasspathManifestTest {

    private var originalCwd: String = ""
    private var tmpDir: String = ""

    @BeforeTest
    fun setUp() {
        originalCwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
        }
        tmpDir = createTempDir("kolt-runtime-classpath-manifest-")
        check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
        // Ensure build dir exists for writeFileAsString.
        check(mkdir(BUILD_DIR, 0b111111101u) == 0) { "mkdir $BUILD_DIR failed" }
    }

    @AfterTest
    fun tearDown() {
        chdir(originalCwd)
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    @Test
    fun outputRuntimeClasspathPathUsesBuildDirAndProjectName() {
        assertEquals(
            "build/my-app-runtime.classpath",
            outputRuntimeClasspathPath(testConfig())
        )
        assertEquals(
            "build/hello-world-runtime.classpath",
            outputRuntimeClasspathPath(testConfig(name = "hello-world"))
        )
    }

    @Test
    fun writeRuntimeClasspathManifestSortsByFileNameAndExcludesSelfJar() {
        val config = testConfig(name = "demo")
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar",
                groupArtifactVersion = "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3"
            ),
            ResolvedJar(
                cachePath = "/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/ktoml-core-jvm-0.7.1.jar",
                groupArtifactVersion = "com.akuleshov7:ktoml-core-jvm:0.7.1"
            ),
            ResolvedJar(
                cachePath = "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar",
                groupArtifactVersion = "org.jetbrains:kotlin-stdlib:2.3.20"
            ),
            // Self jar: must not appear in the manifest even if provided.
            ResolvedJar(
                cachePath = outputJarPath(config),
                groupArtifactVersion = "self:${config.name}:${config.version}"
            )
        )

        writeRuntimeClasspathManifest(config, jars).getOrElse { error("write failed: $it") }

        val content = readFileAsString(outputRuntimeClasspathPath(config))
            .getOrElse { error("read failed: $it") }
        val lines = content.split('\n')
        assertEquals(
            listOf(
                "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar",
                "/cache/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar",
                "/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/ktoml-core-jvm-0.7.1.jar"
            ),
            lines
        )
    }

    @Test
    fun writeRuntimeClasspathManifestTieBreaksByGroupArtifactVersion() {
        val config = testConfig(name = "tiebreak")
        // Same file name ("shaded-1.0.0.jar") — tiebreak by GAV lexicographic.
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/z.example/shaded/1.0.0/shaded-1.0.0.jar",
                groupArtifactVersion = "z.example:shaded:1.0.0"
            ),
            ResolvedJar(
                cachePath = "/cache/a.example/shaded/1.0.0/shaded-1.0.0.jar",
                groupArtifactVersion = "a.example:shaded:1.0.0"
            )
        )

        writeRuntimeClasspathManifest(config, jars).getOrElse { error("write failed: $it") }

        val content = readFileAsString(outputRuntimeClasspathPath(config))
            .getOrElse { error("read failed: $it") }
        val lines = content.split('\n')
        assertEquals(
            listOf(
                "/cache/a.example/shaded/1.0.0/shaded-1.0.0.jar",
                "/cache/z.example/shaded/1.0.0/shaded-1.0.0.jar"
            ),
            lines
        )
    }

    @Test
    fun writeRuntimeClasspathManifestUsesLfWithoutTrailingNewline() {
        val config = testConfig(name = "fmt")
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/a/a/1/a-1.jar",
                groupArtifactVersion = "a:a:1"
            ),
            ResolvedJar(
                cachePath = "/cache/b/b/1/b-1.jar",
                groupArtifactVersion = "b:b:1"
            )
        )

        writeRuntimeClasspathManifest(config, jars).getOrElse { error("write failed: $it") }

        val content = readFileAsString(outputRuntimeClasspathPath(config))
            .getOrElse { error("read failed: $it") }
        val bytes = content.encodeToByteArray()
        val expected = "/cache/a/a/1/a-1.jar\n/cache/b/b/1/b-1.jar".encodeToByteArray()
        assertEquals(expected.size, bytes.size)
        for (i in expected.indices) {
            assertEquals(expected[i], bytes[i], "byte mismatch at index $i")
        }
        // Explicit invariants: LF-only and no trailing newline.
        assertFalse(content.contains('\r'), "manifest must not contain CR")
        assertFalse(content.endsWith('\n'), "manifest must not end with a newline")
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
