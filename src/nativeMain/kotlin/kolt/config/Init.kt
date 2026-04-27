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
    appendLine("""jvm_target = "17"""")
  }
  if (kind == ScaffoldKind.APP) {
    appendLine("""main = "main"""")
  }
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

fun generateLibKt(): String = buildString {
  appendLine("""fun greet(): String = "Hello, world!"""")
}

fun generateLibTestKt(): String = buildString {
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

fun generateGitignore(): String = "build/\n"

fun inferProjectName(dirPath: String): String {
  val name = dirPath.trimEnd('/').substringAfterLast('/')
  return name.ifEmpty { "project" }
}

private val validProjectNamePattern = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]*$""")

fun isValidProjectName(name: String): Boolean =
  name.isNotEmpty() && validProjectNamePattern.matches(name)
