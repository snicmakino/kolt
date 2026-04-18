package kolt.build

import kolt.config.KoltConfig
import kolt.config.jvmMainClass

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
        args = listOf(javaPath ?: "java", "-cp", cp, jvmMainClass(config.build.main)) + appArgs
    )
}

fun nativeRunCommand(
    config: KoltConfig,
    appArgs: List<String> = emptyList()
): RunCommand = RunCommand(args = listOf(outputKexePath(config)) + appArgs)

fun nativeTestRunCommand(
    config: KoltConfig,
    testArgs: List<String> = emptyList()
): RunCommand = RunCommand(args = listOf(outputNativeTestKexePath(config)) + testArgs)
