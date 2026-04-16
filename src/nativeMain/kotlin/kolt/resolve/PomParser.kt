package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class PomExclusion(
    val groupId: String,
    val artifactId: String
)

data class PomDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String?,
    val optional: Boolean,
    val exclusions: List<PomExclusion> = emptyList()
)

data class PomParent(
    val groupId: String,
    val artifactId: String,
    val version: String
)

data class PomInfo(
    val parent: PomParent?,
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val properties: Map<String, String>,
    val dependencyManagement: List<PomDependency>,
    val dependencies: List<PomDependency>
)

data class PomParseError(val message: String)

fun parsePom(xml: String): Result<PomInfo, PomParseError> {
    val cleaned = stripComments(stripProcessingInstructions(xml))
    val root = parseElement(cleaned, 0) ?: return Err(PomParseError("Failed to parse root element"))

    if (root.name != "project") {
        return Err(PomParseError("Root element is not <project>"))
    }

    val projectGroupId = root.childText("groupId")
    val projectArtifactId = root.childText("artifactId")
        ?: return Err(PomParseError("Missing <artifactId>"))
    val projectVersion = root.childText("version")

    val properties = mutableMapOf<String, String>()
    root.child("properties")?.children?.forEach { prop ->
        val text = prop.text
        if (text != null) {
            properties[prop.name] = text
        }
    }

    val interpolationMap = buildInterpolationMap(properties, projectGroupId, projectVersion)

    val parent = root.child("parent")?.let { p ->
        val pg = p.childText("groupId") ?: return Err(PomParseError("Parent missing <groupId>"))
        val pa = p.childText("artifactId") ?: return Err(PomParseError("Parent missing <artifactId>"))
        val pv = p.childText("version") ?: return Err(PomParseError("Parent missing <version>"))
        PomParent(interpolate(pg, interpolationMap), interpolate(pa, interpolationMap), interpolate(pv, interpolationMap))
    }

    val depMgmt = root.child("dependencyManagement")
        ?.child("dependencies")
        ?.children
        ?.filter { it.name == "dependency" }
        ?.map { parseDependencyElement(it, interpolationMap) }
        ?: emptyList()

    val deps = root.child("dependencies")
        ?.children
        ?.filter { it.name == "dependency" }
        ?.map { parseDependencyElement(it, interpolationMap) }
        ?: emptyList()

    return Ok(PomInfo(parent, projectGroupId, projectArtifactId, projectVersion, properties, depMgmt, deps))
}

private fun buildInterpolationMap(
    properties: Map<String, String>,
    groupId: String?,
    version: String?
): Map<String, String> {
    val map = properties.toMutableMap()
    if (groupId != null) {
        map["project.groupId"] = groupId
    }
    if (version != null) {
        map["project.version"] = version
    }
    return map
}

private fun parseDependencyElement(elem: XmlElement, interpolationMap: Map<String, String>): PomDependency {
    val groupId = interpolate(elem.childText("groupId") ?: "", interpolationMap)
    val artifactId = interpolate(elem.childText("artifactId") ?: "", interpolationMap)
    val version = elem.childText("version")?.let { interpolate(it, interpolationMap) }
    val scope = elem.childText("scope")?.let { interpolate(it, interpolationMap) }
    val optional = elem.childText("optional")?.let { interpolate(it, interpolationMap) } == "true"
    val exclusions = elem.child("exclusions")
        ?.children
        ?.filter { it.name == "exclusion" }
        ?.map { ex ->
            PomExclusion(
                groupId = interpolate(ex.childText("groupId") ?: "", interpolationMap),
                artifactId = interpolate(ex.childText("artifactId") ?: "", interpolationMap)
            )
        }
        ?: emptyList()
    return PomDependency(groupId, artifactId, version, scope, optional, exclusions)
}

private fun interpolate(value: String, properties: Map<String, String>): String {
    var result = value
    repeat(10) {
        val next = PROPERTY_REGEX.replace(result) { match ->
            val key = match.groupValues[1]
            properties[key] ?: match.value
        }
        if (next == result) return result
        result = next
    }
    return result
}

