package kolt.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionCompareTest {

  @Test
  fun equalVersionsReturnZero() {
    assertEquals(0, compareVersions("1.0.0", "1.0.0"))
  }

  @Test
  fun higherMajorVersionIsGreater() {
    assertTrue(compareVersions("2.0.0", "1.0.0") > 0)
  }

  @Test
  fun lowerMajorVersionIsLess() {
    assertTrue(compareVersions("1.0.0", "2.0.0") < 0)
  }

  @Test
  fun higherMinorVersionIsGreater() {
    assertTrue(compareVersions("1.2.0", "1.1.0") > 0)
  }

  @Test
  fun higherPatchVersionIsGreater() {
    assertTrue(compareVersions("1.0.2", "1.0.1") > 0)
  }

  @Test
  fun numericComparisonNotLexicographic() {
    assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
  }

  @Test
  fun differentSegmentCountsShorterIsSmallerWhenPrefixEqual() {
    assertTrue(compareVersions("1.0.0.1", "1.0.0") > 0)
  }

  @Test
  fun snapshotIsLessThanRelease() {
    assertTrue(compareVersions("1.0.0-SNAPSHOT", "1.0.0") < 0)
  }

  @Test
  fun alphaIsLessThanBeta() {
    assertTrue(compareVersions("1.0.0-alpha", "1.0.0-beta") < 0)
  }

  @Test
  fun betaIsLessThanRc() {
    assertTrue(compareVersions("1.0.0-beta", "1.0.0-rc") < 0)
  }

  @Test
  fun rcIsLessThanRelease() {
    assertTrue(compareVersions("1.0.0-rc", "1.0.0") < 0)
  }

  @Test
  fun snapshotIsLessThanAlpha() {
    assertTrue(compareVersions("1.0.0-SNAPSHOT", "1.0.0-alpha") < 0)
  }

  @Test
  fun qualifierComparisonIsCaseInsensitive() {
    assertTrue(compareVersions("1.0.0-Alpha", "1.0.0-BETA") < 0)
  }

  @Test
  fun numericQualifierComparedNumerically() {
    assertTrue(compareVersions("1.0.0-alpha2", "1.0.0-alpha10") < 0)
  }

  @Test
  fun twoSegmentVersion() {
    assertTrue(compareVersions("1.1", "1.0") > 0)
  }

  @Test
  fun singleSegmentVersion() {
    assertTrue(compareVersions("2", "1") > 0)
  }

  @Test
  fun trailingZeroSegmentsAreEqual() {
    assertEquals(0, compareVersions("1.0", "1.0.0"))
  }

  @Test
  fun trailingZeroSegmentsAreEqualLonger() {
    assertEquals(0, compareVersions("1.0.0", "1.0.0.0"))
  }

  // --- Version interval parsing ---

  @Test
  fun parseExactVersionConstraint() {
    val vc = parseVersionConstraint("1.0.0")
    assertNull(vc.interval)
    assertEquals("1.0.0", vc.preferred)
  }

  @Test
  fun parseClosedInterval() {
    val vc = parseVersionConstraint("[1.0.0,2.0.0]")
    val interval = assertNotNull(vc.interval)
    assertEquals("1.0.0", interval.from)
    assertTrue(interval.fromInclusive)
    assertEquals("2.0.0", interval.to)
    assertTrue(interval.toInclusive)
    assertNull(vc.preferred)
  }

  @Test
  fun parseHalfOpenInterval() {
    val vc = parseVersionConstraint("[1.0.0,2.0.0)")
    val interval = assertNotNull(vc.interval)
    assertEquals("1.0.0", interval.from)
    assertTrue(interval.fromInclusive)
    assertEquals("2.0.0", interval.to)
    assertFalse(interval.toInclusive)
  }

  @Test
  fun parseOpenInterval() {
    val vc = parseVersionConstraint("(1.0.0,2.0.0)")
    val interval = assertNotNull(vc.interval)
    assertEquals("1.0.0", interval.from)
    assertFalse(interval.fromInclusive)
    assertEquals("2.0.0", interval.to)
    assertFalse(interval.toInclusive)
  }

  @Test
  fun parseUnboundedUpperInterval() {
    val vc = parseVersionConstraint("[1.0.0,)")
    val interval = assertNotNull(vc.interval)
    assertEquals("1.0.0", interval.from)
    assertTrue(interval.fromInclusive)
    assertNull(interval.to)
  }

  @Test
  fun parseUnboundedLowerInterval() {
    val vc = parseVersionConstraint("(,2.0.0]")
    val interval = assertNotNull(vc.interval)
    assertNull(interval.from)
    assertEquals("2.0.0", interval.to)
    assertTrue(interval.toInclusive)
  }

  @Test
  fun parsePinnedInterval() {
    val vc = parseVersionConstraint("[1.5.0]")
    val interval = assertNotNull(vc.interval)
    assertEquals("1.5.0", interval.from)
    assertEquals("1.5.0", interval.to)
    assertTrue(interval.fromInclusive)
    assertTrue(interval.toInclusive)
  }

  // --- selectVersion ---

  @Test
  fun selectVersionFromExactConstraint() {
    assertEquals("1.0.0", selectVersion("1.0.0"))
  }

  @Test
  fun selectVersionFromClosedIntervalUsesLowerBound() {
    assertEquals("1.0.0", selectVersion("[1.0.0,2.0.0]"))
  }

  @Test
  fun selectVersionFromPinnedInterval() {
    assertEquals("1.5.0", selectVersion("[1.5.0]"))
  }

  @Test
  fun selectVersionFromUnboundedUpperUsesLowerBound() {
    assertEquals("1.0.0", selectVersion("[1.0.0,)"))
  }

  @Test
  fun selectVersionFromUnboundedLowerUsesUpperBound() {
    assertEquals("2.0.0", selectVersion("(,2.0.0]"))
  }

  // --- VersionInterval.contains ---

  @Test
  fun intervalContainsVersionInRange() {
    val vc = parseVersionConstraint("[1.0.0,2.0.0]")
    assertTrue(vc.interval!!.contains("1.5.0"))
  }

  @Test
  fun intervalExcludesVersionOutOfRange() {
    val vc = parseVersionConstraint("[1.0.0,2.0.0)")
    assertFalse(vc.interval!!.contains("2.0.0"))
  }

  @Test
  fun intervalIncludesLowerBoundWhenInclusive() {
    val vc = parseVersionConstraint("[1.0.0,2.0.0)")
    assertTrue(vc.interval!!.contains("1.0.0"))
  }

  @Test
  fun intervalExcludesLowerBoundWhenExclusive() {
    val vc = parseVersionConstraint("(1.0.0,2.0.0)")
    assertFalse(vc.interval!!.contains("1.0.0"))
  }

  // --- matchesRejectPattern ---

  @Test
  fun rejectExactVersionMatchesIdenticalString() {
    assertTrue(matchesRejectPattern("1.6.0", "1.6.0"))
  }

  @Test
  fun rejectExactVersionDoesNotMatchDifferentString() {
    assertFalse(matchesRejectPattern("1.7.0", "1.6.0"))
  }

  @Test
  fun rejectIntervalMatchesVersionInRange() {
    assertTrue(matchesRejectPattern("1.2.0", "[1.0.0,1.5.0)"))
  }

  @Test
  fun rejectIntervalExcludesVersionAtExclusiveUpperBound() {
    assertFalse(matchesRejectPattern("1.5.0", "[1.0.0,1.5.0)"))
  }

  @Test
  fun rejectUnboundedUpperIntervalMatchesAnyHigherVersion() {
    assertTrue(matchesRejectPattern("3.0.0", "[2.0.0,)"))
  }
}

