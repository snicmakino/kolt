package kolt.build

import kolt.daemon.wire.Diagnostic
import kolt.daemon.wire.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun fileNullWithLineColumnStillCollapsesToNoLocation() {
        // Must not emit a leading-colon `:5:3: error: msg` when file is null.
        val error = CompileError.CompilationFailed(
            exitCode = 1,
            stdout = "",
            stderr = "",
            diagnostics = listOf(
                Diagnostic(Severity.Error, file = null, line = 5, column = 3, message = "rogue"),
            ),
        )
        assertEquals("error: rogue", renderCompilationFailure(error))
    }

    @Test
    fun rendersStderrOnlyWhenDiagnosticsAreEmpty() {
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