private val PROPERTY_REGEX = Regex("""\$\{([^}]+)}""")

private class XmlElement(
    val name: String,
    val text: String?,
    val children: List<XmlElement>
) {
    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun childText(name: String): String? = child(name)?.text
}

private fun stripProcessingInstructions(xml: String): String {
    return xml.replace(Regex("""<\?[^?]*\?>"""), "")
}

private fun stripComments(xml: String): String {
    return xml.replace(Regex("""<!--[\s\S]*?-->"""), "")
}

private fun parseElement(xml: String, start: Int): XmlElement? {
    val tagStart = xml.indexOf('<', start)
    if (tagStart == -1) return null

    val selfCloseMatch = Regex("""<(\w[\w.\-]*)(\s[^>]*)?\s*/>""").find(xml, tagStart)
    if (selfCloseMatch != null && selfCloseMatch.range.first == tagStart) {
        return XmlElement(selfCloseMatch.groupValues[1], null, emptyList())
    }

    val openMatch = Regex("""<(\w[\w.\-]*)(\s[^>]*)?>""").find(xml, tagStart) ?: return null
    if (openMatch.range.first != tagStart) return null

    val tagName = openMatch.groupValues[1]
    val closeTag = "</$tagName>"
    val contentStart = openMatch.range.last + 1

    val closeIndex = findMatchingClose(xml, tagName, contentStart)
    if (closeIndex == -1) return null

    val content = xml.substring(contentStart, closeIndex)

    val children = mutableListOf<XmlElement>()
    var pos = 0
    while (pos < content.length) {
        val nextTag = content.indexOf('<', pos)
        if (nextTag == -1) break

        val child = parseElement(content, nextTag)
        if (child != null) {
            children.add(child)
            val childClose = findElementEnd(content, child.name, nextTag)
            pos = if (childClose != -1) childClose else content.length
        } else {
            break
        }
    }

    val text = if (children.isEmpty()) content.trim().ifEmpty { null } else null
    return XmlElement(tagName, text, children)
}

private fun findMatchingClose(xml: String, tagName: String, start: Int): Int {
    var depth = 1
    var pos = start
    val openPattern = "<$tagName"
    val closePattern = "</$tagName>"
    while (pos < xml.length && depth > 0) {
        val nextOpen = xml.indexOf(openPattern, pos)
        val nextClose = xml.indexOf(closePattern, pos)
        if (nextClose == -1) return -1

        if (nextOpen != -1 && nextOpen < nextClose) {
            val afterOpen = nextOpen + openPattern.length
            if (afterOpen < xml.length && (xml[afterOpen] == '>' || xml[afterOpen] == ' ' || xml[afterOpen] == '/')) {
                val closeBracket = xml.indexOf('>', afterOpen)
                if (closeBracket != -1 && xml[closeBracket - 1] == '/') {
                    pos = closeBracket + 1
                } else {
                    depth++
                    pos = afterOpen
                }
            } else {
                pos = afterOpen
            }
        } else {
            depth--
            if (depth == 0) return nextClose
            pos = nextClose + closePattern.length
        }
    }
    return -1
}

private fun findElementEnd(xml: String, tagName: String, start: Int): Int {
    val selfCloseMatch = Regex("""<\w[\w.\-]*(\s[^>]*)?\s*/>""").find(xml, start)
    if (selfCloseMatch != null && selfCloseMatch.range.first == start) {
        return selfCloseMatch.range.last + 1
    }

    val openMatch = Regex("""<\w[\w.\-]*(\s[^>]*)?>""").find(xml, start) ?: return -1
    if (openMatch.range.first != start) return -1

    val contentStart = openMatch.range.last + 1
    val closeIndex = findMatchingClose(xml, tagName, contentStart)
    if (closeIndex == -1) return -1
    return closeIndex + "</$tagName>".length
}
