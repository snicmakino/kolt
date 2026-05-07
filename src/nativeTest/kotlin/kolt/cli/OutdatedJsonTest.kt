package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class OutdatedJsonTest {

  @Test
  fun emptyReportEmitsEmptyArrays() {
    val report = OutdatedReport(main = emptyList(), test = emptyList())
    val expected =
      """
      {
        "dependencies": [],
        "testDependencies": []
      }
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedJson(report))
  }

  @Test
  fun outdatedRowEmitsAllFieldsExceptError() {
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
            )
          ),
        test = emptyList(),
      )
    val expected =
      """
      {
        "dependencies": [
          {
            "group": "com.squareup.okhttp3",
            "name": "okhttp",
            "current": "4.12.0",
            "latest": "4.13.0",
            "severity": "minor"
          }
        ],
        "testDependencies": []
      }
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedJson(report))
  }

  @Test
  fun errorRowOmitsLatestAndSeverityAndIncludesError() {
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
      {
        "dependencies": [
          {
            "group": "com.broken",
            "name": "dep",
            "current": "1.0.0",
            "error": "network down"
          }
        ],
        "testDependencies": []
      }
      """
        .trimIndent()
    assertEquals(expected, formatOutdatedJson(report))
  }

  @Test
  fun severityIsLowercaseEnumName() {
    val report =
      OutdatedReport(
        main =
          listOf(
            OutdatedRow("a:b", "1.0.0", "2.0.0", Severity.Major, null),
            OutdatedRow("c:d", "1.0.0", "1.1.0", Severity.Minor, null),
            OutdatedRow("e:f", "1.0.0", "1.0.1", Severity.Patch, null),
          ),
        test = emptyList(),
      )
    val json = formatOutdatedJson(report)
    assertEquals(true, json.contains("\"severity\": \"major\""))
    assertEquals(true, json.contains("\"severity\": \"minor\""))
    assertEquals(true, json.contains("\"severity\": \"patch\""))
  }
}
