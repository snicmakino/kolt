package kolt.nativedaemon.compiler

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

sealed interface SetupError {
    data class KonancJarNotFound(val path: Path) : SetupError
    // A path that exists but is not a readable jar — e.g., a directory, a
    // permission error, or a malformed URI. The native client should refuse
    // to spawn the daemon and fall back to the subprocess path.
    data class KonancJarUnreadable(val path: Path, val cause: Throwable) : SetupError
    // The jar loaded but does not contain `org.jetbrains.kotlin.cli.bc.K2Native`.
    // Usually means the wrong jar was resolved (not the native embeddable),
    // or a Kotlin version in which the class moved — ADR 0022 pinning should
    // prevent the latter.
    data class K2NativeClassNotFound(val cause: Throwable) : SetupError
    data class K2NativeInstantiationFailed(val cause: Throwable) : SetupError
    // Exec method shape changed between Kotlin versions; ADR 0024 §2 notes
    // `MainKt.daemonMain` as the alternative entry point if this shows up.
    data object ExecMethodNotFound : SetupError
}

// ADR 0024 §2: loads `kotlin-native-compiler-embeddable.jar` via a dedicated
// URLClassLoader and invokes `K2Native.exec(PrintStream, String[])`
// reflectively. A single K2Native instance is reused for the daemon's
// lifetime — spike #166 validated this at 100 invocations with no state
// leakage.
//
// Thread-safety: this class is single-threaded by contract. `DaemonServer`
// serves one request at a time from its accept loop; K2Native's CLI backend
// almost certainly reuses mutable buffers internally, so introducing a
// worker pool here without replacing the instance-per-call model would be a
// latent crash.
//
// Classloader parenting: we use `URLClassLoader(arrayOf(url), null)` —
// bootstrap parent — so the daemon's own classpath (kotlin-result,
// kotlinx-serialization-json, daemon-core classes) is NOT visible to
// konanc's classloader. kotlin-native-compiler-embeddable.jar ships its
// own Kotlin stdlib / kotlinx-serialization, and parent-first delegation
// would otherwise let the daemon's versions win and risk LinkageError.
// NOTE: this differs from spike #166's arrangement, which used a flat
// `-cp .:konanc.jar` (system-parent) classloader. The 100-invocation
// stability proof therefore does NOT transfer directly to this topology;
// end-to-end validation of null-parent behaviour is deferred to the PR 6
// integration test. If konanc turns out to need classes beyond
// `java.*`/`javax.*`, fall back to a shared-API parent similar to
// `SharedApiClassesClassLoader` in the JVM daemon.
class ReflectiveK2NativeCompiler private constructor(
    private val k2NativeInstance: Any,
    private val execMethod: Method,
    @Suppress("unused") // retained so the classloader outlives the compiler instance
    private val classLoader: URLClassLoader,
) : NativeCompiler {

    override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
        val stderrBuf = ByteArrayOutputStream()
        val printStream = PrintStream(stderrBuf, true, StandardCharsets.UTF_8)
        val argsArray = args.toTypedArray()
        val exitValue = try {
            execMethod.invoke(k2NativeInstance, printStream, argsArray)
        } catch (e: InvocationTargetException) {
            // The reflective call wraps any Throwable the compiler threw —
            // unwrap once so the client sees the root cause, not the
            // boilerplate wrapper.
            return Err(NativeCompileError.InvocationFailed(e.cause ?: e))
        } catch (e: Throwable) {
            return Err(NativeCompileError.InvocationFailed(e))
        }
        printStream.flush()
        return Ok(
            NativeCompileOutcome(
                exitCode = extractExitCode(exitValue),
                stderr = stderrBuf.toString(StandardCharsets.UTF_8),
            ),
        )
    }

    companion object {
        fun create(konancJar: Path): Result<ReflectiveK2NativeCompiler, SetupError> {
            if (!Files.isRegularFile(konancJar)) {
                return if (Files.exists(konancJar)) {
                    Err(SetupError.KonancJarUnreadable(konancJar, IllegalArgumentException("not a regular file")))
                } else {
                    Err(SetupError.KonancJarNotFound(konancJar))
                }
            }
            val url = try {
                konancJar.toUri().toURL()
            } catch (e: Throwable) {
                return Err(SetupError.KonancJarUnreadable(konancJar, e))
            }
            val loader = URLClassLoader(arrayOf(url), null)
            val k2NativeClass = try {
                Class.forName("org.jetbrains.kotlin.cli.bc.K2Native", true, loader)
            } catch (e: Throwable) {
                runCatching { loader.close() }
                return Err(SetupError.K2NativeClassNotFound(e))
            }
            val instance = try {
                k2NativeClass.getDeclaredConstructor().newInstance()
            } catch (e: Throwable) {
                runCatching { loader.close() }
                return Err(SetupError.K2NativeInstantiationFailed(e))
            }
            val execMethod = findExecMethod(k2NativeClass)
            if (execMethod == null) {
                runCatching { loader.close() }
                return Err(SetupError.ExecMethodNotFound)
            }
            return Ok(ReflectiveK2NativeCompiler(instance, execMethod, loader))
        }

        // Walks the class hierarchy for `exec(PrintStream, String[])`. ADR
        // 0024 §2 / spike #166's `ReflectiveKonanc.java` both use this
        // shape. `execImpl` is accepted as a fallback because the public
        // `exec` has at times been an inline thunk over it; the spike code
        // guards against that case and we keep the guard.
        private fun findExecMethod(clazz: Class<*>): Method? {
            var c: Class<*>? = clazz
            while (c != null) {
                for (m in c.declaredMethods) {
                    if ((m.name == "exec" || m.name == "execImpl") &&
                        m.parameterCount == 2 &&
                        m.parameterTypes[0] == PrintStream::class.java &&
                        m.parameterTypes[1] == Array<String>::class.java
                    ) {
                        m.isAccessible = true
                        return m
                    }
                }
                c = c.superclass
            }
            return null
        }

        // `CLICompiler.exec` returns `org.jetbrains.kotlin.cli.common.ExitCode`,
        // a Java enum with an `int code` field (accessor: `getCode`). We read
        // it reflectively because the enum class lives in the konanc
        // classloader, not the daemon's. ExitCode values have `code` values
        // that are NOT equal to ordinals (e.g. `OOM_ERROR.code = 137` at
        // ordinal 4), so ordinal is never an acceptable fallback. Any
        // failure to read `.code` surfaces as exitCode=2 (INTERNAL_ERROR),
        // matching the shape the client already handles.
        private fun extractExitCode(exitValue: Any?): Int {
            if (exitValue == null) return 2
            return runCatching {
                val getCode = exitValue.javaClass.getMethod("getCode")
                getCode.invoke(exitValue) as Int
            }.getOrDefault(2)
        }
    }
}
