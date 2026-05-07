package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutdatedCoreTest {

  @Test
  fun classifiesMajorMinorPatch() {
    assertEquals(Severity.Major, classifySeverity("1.7.0", "2.0.0"))
    assertEquals(Severity.Minor, classifySeverity("1.7.0", "1.8.1"))
    assertEquals(Severity.Patch, classifySeverity("4.12.0", "4.12.3"))
  }

  @Test
  fun classifySeverityIsNullWhenCurrentIsAtOrAheadOfLatest() {
    assertNull(classifySeverity("1.0.0", "1.0.0"))
    assertNull(classifySeverity("2.0.0", "1.9.9"))
  }

  @Test
  fun classifySeverityHandlesQualifierOnLatest() {
    // Stable filter is the fetcher's job; severity classifier just compares
    // numeric prefixes. "1.8.1" -> Minor, the "-RC1" tail is ignored.
    assertEquals(Severity.Minor, classifySeverity("1.7.0", "1.8.1-RC1"))
  }

  @Test
  fun upToDateRowsAreOmitted() {
    val report =
      computeOutdated(
        mainDeps = mapOf("com.example:lib" to "1.0.0"),
        testDeps = emptyMap(),
        fetchLatest = { _, _ -> Ok("1.0.0") },
      )
    assertTrue(report.main.isEmpty(), "up-to-date deps must not appear: ${report.main}")
    assertTrue(report.test.isEmpty())
  }

  @Test
  fun outdatedRowCarriesGroupArtifactCurrentLatestSeverity() {
    val report =
      computeOutdated(
        mainDeps = mapOf("com.squareup.okhttp3:okhttp" to "4.12.0"),
        testDeps = emptyMap(),
        fetchLatest = { _, _ -> Ok("4.13.0") },
      )
    assertEquals(1, report.main.size)
    val row = report.main.single()
    assertEquals("com.squareup.okhttp3:okhttp", row.groupArtifact)
    assertEquals("4.12.0", row.current)
    assertEquals("4.13.0", row.latest)
    assertEquals(Severity.Minor, row.severity)
    assertNull(row.error)
  }

  @Test
  fun mainAndTestDepsRoutedToSeparateSections() {
    val report =
      computeOutdated(
        mainDeps = mapOf("com.example:lib" to "1.0.0"),
        testDeps = mapOf("com.example:junit" to "5.10.0"),
        fetchLatest = { _, artifact ->
          when (artifact) {
            "lib" -> Ok("1.0.1")
            "junit" -> Ok("5.11.3")
            else -> error("unexpected artifact: $artifact")
          }
        },
      )
    assertEquals(listOf("com.example:lib"), report.main.map { it.groupArtifact })
    assertEquals(listOf("com.example:junit"), report.test.map { it.groupArtifact })
  }

  @Test
  fun fetchErrorBecomesErrorRowAndDoesNotPoisonOtherRows() {
    val report =
      computeOutdated(
        mainDeps = mapOf("com.broken:a" to "1.0.0", "com.ok:b" to "1.0.0"),
        testDeps = emptyMap(),
        fetchLatest = { group, _ ->
          if (group == "com.broken") Err("network down") else Ok("1.1.0")
        },
      )
    assertEquals(2, report.main.size)
    val broken = report.main.single { it.groupArtifact == "com.broken:a" }
    assertEquals("network down", broken.error)
    assertNull(broken.latest)
    assertNull(broken.severity)
    val ok = report.main.single { it.groupArtifact == "com.ok:b" }
    assertNull(ok.error)
    assertEquals("1.1.0", ok.latest)
    assertEquals(Severity.Minor, ok.severity)
  }

  @Test
  fun rowsSortedAlphabeticallyByGroupArtifactWithinSection() {
    val report =
      computeOutdated(
        mainDeps =
          mapOf(
            "com.zzz:later" to "1.0.0",
            "com.aaa:earlier" to "1.0.0",
            "com.mmm:middle" to "1.0.0",
          ),
        testDeps = emptyMap(),
        fetchLatest = { _, _ -> Ok("1.0.1") },
      )
    assertEquals(
      listOf("com.aaa:earlier", "com.mmm:middle", "com.zzz:later"),
      report.main.map { it.groupArtifact },
    )
  }
}
