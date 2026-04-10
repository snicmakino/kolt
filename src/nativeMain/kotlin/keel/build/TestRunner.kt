package keel.build

data class TestRunCommand(
    val args: List<String>
)

fun testRunCommand(
    classesDir: String,
    testClassesDir: String,
    consoleLauncherPath: String,
    testResourceDirs: List<String> = emptyList(),
    classpath: String? = null,
    testArgs: List<String> = emptyList()
): TestRunCommand {
    val cp = buildList {
        add(classesDir)
        add(testClassesDir)
        addAll(testResourceDirs)
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
