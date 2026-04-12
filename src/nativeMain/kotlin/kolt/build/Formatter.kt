package kolt.build

data class FormatCommand(
    val args: List<String>
)

fun formatCommand(
    ktfmtJarPath: String,
    files: List<String>,
    checkOnly: Boolean,
    style: String = "google"
): FormatCommand {
    val args = buildList {
        add("java")
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
