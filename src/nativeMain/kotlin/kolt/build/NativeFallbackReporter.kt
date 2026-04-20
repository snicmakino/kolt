package kolt.build

import kolt.infra.eprintln

// Parallel to `reportFallback` in FallbackReporter.kt, but for the native
// compiler daemon's error surface. ADR 0024 §7: a daemon failure is a
// warning, never a build-blocker — except InternalMisuse, which is a kolt
// bug (bubbles as error-level so nobody's silent-reporting a latent issue).
// CompilationFailed and NoCommand never take the fallback branch (ADR 0024
// §7 + `isNativeFallbackEligible`), so they stay silent here: the caller
// has already printed the konanc diagnostics.
fun reportNativeFallback(err: NativeCompileError, sink: (String) -> Unit = ::eprintln) {
    when (err) {
        is NativeCompileError.BackendUnavailable.ForkFailed,
        is NativeCompileError.BackendUnavailable.WaitFailed,
        is NativeCompileError.BackendUnavailable.SignalKilled,
        is NativeCompileError.BackendUnavailable.PopenFailed,
        -> sink("warning: native compiler daemon unavailable, falling back to subprocess compile")
        is NativeCompileError.BackendUnavailable.Other ->
            sink("warning: native compiler daemon unavailable (${err.detail}), falling back to subprocess compile")
        is NativeCompileError.InternalMisuse ->
            sink("error: native compiler daemon hit an internal kolt bug (${err.detail}); falling back to subprocess compile — please report this")
        is NativeCompileError.CompilationFailed, NativeCompileError.NoCommand -> {}
    }
}
