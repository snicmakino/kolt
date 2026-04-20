package kolt.build.nativedaemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.net.UnixSocket
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.nativedaemon.wire.FrameCodec
import kolt.nativedaemon.wire.Message
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// End-to-end validation of the native compiler daemon pipeline (ADR 0024).
// Exercises the JVM daemon spawn + reflective K2Native.exec + Unix domain
// socket wire protocol through BOTH stages of a real build, then runs the
// produced kexe to confirm it is a working binary.
//
// Opt-in via `KOLT_NATIVE_DAEMON_IT=1`. Requires:
//  - `JAVA_HOME`              : JDK root used to spawn the daemon JVM.
//  - `KOLT_IT_KONAN_HOME`     : konan distribution root containing
//                               `konan/lib/kotlin-native-compiler-embeddable.jar`
//                               and `bin/konanc`. Typically
//                               `$HOME/.kolt/toolchains/konanc/<version>`
//                               after a first kolt native build, or
//                               `$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-<version>`
//                               from a Kotlin/Native distribution.
// The daemon jar itself is resolved via the usual env → libexec →
// dev-fallback chain (`NativeDaemonJarResolver`), so a
// `./gradlew :kolt-native-daemon:shadowJar` is enough when developing
// locally.
@OptIn(ExperimentalForeignApi::class)
class NativeDaemonIntegrationTest {

    @Test
    fun realDaemonCompilesAndLinksFixtureAndProducedKexeRuns() {
        if (!integrationTestsEnabled()) return
        val env = requireEnv()

        val projectDir = "/tmp/kolt_native_daemon_it_${getpid()}"
        val stateDir = "$projectDir/state"
        val outputDir = "$projectDir/out"
        val srcFile = "$projectDir/Hello.kt"
        val klibBase = "$outputDir/hello"
        // konanc appends `.kexe` to the requested link output on Linux.
        val exePath = "$outputDir/hello.kexe"
        val capturedStdoutFile = "$projectDir/stdout.txt"

        try {
            ensureDirectoryRecursive(stateDir)
            ensureDirectoryRecursive(outputDir)
            writeFileAsString(
                srcFile,
                "fun main() { println(\"$EXPECTED_STDOUT_MARKER\") }\n",
            )

            val backend = NativeDaemonBackend(
                javaBin = env.javaBin,
                daemonJarPath = env.daemonJarPath,
                konancJar = env.konancJar,
                konanHome = env.konanHome,
                socketPath = "$stateDir/native-daemon.sock",
                logPath = "$stateDir/native-daemon.log",
            )

            // Stage 1 (library): source → klib. Arg shape mirrors
            // `nativeLibraryCommand` in Builder.kt minus the konanc binary
            // (NativeCompilerBackend takes args *after* the binary per ADR
            // 0024 §4).
            val stage1 = backend.compile(
                listOf(
                    "-target", "linux_x64",
                    srcFile,
                    "-p", "library",
                    "-nopack",
                    "-o", klibBase,
                ),
            )
            assertNotNull(
                stage1.get(),
                "stage 1 (library) failed: ${stage1.getError()} (daemon log: $stateDir/native-daemon.log)",
            )

            // Stage 2 (link): klib → kexe. Re-runs through the same daemon
            // socket, validating warm-path reuse of the cached K2Native
            // instance (spike #166 confirmed stability at 100 invocations;
            // here we pin the 2-invocation minimum).
            val stage2 = backend.compile(
                listOf(
                    "-target", "linux_x64",
                    "-p", "program",
                    "-e", "main",
                    "-Xinclude=$klibBase",
                    "-o", "$outputDir/hello",
                ),
            )
            assertNotNull(
                stage2.get(),
                "stage 2 (link) failed: ${stage2.getError()} (daemon log: $stateDir/native-daemon.log)",
            )

            assertTrue(
                fileExists(exePath),
                "expected $exePath to be produced by stage 2 (daemon log: $stateDir/native-daemon.log)",
            )

            // Execute the kexe through `sh -c` so we can capture stdout via
            // shell redirect — `executeCommand` inherits the parent's fd
            // and has no capture API. `kolt run` intentionally inherits
            // too; only this IT needs to verify the bytes, hence the
            // shell detour rather than a new capturing helper in kolt.infra.
            val runResult = executeCommand(
                listOf("sh", "-c", "\"$exePath\" > \"$capturedStdoutFile\""),
            )
            assertNotNull(
                runResult.get(),
                "kexe exec failed: ${runResult.getError()}",
            )
            val stdout = readFileAsString(capturedStdoutFile).getOrElse {
                error("could not read captured stdout at $capturedStdoutFile: $it")
            }
            assertEquals(
                "$EXPECTED_STDOUT_MARKER\n",
                stdout,
                "kexe did not print the expected marker",
            )
        } finally {
            // Best-effort daemon shutdown so the test doesn't leave a
            // detached 4GB-heap JVM lingering until its 10-min idle
            // timeout. This is also the only end-to-end exercise of the
            // Shutdown wire path in the suite. Wrapped in runCatching
            // because cleanup must never fail a passing test.
            runCatching {
                val socket = UnixSocket.connect("$stateDir/native-daemon.sock")
                    .getOrElse { return@runCatching }
                FrameCodec.writeFrame(socket, Message.Shutdown)
                socket.close()
            }
            removeDirectoryRecursive(projectDir)
        }
    }

    private data class ItEnv(
        val javaBin: String,
        val daemonJarPath: String,
        val konancJar: String,
        val konanHome: String,
    )

    private fun requireEnv(): ItEnv {
        val javaHome = getenv("JAVA_HOME")?.toKString()
        if (javaHome.isNullOrEmpty()) {
            error("$IT_FLAG=1 but JAVA_HOME is not set")
        }
        val javaBin = "$javaHome/bin/java"
        if (!fileExists(javaBin)) {
            error("$IT_FLAG=1 but $javaBin does not exist")
        }

        val daemonJar = when (val r = resolveNativeDaemonJar()) {
            is NativeDaemonJarResolution.Resolved -> r.path
            NativeDaemonJarResolution.NotFound ->
                error("$IT_FLAG=1 but kolt-native-daemon jar not found — run './gradlew :kolt-native-daemon:shadowJar' first")
        }

        val konanHome = getenv("KOLT_IT_KONAN_HOME")?.toKString()
        if (konanHome.isNullOrEmpty()) {
            error("$IT_FLAG=1 but KOLT_IT_KONAN_HOME is not set")
        }
        val konancJar = "$konanHome/konan/lib/kotlin-native-compiler-embeddable.jar"
        if (!fileExists(konancJar)) {
            error("$IT_FLAG=1 but $konancJar does not exist under KOLT_IT_KONAN_HOME=$konanHome")
        }

        return ItEnv(javaBin, daemonJar, konancJar, konanHome)
    }

    private fun integrationTestsEnabled(): Boolean =
        getenv(IT_FLAG)?.toKString() == "1"

    private companion object {
        const val IT_FLAG = "KOLT_NATIVE_DAEMON_IT"
        const val EXPECTED_STDOUT_MARKER = "hello from native daemon IT"
    }
}
