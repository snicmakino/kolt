package kolt.config

fun generateKoltToml(projectName: String): String = buildString {
    appendLine("""name = "$projectName"""")
    appendLine("""version = "0.1.0"""")
    appendLine("""kotlin = "2.1.0"""")
    appendLine("""target = "jvm"""")
    appendLine("""jvm_target = "17"""")
    appendLine("""main = "MainKt"""")
    appendLine("""sources = ["src"]""")
}

fun generateTestKt(): String = buildString {
    appendLine("import kotlin.test.Test")
    appendLine("import kotlin.test.assertEquals")
    appendLine()
    appendLine("class MainTest {")
    appendLine("    @Test")
    appendLine("    fun example() {")
    appendLine("        assertEquals(4, 2 + 2)")
    appendLine("    }")
    appendLine("}")
}

fun generateMainKt(): String = buildString {
    appendLine("fun main() {")
    appendLine("""    println("Hello, world!")""")
    appendLine("}")
}

fun inferProjectName(dirPath: String): String {
    val name = dirPath.trimEnd('/').substringAfterLast('/')
    return name.ifEmpty { "project" }
}

private val validProjectNamePattern = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]*$""")

fun isValidProjectName(name: String): Boolean =
    name.isNotEmpty() && validProjectNamePattern.matches(name)
