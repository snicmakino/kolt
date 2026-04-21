package kolt.build

import kolt.config.KoltConfig
import kolt.config.jvmMainClass

data class RunCommand(
    val args: List<String>
)

// `main` is passed explicitly (not read from `config.build.main`) because
// `BuildSection.main` became nullable in the lib-build-pipeline spec; the
// kind gate and null-check live at the caller per ADR 0001.
fun runCommand(
    config: KoltConfig,
    main: String,
    classpath: String? = null,
    appArgs: List<String> = emptyList(),
    javaPath: String? = null
): RunCommand {
    val cp = if (!classpath.isNullOrEmpty()) "$CLASSES_DIR:$classpath" else CLASSES_DIR
    return RunCommand(
        args = listOf(javaPath ?: "java", "-cp", cp, jvmMainClass(main)) + appArgs
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
