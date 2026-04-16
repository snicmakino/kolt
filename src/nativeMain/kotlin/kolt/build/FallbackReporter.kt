package kolt.build

import kolt.infra.eprintln

// ADR 0016 §3: BackendUnavailable => warning, InternalMisuse => error.
fun reportFallback(err: CompileError, sink: (String) -> Unit = ::eprintln) {
    when (err) {
        is CompileError.BackendUnavailable.ForkFailed,
        is CompileError.BackendUnavailable.WaitFailed,
        is CompileError.BackendUnavailable.SignalKilled,
        is CompileError.BackendUnavailable.PopenFailed,
        -> sink("warning: compiler daemon unavailable, falling back to subprocess compile")
        is CompileError.BackendUnavailable.Other ->
            sink("warning: compiler daemon unavailable (${err.detail}), falling back to subprocess compile")
        is CompileError.InternalMisuse ->
            sink("error: compiler daemon hit an internal kolt bug (${err.detail}); falling back to subprocess compile — please report this")
        is CompileError.CompilationFailed, CompileError.NoCommand -> {}
    }
}
