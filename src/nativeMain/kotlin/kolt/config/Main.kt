package kolt.config

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

// Derives the JVM facade class from a Kotlin FQN, assuming the file is Main.kt.
fun jvmMainClass(main: String): String {
    val prefix = main.substringBeforeLast("main")
    return "${prefix}MainKt"
}

fun validateMainFqn(main: String): Result<Unit, ConfigError.ParseFailed> {
    if (main.endsWith("Kt")) {
        return Err(ConfigError.ParseFailed(
            "main = \"$main\" is a JVM class name. " +
                "Use a Kotlin function FQN instead: main = \"${jvmClassToFqnHint(main)}\""
        ))
    }
    if (main != "main" && !main.endsWith(".main")) {
        return Err(ConfigError.ParseFailed(
            "main = \"$main\" is not a valid Kotlin function FQN " +
                "(expected \"main\" or \"<package>.main\")"
        ))
    }
    return Ok(Unit)
}

private fun jvmClassToFqnHint(main: String): String {
    val withoutKt = main.removeSuffix("Kt")
    val dot = withoutKt.lastIndexOf('.')
    return if (dot < 0) "main" else "${withoutKt.substring(0, dot)}.main"
}
