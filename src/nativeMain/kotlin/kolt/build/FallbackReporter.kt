package kolt.build

import kolt.infra.eprintln

/**
 * Turns a primary-backend [CompileError] into a one-line stderr line
 * and hands it to [sink]. This is the production hook wired into
 * [FallbackCompilerBackend] via `onFallback` and is kept as a
 * top-level function so the severity policy (ADR 0016 §3) can be
 * exercised without standing up a real doBuild pipeline.
 *
 * Severity mapping:
 *  - [CompileError.BackendUnavailable] — daemon spawn/connect/reply
 *    failed. Expected transient class; surface as a warning so the
 *    user sees it once but the build does not look broken.
 *  - [CompileError.InternalMisuse] — kolt produced a bad argument
 *    for the backend (e.g. a sockaddr_un path that overflowed, or a
 *    daemon that exited with "usage error"). This is a kolt bug that
 *    must not disappear into a silent fallback; ADR 0016 §3 requires
 *    an error log so the dogfooding feedback loop catches it.
 *  - [CompileError.CompilationFailed] / [CompileError.NoCommand] —
 *    not fallback-eligible; [isFallbackEligible] returns `false` for
 *    both so `FallbackCompilerBackend` never invokes the observer
 *    with them. The no-op branch here is a defensive fallthrough so
 *    that adding a new `false`-classified variant in the future does
 *    not force a hasty wording decision here.
 */
fun reportFallback(err: CompileError, sink: (String) -> Unit = ::eprintln) {
    // Note on variant reachability: in the current production wiring
    // (daemon primary + subprocess fallback) only
    // `BackendUnavailable.Other` and `InternalMisuse` can arrive here
    // — `DaemonCompilerBackend` wraps every spawn/connect/frame
    // failure into one of those two. The four named `BackendUnavailable`
    // variants below are emitted only by `SubprocessCompilerBackend`
    // and are therefore dead in the current composition; they are
    // kept (and tested) so a future composition that puts the
    // subprocess backend as the primary — with a different secondary
    // — does not silently lose severity wording.
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
        is CompileError.CompilationFailed, CompileError.NoCommand -> {
            // isFallbackEligible returns false for both; FallbackCompilerBackend
            // never reaches the onFallback hook with these. Kept as a no-op so
            // the when remains exhaustive.
        }
    }
}
