package kolt.daemon.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Pure-function parser pinned by unit tests. BTA's KotlinLogger surfaces
// per-source-position diagnostics as `path:line:col: severity: message`
// strings; this parser splits that shape into a structured Diagnostic so
// the native client can render IDE-style error lists. Lines that do not
// match stay as free-text error messages (the parser returns null and the
// caller keeps the original string).
class DiagnosticParserTest {

    @Test
    fun parsesErrorLineWithAbsolutePath() {
        val parsed = DiagnosticParser.parseLine(
            "/tmp/kolt/Main.kt:12:7: error: unresolved reference: foo",
        )
        assertEquals(
            Diagnostic(
                severity = Severity.Error,
                file = "/tmp/kolt/Main.kt",
                line = 12,
                column = 7,
                message = "unresolved reference: foo",
            ),
            parsed,
        )
    }

    @Test
    fun parsesWarningLine() {
        val parsed = DiagnosticParser.parseLine(
            "/src/a/B.kt:3:1: warning: parameter 'x' is never used",
        )
        assertEquals(Severity.Warning, parsed?.severity)
        assertEquals("parameter 'x' is never used", parsed?.message)
    }

    @Test
    fun parsesInfoLine() {
        val parsed = DiagnosticParser.parseLine("/p/Q.kt:1:1: info: note text")
        assertEquals(Severity.Info, parsed?.severity)
    }

    @Test
    fun returnsNullForNonDiagnosticLine() {
        assertNull(DiagnosticParser.parseLine("just some random kotlinc chatter"))
    }

    @Test
    fun returnsNullForMissingSeverity() {
        assertNull(DiagnosticParser.parseLine("/x/Y.kt:1:1: nope: whatever"))
    }

    @Test
    fun parseMessagesSplitsParsedAndUnparsedLines() {
        val input = listOf(
            "/tmp/A.kt:5:3: error: bad",
            "something else",
            "/tmp/B.kt:1:1: warning: stylistic",
        )
        val (diagnostics, plain) = DiagnosticParser.parseMessages(input)
        assertEquals(
            listOf(
                Diagnostic(Severity.Error, "/tmp/A.kt", 5, 3, "bad"),
                Diagnostic(Severity.Warning, "/tmp/B.kt", 1, 1, "stylistic"),
            ),
            diagnostics,
        )
        assertEquals(listOf("something else"), plain)
    }

    @Test
    fun parsesLineWithExtraSpacesAroundSeverity() {
        val parsed = DiagnosticParser.parseLine(
            "/a/B.kt:2:4:   error:   msg with padding",
        )
        assertEquals("msg with padding", parsed?.message)
    }

    @Test
    fun strongWarningCollapsesToWarning() {
        // kotlinc emits `strong_warning` for opt-in-required and a few
        // other cases; the parser must recognise it (not drop the line
        // into plain-text stderr) and the IDE-rendered severity must be
        // the regular Warning bucket.
        val parsed = DiagnosticParser.parseLine(
            "/tmp/A.kt:1:1: strong_warning: opt-in required for foo",
        )
        assertEquals(Severity.Warning, parsed?.severity)
        assertEquals("opt-in required for foo", parsed?.message)
    }

    @Test
    fun parsesBtaInlineShapeWithFileUriAndNoSeverityWord() {
        // BTA 2.3.20 `KotlinLogger.error` emits this shape on a real
        // compile error; verified against dogfood output on the B-2c
        // merge. The `file://` prefix must be stripped and the
        // severity defaults to `Error` because only error-level lines
        // reach `CapturingKotlinLogger.errors`.
        val parsed = DiagnosticParser.parseLine(
            "file:///tmp/kolt/File12.kt:3:17 Initializer type mismatch: expected 'Int', actual 'String'.",
        )
        assertEquals(
            Diagnostic(
                severity = Severity.Error,
                file = "/tmp/kolt/File12.kt",
                line = 3,
                column = 17,
                message = "Initializer type mismatch: expected 'Int', actual 'String'.",
            ),
            parsed,
        )
    }

    @Test
    fun nonAbsolutePathFallsThroughInsteadOfFalsePositiveOnBtaInline() {
        // The BTA-inline regex must not match arbitrary error-level
        // log lines that happen to contain `word:digits:digits word`.
        // Real diagnostics always start with an absolute path or
        // `file://` URI; anything else is unstructured chatter and
        // should land in plain-text stderr instead of being lifted
        // into a fake Diagnostic with a path like `Cannot resolve`.
        assertNull(
            DiagnosticParser.parseLine("Cannot resolve coordinate org.foo:1:2 from repo"),
        )
        assertNull(DiagnosticParser.parseLine("foo:1:2 bar"))
    }

    @Test
    fun subprocessShapeWinsOverBtaInlineShapeOnLinesThatMatchBoth() {
        // `/foo:1:2: error: bar` is a legal subprocess shape AND would
        // also match the BTA-inline regex if reached (path=`/foo`,
        // line=1, col=2, msg=`error: bar`). The parser must try the
        // subprocess regex first so the severity word is honoured and
        // the message body is correct. A refactor that swapped the
        // order would silently produce wrong diagnostics.
        val parsed = DiagnosticParser.parseLine("/foo:1:2: error: bar")
        assertEquals(Severity.Error, parsed?.severity)
        assertEquals("/foo", parsed?.file)
        assertEquals("bar", parsed?.message)
    }

    @Test
    fun trailingNewlineIsStripped() {
        // BTA may or may not terminate captured strings; defend
        // against a future bump by trimming `\n` / `\r` before the
        // regex tries matchEntire (which does not consume `\n`).
        val parsed = DiagnosticParser.parseLine(
            "file:///tmp/A.kt:1:1 boom\n",
        )
        assertEquals("/tmp/A.kt", parsed?.file)
        assertEquals("boom", parsed?.message)
    }

    @Test
    fun btaInlineShapeWithoutFileUriPrefixStillParses() {
        // A future BTA release may drop the `file://` prefix. Matching
        // the shape without it keeps us forward-compatible.
        val parsed = DiagnosticParser.parseLine(
            "/tmp/kolt/File12.kt:3:17 Initializer type mismatch",
        )
        assertEquals("/tmp/kolt/File12.kt", parsed?.file)
        assertEquals(Severity.Error, parsed?.severity)
    }

    @Test
    fun trailingStackFrameLinesStayUnparsed() {
        // CapturingKotlinLogger appends `\n\tat Foo.bar(...)` for throwables.
        // That trailing frame must not masquerade as a diagnostic.
        assertNull(DiagnosticParser.parseLine("\tat foo.Bar.baz(Bar.kt:42)"))
    }
}
