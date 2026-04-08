package keel

fun generateKeelToml(projectName: String): String = buildString {
    appendLine("""name = "$projectName"""")
    appendLine("""version = "0.1.0"""")
    appendLine("""kotlin = "2.1.0"""")
    appendLine("""target = "jvm"""")
    appendLine("""jvm_target = "17"""")
    appendLine("""main = "MainKt"""")
    appendLine("""sources = ["src"]""")
}

fun generateMainKt(): String = buildString {
    appendLine("fun main() {")
    appendLine("""    println("Hello, world!")""")
    appendLine("}")
}

fun inferProjectName(dirPath: String): String =
    dirPath.trimEnd('/').substringAfterLast('/')
