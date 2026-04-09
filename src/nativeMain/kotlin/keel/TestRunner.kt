package keel

data class TestRunCommand(
    val args: List<String>
)

fun testRunCommand(
    mainJarPath: String,
    testJarPath: String,
    consoleLauncherPath: String,
    classpath: String? = null,
    testArgs: List<String> = emptyList()
): TestRunCommand {
    val cp = buildList {
        add(mainJarPath)
        add(testJarPath)
        if (!classpath.isNullOrEmpty()) add(classpath)
    }.joinToString(":")
    val args = buildList {
        add("java")
        add("-jar")
        add(consoleLauncherPath)
        add("--class-path")
        add(cp)
        add("--scan-class-path")
        addAll(testArgs)
    }
    return TestRunCommand(args = args)
}
