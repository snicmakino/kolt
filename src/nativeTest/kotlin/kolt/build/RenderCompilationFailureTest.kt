package kolt.build

import kolt.daemon.wire.Diagnostic
import kolt.daemon.wire.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Pins the multi-line rendering of CompileError.CompilationFailed so the
// body the daemon path hands back actually reaches the user. B-2c's
// DaemonServer.icErrorToReply populates `Message.CompileResult.diagnostics`
// (structured) and `stderr` (plain text for unparsable lines); the
// native client must reunite the two streams before printing the
// one-line `formatCompileError` summary, otherwise kotlinc diagnostics
// disappear and the user sees only "error: compilation failed with
// exit code 1". Dogfood on B-2c is what surfaced the bug.
class RenderCompilationFailureTest {

    @Test
    fun rendersStructuredDiagnosticsAsKotlincStylePositionedLines() {
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "",
            diagnostics = listOf(
                Diagnostic(
                    severity = Severity.Error,
                    file = "/tmp/Main.kt",
                    line = 3,
                    column = 5,
                    message = "expected ';'",
                ),
            ),
        )
        assertEquals(
            "/tmp/Main.kt:3:5: error: expected ';'",
            renderCompilationFailure(error),
        )
    }

    @Test
    fun rendersMultipleDiagnosticsInOrder() {
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "",
            diagnostics = listOf(
                Diagnostic(Severity.Error, "/a.kt", 1, 1, "first"),
                Diagnostic(Severity.Warning, "/b.kt", 2, 2, "second"),
                Diagnostic(Severity.Info, "/c.kt", 3, 3, "third"),
            ),
        )
        val rendered = renderCompilationFailure(error)
        assertEquals(
            listOf(
                "/a.kt:1:1: error: first",
                "/b.kt:2:2: warning: second",
                "/c.kt:3:3: info: third",
            ),
            rendered.split("\n"),
        )
    }

    @Test
    fun appendsPlainStderrAfterStructuredDiagnostics() {
        // DiagnosticParser leaves unparsable lines (e.g. trailing
        // stack frames appended by CapturingKotlinLogger) in the
        // stderr string. They still need to surface, after the
        // structured lines for readability.
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "\tat foo.Bar.baz(Bar.kt:42)",
            diagnostics = listOf(
                Diagnostic(Severity.Error, "/a.kt", 1, 1, "boom"),
            ),
        )
        val rendered = renderCompilationFailure(error)
        assertEquals(
            "/a.kt:1:1: error: boom\n\tat foo.Bar.baz(Bar.kt:42)",
            rendered,
        )
    }

    @Test
    fun subprocessPathProducesEmptyBody() {
        // SubprocessCompilerBackend leaves both fields empty because
        // kotlinc already wrote to the inherited terminal. The
        // renderer must gracefully return an empty string rather than
        // a lone "\n" or a synthetic placeholder, so the caller's
        // `isNotEmpty()` skip works.
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "",
        )
        assertTrue(renderCompilationFailure(error).isEmpty())
    }

    @Test
    fun diagnosticsWithoutFileAreRenderedWithoutPositionPrefix() {
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "",
            diagnostics = listOf(
                Diagnostic(Severity.Error, file = null, line = null, column = null, message = "no location"),
            ),
        )
        assertEquals("error: no location", renderCompilationFailure(error))
    }

    @Test
    fun rendersStderrOnlyWhenDiagnosticsAreEmpty() {
        // Pre-B-2c daemon behaviour and subprocess path with
        // ProcessError.NonZeroExit carrying captured stderr both land
        // here. The renderer must not synthesise an empty structured
        // block when the diagnostic list is empty.
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "kotlinc: broken",
        )
        assertEquals("kotlinc: broken", renderCompilationFailure(error))
    }

    @Test
    fun trailingNewlineInStderrIsStripped() {
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "line 1\nline 2\n",
        )
        assertEquals("line 1\nline 2", renderCompilationFailure(error))
    }
}
