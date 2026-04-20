package kolt.nativedaemon.compiler

import com.github.michaelbull.result.Result

// Server-side abstraction over the konanc invocation path. The production
// implementation is ReflectiveK2NativeCompiler (ADR 0024 §2); tests
// substitute a fake so DaemonServer can be exercised without a real
// kotlin-native-compiler-embeddable.jar on disk.
//
// Non-zero exitCode is a normal compilation outcome (source errors live
// here) and rides the Ok side of the Result. Only genuinely unexpected
// failures — the reflective call threw, the classloader cracked, etc. —
// become NativeCompileError. Client-side fallback (ADR 0024 §7) keys off
// whether the daemon returned at all; once a NativeCompileResult reaches
// the client the exitCode passes through untouched.
//
// Thread-safety contract: implementations MAY be single-threaded (the
// production ReflectiveK2NativeCompiler is — see its type doc). DaemonServer
// serves one request at a time, so this is sufficient today. A future
// worker-pool change in DaemonServer must replace the compiler with a
// thread-safe variant before it can fan out.
interface NativeCompiler {
    fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError>
}

data class NativeCompileOutcome(
    val exitCode: Int,
    val stderr: String,
)

sealed interface NativeCompileError {
    data class InvocationFailed(val cause: Throwable) : NativeCompileError
}
