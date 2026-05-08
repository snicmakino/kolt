package kolt.infra.suggest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClosestMatchTest {
  @Test
  fun returnsExactMatchAtDistanceZero() {
    assertEquals("build", closestMatch("build", listOf("build", "test", "fmt")))
  }

  @Test
  fun returnsTransposedCandidateWithinThreshold() {
    // "buidl" vs "build" is distance 2 (two substitutions, no transposition
    // shortcut in plain Levenshtein); length 5 → adaptive threshold 2 → match.
    assertEquals("build", closestMatch("buidl", listOf("build", "test", "fmt")))
  }

  @Test
  fun returnsCandidateForLongerTypoWithinThreshold() {
    // longer input, adaptive threshold 2.
    assertEquals("dependencies", closestMatch("dependncies", listOf("dependencies")))
  }

  @Test
  fun returnsNullWhenAllCandidatesExceedThreshold() {
    // "zzzzz" vs "build" / "test" — distance > 2 for both, no match
    assertNull(closestMatch("zzzzz", listOf("build", "test")))
  }

  @Test
  fun returnsNullForEmptyCandidates() {
    assertNull(closestMatch("anything", emptyList()))
  }

  @Test
  fun returnsLexFirstForTiedDistances() {
    // both "alpha" and "alphb" are distance 1 from "alphz"; sorted input
    // returns the first encountered which happens to be lex-smallest.
    val candidates = listOf("alpha", "alphb")
    assertEquals("alpha", closestMatch("alphz", candidates))
  }

  @Test
  fun shorterInputUsesTighterThreshold() {
    // input length 3 (<=4) → threshold 1
    // "abc" vs "xyz" is distance 3 → null even though longer-input mode would allow distance 2
    assertNull(closestMatch("abc", listOf("xyz")))
  }

  @Test
  fun explicitMaxDistanceOverridesAdaptive() {
    // override threshold to 3 — "abc"/"xyz" distance 3 now matches.
    assertEquals("xyz", closestMatch("abc", listOf("xyz"), maxDistance = 3))
  }

  @Test
  fun adaptiveThresholdBoundary() {
    // length 4 → threshold 1; length 5 → threshold 2.
    assertEquals(1, adaptiveThreshold(4))
    assertEquals(2, adaptiveThreshold(5))
    assertEquals(1, adaptiveThreshold(1))
    assertEquals(2, adaptiveThreshold(100))
  }

  @Test
  fun deterministicOrderingFromCallerInput() {
    // Same input with same candidate list always returns the same result.
    // "buidl" → "build" exercises a length-5 input (adaptive threshold 2)
    // matching at edit distance 2.
    val candidates = listOf("build", "check", "clean", "fmt", "info", "run", "test")
    assertEquals("build", closestMatch("buidl", candidates))
    assertEquals("build", closestMatch("buidl", candidates))
  }
}
