package kolt.daemon.ic

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import java.nio.file.Files
import java.nio.file.Path

// Self-heal wrapper defined by ADR 0019 §7. On an `IcError.InternalError`
// from the wrapped adapter it (a) deletes the per-project `workingDir`
// tree — the presumed-corrupt IC state — and (b) invokes the delegate
// once more with the same request. BTA's `SourcesChanges.ToBeCalculated`
// mode walking an empty state dir naturally degrades to a full recompile,
// so no "force cold" flag is needed on `IcRequest`.
//
// Placement rationale: the ADR specifies self-heal lives "inside the
// adapter". This wrapper is still inside the `:ic` module — daemon core
// imports `IncrementalCompiler` without knowing whether it received a
// bare `BtaIncrementalCompiler` or the wrapped variant. The split into
// two classes is an implementation choice that keeps the retry logic
// unit-testable against a fake delegate (`SelfHealingIncrementalCompilerTest`),
// without any real BTA dependency, while honouring the ADR's boundary.
//
// Failure precedence: if the retry also fails, the retry's `IcError` is
// surfaced verbatim — a `CompilationFailed` from the retry wins over the
// original `InternalError`, and a second `InternalError` overwrites the
// first. This matches ADR §7: the user must see a useful compile message
// whenever the retry produced one, and must never see "incremental cache
// was corrupt".
//
// `VirtualMachineError` from the delegate is not absorbed. ADR 0019 §7
// (widened in this PR) routes JVM-fatal errors past the adapter so they
// reach `FallbackCompilerBackend` (ADR 0016 §5); catching them here and
// firing a retry would allocate more objects and reproduce the OOM in a
// loop.
class SelfHealingIncrementalCompiler(
    private val delegate: IncrementalCompiler,
    // Wipe action is injected so tests can observe the call without
    // touching a real filesystem, and so production can replace the
    // default recursive-delete with a different strategy (e.g. move-
    // to-quarantine-dir) without reopening this class. The default
    // below is what the ADR specifies: a recursive delete of the
    // per-project IC state tree.
    private val wipe: (Path) -> Unit = ::defaultWipe,
) : IncrementalCompiler {

    override fun compile(request: IcRequest): Result<IcResponse, IcError> {
        val first = delegate.compile(request)
        return first.mapBoth(
            success = { first },
            failure = { error ->
                if (error is IcError.InternalError) {
                    wipe(request.workingDir)
                    delegate.compile(request)
                } else {
                    first
                }
            },
        )
    }

    companion object {
        // Recursively delete the working-dir tree. `Files.walk` in post-order
        // lets us delete children before their parent, which is the only
        // order `Files.delete` accepts for a non-empty directory.
        private fun defaultWipe(workingDir: Path) {
            if (!Files.exists(workingDir)) return
            Files.walk(workingDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { path -> runCatching { Files.delete(path) } }
            }
        }
    }
}
