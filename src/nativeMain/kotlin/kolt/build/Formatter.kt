package kolt.build

data class FormatCommand(val args: List<String>)

fun formatCommand(
  ktfmtJarPath: String,
  files: List<String>,
  checkOnly: Boolean,
  style: String = "google",
  javaPath: String? = null,
  jdkMajorVersion: Int? = null,
): FormatCommand {
  val args = buildList {
    add(javaPath ?: "java")
    // ktfmt's bundled intellij-util fork calls `sun.misc.Unsafe::objectFieldOffset`,
    // which JEP 498 (JDK 23) starts warning about. The flag silences that
    // warning and is itself unrecognised on JDK <23, so gate before adding.
    if (jdkMajorVersion != null && jdkMajorVersion >= 23) {
      add("--sun-misc-unsafe-memory-access=allow")
    }
    add("-jar")
    add(ktfmtJarPath)
    add("--${style}-style")
    if (checkOnly) {
      add("--set-exit-if-changed")
      add("--dry-run")
    }
    addAll(files)
  }
  return FormatCommand(args = args)
}
