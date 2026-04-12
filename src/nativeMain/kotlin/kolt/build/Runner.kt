package kolt.build

import kolt.config.KoltConfig

data class RunCommand(
    val args: List<String>
)

fun runCommand(
    config: KoltConfig,
    classpath: String? = null,
    appArgs: List<String> = emptyList(),
    javaPath: String? = null
): RunCommand {
    val cp = if (!classpath.isNullOrEmpty()) "$CLASSES_DIR:$classpath" else CLASSES_DIR
    return RunCommand(
        args = listOf(javaPath ?: "java", "-cp", cp, config.main) + appArgs
    )
}

fun nativeRunCommand(
    config: KoltConfig,
    appArgs: List<String> = emptyList()
): RunCommand = RunCommand(args = listOf(outputKexePath(config)) + appArgs)

// Invokes the test kexe produced by nativeTestBuildCommand. testArgs are passed
// through verbatim so users can supply kotlin.native test flags such as
// --ktest_filter, --ktest_logger, --ktest_negative_filter.
fun nativeTestRunCommand(
    config: KoltConfig,
    testArgs: List<String> = emptyList()
): RunCommand = RunCommand(args = listOf(outputNativeTestKexePath(config)) + testArgs)
