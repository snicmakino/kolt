package kolt.daemon.protocol

// Parses kotlinc-style `path:line:column: severity: message` strings as
// produced by BTA's KotlinLogger into structured Diagnostic records.
// Anything that does not match the shape stays as a plain-text message
// (the caller holds it as free-text stderr).
//
// Scoped for B-2c: Linux absolute paths only. Windows drive letters
// (`C:\...`) are out of scope because the daemon runs exclusively on
// Linux/macOS-style filesystems in the current kolt release.
object DiagnosticParser {

    // Two regexes because kotlinc has two output shapes that BTA surfaces
    // through `KotlinLogger.error`:
    //
    //   1. Classic subprocess format: `path:L:C: severity: message`
    //      — what `kolt-jvm-compiler-daemon` sees when it runs a plain
    //      kotlinc invocation. Severity token can be `error`, `warning`,
    //      `info`, or `strong_warning` (underscore is why the token
    //      group allows `_`).
    //
    //   2. BTA 2.3.20 in-process format: `file:///URI:L:C <message>`
    //      — what `BtaIncrementalCompiler` actually receives on a real
    //      compile error. No severity word between position and
    //      message, a **space** separator instead of a colon, and a
    //      `file://` URI prefix on the path. We default these to
    //      `Severity.Error` because `CapturingKotlinLogger` only
    //      captures `logger.error(...)` into `errors` (warn/info are
    //      forwarded to the metrics sink only), so anything that
    //      reaches this parser via the captured error list is, by
    //      definition, an error.
    //
    // Dogfood on the B-2c merge (jvm-25 fixture with a deliberate type
    // error) produced shape 2; the parser was only matching shape 1
    // before, so structured diagnostics silently went through the
    // plain-text `stderr` fallback instead of populating
    // `Message.CompileResult.diagnostics`. Both shapes matter: shape 1
    // will reappear the moment a future BTA bump changes the format
    // back, and shape 2 is what today's user actually sees.
    private val SUBPROCESS_REGEX: Regex =
        Regex("""^(?<path>.+):(?<line>\d+):(?<col>\d+):\s*(?<sev>[A-Za-z_]+):\s*(?<msg>.*)$""")

    // Path must start with either `file://` or `/` so an arbitrary
    // error-level log line like `Cannot resolve org.foo:1:2 from repo`
    // cannot false-positive into a fake `Diagnostic`. The parser's own
    // contract (see top-of-file comment) is Linux/macOS absolute paths
    // only, which means a real diagnostic always begins with one of
    // those two prefixes. A line that fits the `path:L:C msg` skeleton
    // but lacks an absolute-path leader correctly falls through to
    // plain-text stderr.
    private val BTA_INLINE_REGEX: Regex =
        Regex("""^(?<path>(?:file://)?/[^\s].*?):(?<line>\d+):(?<col>\d+)\s+(?<msg>.*)$""")

    private const val FILE_URI_PREFIX: String = "file://"

    fun parseLine(line: String): Diagnostic? {
        // Defensive trim: BTA may or may not terminate captured logger
        // strings with `\n`; `matchEntire` does not consume newlines
        // because `.` does not match `\n` by default. Strip both `\n`
        // and `\r` so a future BTA bump that starts adding line
        // terminators does not silently break the parser.
        val trimmed = line.trimEnd('\n', '\r')
        SUBPROCESS_REGEX.matchEntire(trimmed)?.let { match ->
            val severity = toSeverity(match.groups["sev"]!!.value) ?: return@let
            return Diagnostic(
                severity = severity,
                file = stripFileUri(match.groups["path"]!!.value),
                line = match.groups["line"]!!.value.toInt(),
                column = match.groups["col"]!!.value.toInt(),
                message = match.groups["msg"]!!.value.trim(),
            )
        }
        BTA_INLINE_REGEX.matchEntire(trimmed)?.let { match ->
            return Diagnostic(
                severity = Severity.Error,
                file = stripFileUri(match.groups["path"]!!.value),
                line = match.groups["line"]!!.value.toInt(),
                column = match.groups["col"]!!.value.toInt(),
                message = match.groups["msg"]!!.value.trim(),
            )
        }
        return null
    }

    private fun stripFileUri(path: String): String =
        if (path.startsWith(FILE_URI_PREFIX)) path.removePrefix(FILE_URI_PREFIX) else path

    // Splits a flat list of captured logger lines into the structured
    // `diagnostics` field for `Message.CompileResult` and the residual
    // plain-text lines the daemon still surfaces via `stderr`. The
    // partition preserves input order within each bucket so a reader
    // scanning stderr and the diagnostics list in parallel sees a
    // consistent sequence.
    fun parseMessages(lines: List<String>): Pair<List<Diagnostic>, List<String>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val plain = mutableListOf<String>()
        for (line in lines) {
            val parsed = parseLine(line)
            if (parsed != null) diagnostics.add(parsed) else plain.add(line)
        }
        return diagnostics to plain
    }

    // `strong_warning` is kotlinc's opt-in-required severity; collapse it
    // to plain Warning so IDE-style rendering still surfaces it rather
    // than demoting the whole line into free-text stderr.
    private fun toSeverity(raw: String): Severity? = when (raw.lowercase()) {
        "error" -> Severity.Error
        "warning", "strong_warning" -> Severity.Warning
        "info" -> Severity.Info
        else -> null
    }
}
