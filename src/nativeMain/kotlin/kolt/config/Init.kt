package kolt.config

enum class ScaffoldKind(val tomlValue: String) {
  APP("app"),
  LIB("lib"),
}

const val DEFAULT_SCAFFOLD_TARGET = "jvm"

// Pinned to match `BUNDLED_DAEMON_KOTLIN_VERSION` in `kolt.cli` so a fresh
// `kolt init` project hits the libexec BTA-impl bundle on first build (no
// Maven Central round trip). Drift from the bundled version only costs
// new users a one-time download; it does not break the daemon path.
private const val INIT_TEMPLATE_KOTLIN_VERSION = "2.3.20"

fun generateKoltToml(
  projectName: String,
  kind: ScaffoldKind = ScaffoldKind.APP,
  target: String = DEFAULT_SCAFFOLD_TARGET,
  group: String? = null,
): String = buildString {
  appendLine("""name = "$projectName"""")
  appendLine("""version = "0.1.0"""")
  if (kind == ScaffoldKind.LIB) {
    appendLine("""kind = "lib"""")
  }
  appendLine()
  appendLine("[kotlin]")
  appendLine("""version = "$INIT_TEMPLATE_KOTLIN_VERSION"""")
  appendLine()
  appendLine("[build]")
  appendLine("""target = "$target"""")
  if (target == "jvm") {
    appendLine("""jvm_target = "25"""")
  }
  if (kind == ScaffoldKind.APP) {
    val mainFqn =
      if (group == null) "main" else "$group.${projectNameToPackageSegment(projectName)}.main"
    appendLine("""main = "$mainFqn"""")
  }
  appendLine("""sources = ["src"]""")
}

fun generateTestKt(packageDecl: String? = null): String = buildString {
  if (packageDecl != null) {
    appendLine("package $packageDecl")
    appendLine()
  }
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

fun generateMainKt(packageDecl: String? = null): String = buildString {
  if (packageDecl != null) {
    appendLine("package $packageDecl")
    appendLine()
  }
  appendLine("fun main() {")
  appendLine("""    println("Hello, world!")""")
  appendLine("}")
}

fun generateLibKt(packageDecl: String? = null): String = buildString {
  if (packageDecl != null) {
    appendLine("package $packageDecl")
    appendLine()
  }
  appendLine("""fun greet(): String = "Hello, world!"""")
}

fun generateLibTestKt(packageDecl: String? = null): String = buildString {
  if (packageDecl != null) {
    appendLine("package $packageDecl")
    appendLine()
  }
  appendLine("import kotlin.test.Test")
  appendLine("import kotlin.test.assertEquals")
  appendLine()
  appendLine("class LibTest {")
  appendLine("    @Test")
  appendLine("    fun greetReturnsHello() {")
  appendLine("""        assertEquals("Hello, world!", greet())""")
  appendLine("    }")
  appendLine("}")
}

fun generateGitignore(): String =
  """
  build/
  workspace.json
  .idea/
  *.iml
  .DS_Store
  """
    .trimIndent() + "\n"

fun inferProjectName(dirPath: String): String {
  val name = dirPath.trimEnd('/').substringAfterLast('/')
  return name.ifEmpty { "project" }
}

private val validProjectNamePattern = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]*$""")

fun isValidProjectName(name: String): Boolean =
  name.isNotEmpty() && validProjectNamePattern.matches(name)

// Lowers a kolt project name (validated by isValidProjectName) into a
// Kotlin package segment. Hyphens and dots become underscores; a leading
// digit gets an underscore prefix because Kotlin identifiers must not
// start with a digit. Total — assumes the caller has validated the name.
fun projectNameToPackageSegment(name: String): String {
  val replaced = name.replace('-', '_').replace('.', '_')
  return if (replaced[0].isDigit()) "_$replaced" else replaced
}

private val packageSegmentPattern = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

// Kotlin hard keywords cannot appear unbackticked in a package declaration.
// Generating `package com.is.example` would compile-fail on the very first
// build, so the parser must catch these short segment names that real users
// reach for (com.is, com.in, etc.).
private val KOTLIN_HARD_KEYWORDS =
  setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
  )

fun isValidGroup(group: String): Boolean {
  if (group.isEmpty()) return false
  return group.split('.').all { segment ->
    packageSegmentPattern.matches(segment) && segment !in KOTLIN_HARD_KEYWORDS
  }
}
