package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class MetadataParseError(val message: String)

fun parseMetadataXml(xml: String): Result<String, MetadataParseError> {
    val release = extractSimpleTag(xml, "release")
    if (release != null) return Ok(release)

    val latest = extractSimpleTag(xml, "latest")
    if (latest != null) return Ok(latest)

    return Err(MetadataParseError("No release or latest version found in metadata"))
}

fun buildMetadataDownloadUrl(group: String, artifact: String): String {
    val groupPath = group.replace('.', '/')
    return "$MAVEN_CENTRAL_BASE/$groupPath/$artifact/maven-metadata.xml"
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
