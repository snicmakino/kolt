package kolt.resolve

fun compareVersions(a: String, b: String): Int {
    val partsA = splitVersion(a)
    val partsB = splitVersion(b)

    val maxLen = maxOf(partsA.size, partsB.size)
    for (i in 0 until maxLen) {
        val pa = if (i < partsA.size) partsA[i] else VersionSegment.Numeric(0)
        val pb = if (i < partsB.size) partsB[i] else VersionSegment.Numeric(0)
        val cmp = pa.compareTo(pb)
        if (cmp != 0) return cmp
    }
    return 0
}

private sealed class VersionSegment : Comparable<VersionSegment> {
    data class Numeric(val value: Long) : VersionSegment()
    data class Qualifier(val rank: Int, val numericSuffix: Long) : VersionSegment()
    data object Release : VersionSegment()

    override fun compareTo(other: VersionSegment): Int {
        return sortKey().compareTo(other.sortKey())
    }

    private fun sortKey(): Pair<Int, Long> = when (this) {
        is Numeric -> Pair(RANK_NUMERIC, value)
        is Qualifier -> Pair(rank, numericSuffix)
        is Release -> Pair(RANK_RELEASE, 0L)
    }

    private fun Pair<Int, Long>.compareTo(other: Pair<Int, Long>): Int {
        val cmp = first.compareTo(other.first)
        return if (cmp != 0) cmp else second.compareTo(other.second)
    }

    companion object {
        const val RANK_SNAPSHOT = 0
        const val RANK_ALPHA = 1
        const val RANK_BETA = 2
        const val RANK_RC = 3
        const val RANK_RELEASE = 4
        const val RANK_NUMERIC = 5
    }
}

private fun splitVersion(version: String): List<VersionSegment> {
    val segments = mutableListOf<VersionSegment>()
    val tokens = version.split('.', '-')
    for (token in tokens) {
        segments.add(parseSegment(token))
    }
    return segments
}

private fun parseSegment(token: String): VersionSegment {
    token.toLongOrNull()?.let { return VersionSegment.Numeric(it) }

    val lower = token.lowercase()
    val qualifierMatch = Regex("""^(snapshot|alpha|beta|rc)(\d*)$""").find(lower)
    if (qualifierMatch != null) {
        val name = qualifierMatch.groupValues[1]
        val suffix = qualifierMatch.groupValues[2].toLongOrNull() ?: 0L
        val rank = when (name) {
            "snapshot" -> VersionSegment.RANK_SNAPSHOT
            "alpha" -> VersionSegment.RANK_ALPHA
            "beta" -> VersionSegment.RANK_BETA
            "rc" -> VersionSegment.RANK_RC
            else -> VersionSegment.RANK_RELEASE
        }
        return VersionSegment.Qualifier(rank, suffix)
    }

    return VersionSegment.Qualifier(VersionSegment.RANK_RELEASE, 0L)
}

data class VersionInterval(
    val from: String?,
    val fromInclusive: Boolean,
    val to: String?,
    val toInclusive: Boolean
) {
    fun contains(version: String): Boolean {
        if (from != null) {
            val cmp = compareVersions(version, from)
            if (fromInclusive && cmp < 0) return false
            if (!fromInclusive && cmp <= 0) return false
        }
        if (to != null) {
            val cmp = compareVersions(version, to)
            if (toInclusive && cmp > 0) return false
            if (!toInclusive && cmp >= 0) return false
        }
        return true
    }
}

data class VersionConstraint(
    val preferred: String?,
    val interval: VersionInterval?
)

fun parseVersionConstraint(constraint: String): VersionConstraint {
    val trimmed = constraint.trim()
    if (trimmed.isEmpty()) return VersionConstraint(trimmed, null)

    val first = trimmed[0]
    if (first != '[' && first != '(') {
        return VersionConstraint(trimmed, null)
    }

    val last = trimmed.last()
    val fromInclusive = first == '['
    val toInclusive = last == ']'
    val inner = trimmed.substring(1, trimmed.length - 1)

    val commaIndex = inner.indexOf(',')
    if (commaIndex == -1) {
        val version = inner.trim()
        return VersionConstraint(null, VersionInterval(version, true, version, true))
    }

    val fromStr = inner.substring(0, commaIndex).trim()
    val toStr = inner.substring(commaIndex + 1).trim()

    return VersionConstraint(
        null,
        VersionInterval(
            from = fromStr.ifEmpty { null },
            fromInclusive = fromInclusive,
            to = toStr.ifEmpty { null },
            toInclusive = toInclusive
        )
    )
}

fun selectVersion(constraint: String): String {
    val vc = parseVersionConstraint(constraint)
    if (vc.preferred != null) return vc.preferred
    val interval = vc.interval ?: return constraint
    return interval.from ?: interval.to ?: constraint
}

// A Gradle `rejects` pattern may be either an exact version ("1.6.0") or a
// Maven-style interval ("[1.0.0,1.5.0)"). parseVersionConstraint already
// handles both shapes — this helper just dispatches on which one it parsed.
fun matchesRejectPattern(version: String, rejectPattern: String): Boolean {
    val vc = parseVersionConstraint(rejectPattern)
    return when {
        vc.interval != null -> vc.interval.contains(version)
        vc.preferred != null -> vc.preferred == version
        else -> false
    }
}
