package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class MetadataParseError(val message: String)

data class MetadataResolution(val version: String, val fallbackToPrerelease: Boolean = false)

// Maven Central's `<release>` is "latest non-SNAPSHOT" — which still
// surfaces -rc / -alpha / -beta / -M\d+ etc. and contradicts kolt's
// documented "latest stable" contract for `kolt add <ga>` (issue #354).
// Always derive the answer from `<versions>` when it's present so we can
// filter by qualifier; fall back to `<release>` / `<latest>` only for
// malformed metadata that lacks the list entirely.
fun parseMetadataXml(xml: String): Result<MetadataResolution, MetadataParseError> {
  val versions = extractAllVersions(xml)
  if (versions.isNotEmpty()) {
    val stable = versions.filter { isStableVersion(it) }
    val candidates = stable.ifEmpty { versions }
    val maxByCompare = candidates.reduce { acc, v -> if (compareVersions(v, acc) > 0) v else acc }
    // Multi-flavor artefacts (e.g. Guava `33.6.0-jre` / `33.6.0-android`)
    // share a numeric tuple and tie under `compareVersions`. The
    // publisher's `<release>` tag is the authoritative tiebreak — without
    // this preference, picking from XML order silently flips the chosen
    // variant on artefacts whose `<versions>` list isn't `<release>`-last.
    val release = extractSimpleTag(xml, "release")
    val tied = candidates.filter { compareVersions(it, maxByCompare) == 0 }
    val picked = if (release != null && release in tied) release else maxByCompare
    return Ok(MetadataResolution(picked, fallbackToPrerelease = !isStableVersion(picked)))
  }
  val release = extractSimpleTag(xml, "release")
  if (release != null) {
    return Ok(MetadataResolution(release, fallbackToPrerelease = !isStableVersion(release)))
  }
  val latest = extractSimpleTag(xml, "latest")
  if (latest != null) {
    return Ok(MetadataResolution(latest, fallbackToPrerelease = !isStableVersion(latest)))
  }
  return Err(MetadataParseError("No release or latest version found in metadata"))
}

fun buildMetadataDownloadUrl(group: String, artifact: String, baseUrl: String): String {
  val groupPath = group.replace('.', '/')
  return "$baseUrl/$groupPath/$artifact/maven-metadata.xml"
}

private fun extractAllVersions(xml: String): List<String> {
  val open = xml.indexOf("<versions>")
  if (open < 0) return emptyList()
  val close = xml.indexOf("</versions>", open)
  if (close < 0) return emptyList()
  val block = xml.substring(open + "<versions>".length, close)
  val results = mutableListOf<String>()
  var cursor = 0
  while (cursor < block.length) {
    val tagOpen = block.indexOf("<version>", cursor)
    if (tagOpen < 0) break
    val contentStart = tagOpen + "<version>".length
    val tagClose = block.indexOf("</version>", contentStart)
    if (tagClose < 0) break
    results.add(block.substring(contentStart, tagClose).trim())
    cursor = tagClose + "</version>".length
  }
  return results
}

private fun extractSimpleTag(xml: String, tagName: String): String? {
  val openTag = "<$tagName>"
  val closeTag = "</$tagName>"
  val start = xml.indexOf(openTag)
  if (start == -1) return null
  val contentStart = start + openTag.length
  val end = xml.indexOf(closeTag, contentStart)
  if (end == -1) return null
  return xml.substring(contentStart, end).trim()
}
