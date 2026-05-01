package kolt.build

data class TestRunCommand(val args: List<String>)

fun testRunCommand(
  classesDir: String,
  testClassesDir: String,
  consoleLauncherPath: String,
  testResourceDirs: List<String> = emptyList(),
  classpath: String? = null,
  testArgs: List<String> = emptyList(),
  javaPath: String? = null,
  sysProps: List<Pair<String, String>> = emptyList(),
): TestRunCommand {
  val cp =
    buildList {
        add(classesDir)
        add(testClassesDir)
        addAll(testResourceDirs)
        if (!classpath.isNullOrEmpty()) add(classpath)
      }
      .joinToString(":")
  // JUnit Platform Console Launcher 1.11 rejects `--scan-class-path`
  // together with any `--select-*` flag. Suppress the implicit scan so
  // `kolt test -- --select-class=...` is honoured. `--include-*` /
  // `--exclude-*` filters do not collide with class-path scanning, so
  // they continue to compose with the default scan.
  val hasExplicitSelector = testArgs.any { it.startsWith("--select-") }
  val args = buildList {
    add(javaPath ?: "java")
    for ((k, v) in sysProps) add("-D$k=$v")
    add("-jar")
    add(consoleLauncherPath)
    add("--class-path")
    add(cp)
    if (!hasExplicitSelector) add("--scan-class-path")
    addAll(testArgs)
  }
  return TestRunCommand(args = args)
}
