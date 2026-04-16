package kolt.build

import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeAndCapture
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.homeDirectory
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.posix.getpid

class CinteropSmokeTest {

    // Debian/Ubuntu multiarch, default /usr/include, and /usr/local/include
    private val curlIncludeCandidates = listOf(
        "/usr/include/x86_64-linux-gnu",
        "/usr/include",
        "/usr/local/include",
    )

    private val curlLibCandidates = listOf(
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
        "/usr/local/lib",
    )

    @Test
    fun libcurlCinteropPipelineProducesRunningExecutable() {
        val home = homeDirectory().get() ?: error("HOME not set")
        val paths = KoltPaths(home)
        val kotlinVersion = "2.1.0"
        val cinteropBin = paths.cinteropBin(kotlinVersion)
        val konancBin = paths.konancBin(kotlinVersion)

        if (!fileExists(cinteropBin) || !fileExists(konancBin)) {
            println("SKIP: managed konanc toolchain not found at ${paths.konancPath(kotlinVersion)}")
            return
        }

        val curlIncludeDir = curlIncludeCandidates.firstOrNull { fileExists("$it/curl/curl.h") }
        if (curlIncludeDir == null) {
            println(
                "SKIP: libcurl headers (curl/curl.h) not found in any of " +
                    curlIncludeCandidates.joinToString()
            )
            return
        }
        val includeDirs = linkedSetOf("/usr/include", curlIncludeDir).toList()
        val compilerOpts = includeDirs.joinToString(" ") { "-I$it" }

        val curlLibDir = curlLibCandidates.firstOrNull { fileExists("$it/libcurl.so") }
        if (curlLibDir == null) {
            println(
                "SKIP: libcurl.so not found in any of " + curlLibCandidates.joinToString()
            )
            return
        }
        val linkerOpts = "-L$curlLibDir -lcurl"

        val tmpDir = "/tmp/kolt-e2e-cinterop-${getpid()}"
        val buildDir = "$tmpDir/build"
        ensureDirectoryRecursive(buildDir)

        try {
            val defFile = "$tmpDir/libcurl.def"
            writeFileAsString(
                defFile,
                """
                headers = curl/curl.h
                compilerOpts.linux = $compilerOpts
                linkerOpts.linux = $linkerOpts
                """.trimIndent()
            )

            val mainFile = "$tmpDir/Main.kt"
            writeFileAsString(
                mainFile,
                """
                import libcurl.*
                import kotlinx.cinterop.ExperimentalForeignApi
                import kotlinx.cinterop.toKString

                @OptIn(ExperimentalForeignApi::class)
                fun main() {
                    val version = curl_version()?.toKString() ?: "unknown"
                    println(version)
                }
                """.trimIndent()
            )

            val klibBase = "$buildDir/libcurl"
            val cinteropResult = executeCommand(listOf(cinteropBin, "-def", defFile, "-o", klibBase))
            assertNotNull(
                cinteropResult.get(),
                "cinterop failed to generate klib: $cinteropResult"
            )
            val klibFile = "$klibBase.klib"
            assertTrue(fileExists(klibFile), "Expected .klib at $klibFile after cinterop")

            val appKlibBase = "$buildDir/smoke-klib"
            val stage1Result = executeCommand(
                listOf(
                    konancBin, mainFile,
                    "-p", "library", "-nopack",
                    "-l", klibFile,
                    "-o", appKlibBase
                )
            )
            assertNotNull(
                stage1Result.get(),
                "konanc stage 1 (compile to klib) failed: $stage1Result"
            )
            assertTrue(fileExists(appKlibBase), "Expected project klib at $appKlibBase")

            val exeBase = "$buildDir/smoke"
            val stage2Result = executeCommand(
                listOf(
                    konancBin,
                    "-p", "program",
                    "-l", klibFile,
                    "-Xinclude=$appKlibBase",
                    "-o", exeBase
                )
            )
            assertNotNull(
                stage2Result.get(),
                "konanc stage 2 (link to kexe) failed: $stage2Result"
            )
            val exeFile = "$exeBase.kexe"
            assertTrue(fileExists(exeFile), "Expected executable at $exeFile after link")

            val runResult = executeAndCapture("$exeFile 2>&1")
            val output = assertNotNull(runResult.get(), "executable run failed: $runResult")
            assertTrue(
                output.contains("curl/"),
                "Expected curl version string (e.g. 'curl/8.x.y') in output, got: $output"
            )
        } finally {
            removeDirectoryRecursive(tmpDir)
        }
    }
}
