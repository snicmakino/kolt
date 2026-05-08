package kolt.infra.suggest

// Tighter threshold for very short inputs to avoid suggesting unrelated
// candidates: a 1-char input within distance 2 of every candidate
// would yield false positives. The cutoff at length 4 lets short
// CLI flags (`--no-color`, `--watch`) still get useful suggestions.
internal fun adaptiveThreshold(inputLength: Int): Int = if (inputLength <= 4) 1 else 2

// Returns the candidate with smallest edit distance from `input` if and only
// if that distance is within `maxDistance`; otherwise `null`. Determinism
// (R5.4): scan order is the caller-supplied list, ties resolve to the first
// encountered. Callers that need lex-ordered tie-break should pre-sort.
fun closestMatch(
  input: String,
  candidates: List<String>,
  maxDistance: Int = adaptiveThreshold(input.length),
): String? {
  var best: String? = null
  var bestDistance = Int.MAX_VALUE
  for (candidate in candidates) {
    val d = levenshtein(input, candidate)
    if (d < bestDistance) {
      bestDistance = d
      best = candidate
    }
  }
  return if (best != null && bestDistance <= maxDistance) best else null
}