class IsStableVersionTest {

  @Test
  fun pureNumericTripleIsStable() = assertTrue(isStableVersion("1.0.0"))

  @Test
  fun pureNumericPairIsStable() = assertTrue(isStableVersion("1.10"))

  @Test
  fun rcWithNumberIsNotStable() = assertFalse(isStableVersion("1.11.0-rc02"))

  @Test
  fun rcWithoutNumberIsNotStable() = assertFalse(isStableVersion("1.0.0-rc"))

  @Test
  fun rcUppercaseIsNotStable() = assertFalse(isStableVersion("1.0.0-RC1"))

  @Test
  fun alphaIsNotStable() = assertFalse(isStableVersion("1.0.0-alpha1"))

  @Test
  fun betaIsNotStable() = assertFalse(isStableVersion("1.0.0-beta"))

  @Test
  fun snapshotIsNotStable() = assertFalse(isStableVersion("1.0.0-SNAPSHOT"))

  @Test
  fun milestoneMUpperIsNotStable() = assertFalse(isStableVersion("1.0.0-M5"))

  @Test
  fun milestoneMLowerIsNotStable() = assertFalse(isStableVersion("1.0.0-m12"))

  @Test
  fun bareMWithoutDigitIsStable() = assertTrue(isStableVersion("1.0.0-M"))

  @Test
  fun eapIsNotStable() = assertFalse(isStableVersion("1.0.0-eap"))

  @Test
  fun devIsNotStable() = assertFalse(isStableVersion("1.0.0-dev"))

  @Test
  fun previewIsNotStable() = assertFalse(isStableVersion("1.0.0-preview"))

  @Test
  fun unknownQualifierTreatedAsStable() = assertTrue(isStableVersion("1.0.0-final"))

  @Test
  fun springReleaseQualifierTreatedAsStable() = assertTrue(isStableVersion("1.0.0.RELEASE"))
}
