package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.BUILD_DIR
import kolt.build.ResolvedJar
import kolt.build.outputJarPath
import kolt.build.outputRuntimeClasspathPath
import kolt.config.ConfigError
import kolt.config.parseConfig
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.testConfig
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
import platform.posix.mkdir
import platform.posix.mkdtemp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration matrix for ADR 0027 §4 / Req 2.1 / 2.7: the JVM `kind = "app"`
 * build path emits `build/<name>-runtime.classpath`; every other kind/target
 * combination (JVM lib, native app, native lib) does not — and removes a
 * stale manifest inherited from a previous `kind = "app"` build.
 *
 * The decision point is the pure helper `handleRuntimeClasspathManifest`,
 * which `doBuild` / `doNativeBuild` invoke once per build after the artifact
 * step. Exercising the helper directly with temp-dir fixtures captures the
 * observable effects (file present / absent / removed) without requiring a
 * live JDK + kotlinc; design.md §Testing Strategy §Integration maps these
 * cases onto "manifest emit matrix" and "stale manifest cleanup on kind
 * flip".
 *
 * Req 1.5 regression (`kind = "app"` without `main`) is pinned as a parser
 * smoke: `parseConfig` must reject the malformed `kolt.toml` before any
 * build machinery runs.
 */
@OptIn(ExperimentalForeignApi::class)
class JvmAppBuildTest {

    private var originalCwd: String = ""
    private var tmpDir: String = ""

