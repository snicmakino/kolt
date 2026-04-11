package keel.build

import keel.config.KeelConfig

data class RunCommand(
    val args: List<String>
)

fun runCommand(
    config: KeelConfig,
    classpath: String? = null,
    appArgs: List<String> = emptyList(),
    javaPath: String? = null
): RunCommand {
    val cp = if (!classpath.isNullOrEmpty()) "$CLASSES_DIR:$classpath" else CLASSES_DIR
    return RunCommand(
        args = listOf(javaPath ?: "java", "-cp", cp, config.main) + appArgs
    )
}
