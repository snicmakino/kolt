package kolt.infra.suggest

import kotlin.test.Test
import kotlin.test.assertEquals

class LevenshteinTest {
  @Test
  fun identicalStringsHaveDistanceZero() {
    assertEquals(0, levenshtein("foo", "foo"))
  }

  @Test
  fun singleDeletionHasDistanceOne() {
    assertEquals(1, levenshtein("foo", "fo"))
  }

  @Test
  fun singleInsertionHasDistanceOne() {
    assertEquals(1, levenshtein("fo", "foo"))
  }

  @Test
  fun singleSubstitutionHasDistanceOne() {
    assertEquals(1, levenshtein("foo", "fox"))
  }

  @Test
  fun emptyVsNonEmptyEqualsLengthOfNonEmpty() {
    assertEquals(3, levenshtein("", "abc"))
    assertEquals(3, levenshtein("abc", ""))
  }

  @Test
  fun bothEmptyEqualsZero() {
    assertEquals(0, levenshtein("", ""))
  }

  @Test
  fun mixedEditsCombineCorrectly() {
    // "kitten" → "sitting" : 3 edits (k→s, e→i, +g)
    assertEquals(3, levenshtein("kitten", "sitting"))
  }

  @Test
  fun isSymmetric() {
    assertEquals(levenshtein("alpha", "beta"), levenshtein("beta", "alpha"))
  }
}