    @BeforeTest
    fun setUp() {
        originalCwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
        }
        tmpDir = createTempDir("kolt-jvm-app-build-")
        check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
        check(mkdir(BUILD_DIR, 0b111111101u) == 0) { "mkdir $BUILD_DIR failed" }
    }

    @AfterTest
    fun tearDown() {
        chdir(originalCwd)
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    // Req 2.1 / 2.6 / ADR 0027 §4 row 1: JVM kind=app emits the manifest
    // with the resolved jar list (excluding self), alphabetical by file
    // name. Observed via the pure decision helper driven from doBuild.
    @Test
    fun jvmAppEmitsManifestWithResolvedJars() {
        val config = testConfig(name = "myapp", target = "jvm").copy(kind = "app")
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar",
                groupArtifactVersion = "org.jetbrains:kotlin-stdlib:2.3.20"
            ),
            ResolvedJar(
                cachePath = "/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/ktoml-core-jvm-0.7.1.jar",
                groupArtifactVersion = "com.akuleshov7:ktoml-core-jvm:0.7.1"
            )
        )

        handleRuntimeClasspathManifest(config, jars).getOrElse { error("emit failed: $it") }

        val manifestPath = outputRuntimeClasspathPath(config)
        assertTrue(fileExists(manifestPath), "JVM kind=app must emit $manifestPath")
        val content = readFileAsString(manifestPath).getOrElse { error("read failed: $it") }
        // "kotlin-stdlib-2.3.20.jar" sorts before "ktoml-core-jvm-0.7.1.jar"
        // by file name (lexicographic on the last path component).
        assertEquals(
            listOf(
                "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar",
                "/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/ktoml-core-jvm-0.7.1.jar"
            ),
            content.split('\n'),
            "manifest must list jars sorted by file name"
        )
    }

    // Req 2.1 row 1 corner case: an empty resolver outcome still produces
    // an empty manifest. The stitcher then iterates zero deps — a valid
    // (if unusual) shape.
    @Test
    fun jvmAppWithNoDependenciesEmitsEmptyManifest() {
        val config = testConfig(name = "noop", target = "jvm").copy(kind = "app")

        handleRuntimeClasspathManifest(config, emptyList()).getOrElse { error("emit failed: $it") }

        val manifestPath = outputRuntimeClasspathPath(config)
        assertTrue(fileExists(manifestPath), "empty deps still emit a (zero-byte) manifest")
        val content = readFileAsString(manifestPath).getOrElse { error("read failed: $it") }
        assertEquals("", content, "no deps → empty content (no trailing newline)")
    }

    // Req 2.7 / ADR 0027 §4 row 2: JVM kind=lib must not emit the manifest.
    // If a stale manifest from a previous kind=app build is sitting on
    // disk, it must be removed so assemble-dist.sh does not pick it up.
    @Test
    fun jvmLibDoesNotEmitManifestAndRemovesStaleOne() {
        val config = testConfig(name = "mylib", target = "jvm")
            .let { it.copy(kind = "lib", build = it.build.copy(main = null)) }
        val manifestPath = outputRuntimeClasspathPath(config)
        // Seed a stale manifest as if a previous kind=app build ran here.
        writeFileAsString(manifestPath, "/stale/a.jar\n/stale/b.jar")
            .getError()?.let { error("seed failed: $it") }
        assertTrue(fileExists(manifestPath), "precondition: seeded stale manifest must exist")

        handleRuntimeClasspathManifest(config, emptyList()).getOrElse { error("cleanup failed: $it") }

        assertFalse(fileExists(manifestPath), "JVM kind=lib must remove a stale manifest")
    }

    // Req 2.7 / ADR 0027 §4 rows 3 + 4: native targets (app and lib) never
    // emit the manifest. Any stale file from a prior kind=app JVM build in
    // the same build/ directory is removed.
    @Test
    fun nativeTargetNeverEmitsManifestAndRemovesStaleOne() {
        val matrix = listOf(
            testConfig(name = "nativeapp", target = "linuxX64").copy(kind = "app"),
            testConfig(name = "nativelib", target = "linuxX64")
                .let { it.copy(kind = "lib", build = it.build.copy(main = null)) },
        )
        for (config in matrix) {
            val manifestPath = outputRuntimeClasspathPath(config)
            writeFileAsString(manifestPath, "/stale/x.jar")
                .getError()?.let { error("seed failed for ${config.name}: $it") }
            assertTrue(fileExists(manifestPath), "precondition: seed must exist for ${config.name}")

            handleRuntimeClasspathManifest(config, emptyList())
                .getOrElse { error("cleanup failed for ${config.name}: $it") }

            assertFalse(
                fileExists(manifestPath),
                "native target (${config.name}/${config.build.target}/${config.kind}) must remove stale manifest"
            )
        }
    }

    // Req 2.7 flip: running kind=app first writes the manifest; switching
    // kolt.toml to kind=lib and rebuilding removes it. Pin the pair so a
    // regression in the cleanup arm shows up even when the emit arm keeps
    // working.
    @Test
    fun flippingKindFromAppToLibRemovesTheManifestOnRebuild() {
        val appConfig = testConfig(name = "flip", target = "jvm").copy(kind = "app")
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar",
                groupArtifactVersion = "org.jetbrains:kotlin-stdlib:2.3.20"
            )
        )
        handleRuntimeClasspathManifest(appConfig, jars).getOrElse { error("emit failed: $it") }
        val manifestPath = outputRuntimeClasspathPath(appConfig)
        assertTrue(fileExists(manifestPath), "precondition: first build (app) must emit manifest")

        val libConfig = appConfig.copy(kind = "lib", build = appConfig.build.copy(main = null))
        handleRuntimeClasspathManifest(libConfig, emptyList())
            .getOrElse { error("cleanup failed: $it") }

        assertFalse(fileExists(manifestPath), "kind flip app→lib must remove the manifest")
    }

    // Req 2.3 defence-in-depth: even if the resolver accidentally surfaces
    // the self jar (`build/<name>.jar`), the emit helper filters it out —
    // the manifest must never list the project's own artifact.
    @Test
    fun selfJarIsExcludedFromEmittedManifest() {
        val config = testConfig(name = "self-excl", target = "jvm").copy(kind = "app")
        val jars = listOf(
            ResolvedJar(
                cachePath = "/cache/com.example/lib/1.0/lib-1.0.jar",
                groupArtifactVersion = "com.example:lib:1.0"
            ),
            ResolvedJar(
                cachePath = outputJarPath(config),
                groupArtifactVersion = "self:${config.name}:${config.version}"
            ),
        )

        handleRuntimeClasspathManifest(config, jars).getOrElse { error("emit failed: $it") }

        val manifestPath = outputRuntimeClasspathPath(config)
        val content = readFileAsString(manifestPath).getOrElse { error("read failed: $it") }
        assertEquals(
            listOf("/cache/com.example/lib/1.0/lib-1.0.jar"),
            content.split('\n'),
            "self jar must be filtered out of the manifest"
        )
    }

    // Req 1.5: a schema-violating kolt.toml (`kind = "app"` without
    // `[build] main`) is rejected by the parser before any build stage
    // runs. The existing parser error (`APP_WITHOUT_MAIN_ERROR`) is the
    // user-facing stop signal; no additional wiring is required from this
    // task, but this test pins that the parser still refuses the shape
    // at the config boundary — i.e. `doBuild`'s entry via
    // `loadProjectConfig` cannot reach the manifest step.
    @Test
    fun parserRejectsAppKindWithoutMainBeforeBuildRuns() {
        val malformed = """
            name = "broken"
            version = "0.1.0"
            kind = "app"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            sources = ["src"]
        """.trimIndent()

        val error = assertIs<ConfigError.ParseFailed>(
            assertNotNull(parseConfig(malformed).getError(), "parser must reject app without main")
        )
        assertContains(error.message, "[build] main is required for kind = \"app\"")
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
