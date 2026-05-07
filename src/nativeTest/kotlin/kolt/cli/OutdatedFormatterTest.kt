package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class OutdatedFormatterTest {

  @Test
  fun emptyReportShowsAllUpToDate() {
    val report = OutdatedReport(main = emptyList(), test = emptyList())
    assertEquals("All dependencies up to date.", formatOutdatedText(report))
  }

  @Test
  fun mainSectionRendersWithHeaderAndAlignedColumns() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow(
              groupArtifact = "com.squareup.okhttp3:okhttp",
              current = "4.12.0",
              latest = "4.13.0",
              severity = Severity.Minor,
              error = null,
            ),
            OutdatedRow(
              groupArtifact = "org.jetbrains.kotlinx:kotlinx-serialization-json",
              current = "1.7.0",
              latest = "1.8.1",
              severity = Severity.Major,
              error = null,
            ),
          ),
        test = emptyList(),
      )
    val expected =
      """
      [dependencies]
        com.squareup.okhttp3:okhttp                       4.12.0  →  4.13.0
        org.jetbrains.kotlinx:kotlinx-serialization-json  1.7.0   →  1.8.1   (major)
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedText(report))
  }

  @Test
  fun bothSectionsSeparatedByBlankLine() {
    val report =
      OutdatedReport(
        main = listOf(OutdatedRow("com.example:lib", "1.0.0", "1.0.1", Severity.Patch, null)),
        test =
          listOf(
            OutdatedRow("org.junit.jupiter:junit-jupiter", "5.10.0", "5.11.3", Severity.Minor, null)
          ),
      )
    val expected =
      """
      [dependencies]
        com.example:lib  1.0.0  →  1.0.1

      [test-dependencies]
        org.junit.jupiter:junit-jupiter  5.10.0  →  5.11.3
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedText(report))
  }

  @Test
  fun errorRowsRenderWithErrorTag() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow(
              groupArtifact = "com.broken:dep",
              current = "1.0.0",
              latest = null,
              severity = null,
              error = "network down",
            )
          ),
        test = emptyList(),
      )
    val expected =
      """
      [dependencies]
        com.broken:dep  1.0.0  →  ?  (error: network down)
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedText(report))
  }

  @Test
  fun emptyMainSectionIsOmittedWhenOnlyTestSectionHasRows() {
    val report =
      OutdatedReport(
        main = emptyList(),
        test =
          listOf(
            OutdatedRow("org.junit.jupiter:junit-jupiter", "5.10.0", "5.11.3", Severity.Minor, null)
          ),
      )
    val expected =
      """
      [test-dependencies]
        org.junit.jupiter:junit-jupiter  5.10.0  →  5.11.3
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedText(report))
  }

  @Test
  fun multilineErrorIsCollapsedToOneLineAndKeepsColumnAlignment() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow(
              groupArtifact = "com.broken:dep",
              current = "1.0.0",
              latest = null,
              severity = null,
              error =
                "metadata fetch failed\n  https://repo1/m.xml -> 404\n  https://repo2/m.xml -> 503",
            ),
            OutdatedRow("com.ok:lib", "1.0.0", "2.0.0", Severity.Major, null),
          ),
        test = emptyList(),
      )
    val rendered = formatOutdatedText(report)
    val lineCount = rendered.split('\n').size
    assertEquals(3, lineCount, "expected header + 2 rows, got:\n$rendered")
    val errorLine = rendered.split('\n')[1]
    assertEquals(
      true,
      errorLine.contains(
        "metadata fetch failed https://repo1/m.xml -> 404 https://repo2/m.xml -> 503"
      ),
      "newlines must collapse, got:\n$errorLine",
    )
  }

  @Test
  fun leadingErrorPrefixIsStripped() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow(
              groupArtifact = "com.broken:dep",
              current = "1.0.0",
              latest = null,
              severity = null,
              error = "error: oops",
            )
          ),
        test = emptyList(),
      )
    val rendered = formatOutdatedText(report)
    assertEquals(
      true,
      rendered.contains("(error: oops)") && !rendered.contains("(error: error:"),
      "expected `(error: oops)` and no double-error prefix, got:\n$rendered",
    )
  }
}
