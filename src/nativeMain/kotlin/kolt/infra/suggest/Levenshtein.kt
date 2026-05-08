package kolt.infra.suggest

import kotlin.math.min

// Standard Wagner-Fischer dynamic-programming edit distance.
// Cost 1 for insertion, deletion, and substitution.
fun levenshtein(a: String, b: String): Int {
  if (a == b) return 0
  if (a.isEmpty()) return b.length
  if (b.isEmpty()) return a.length

  // Two-row optimisation: only the previous row is needed at any time.
  var prev = IntArray(b.length + 1) { it }
  var curr = IntArray(b.length + 1)
  for (i in 1..a.length) {
    curr[0] = i
    for (j in 1..b.length) {
      val substCost = if (a[i - 1] == b[j - 1]) 0 else 1
      curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + substCost)
    }
    val tmp = prev
    prev = curr
    curr = tmp
  }
  return prev[b.length]
}
