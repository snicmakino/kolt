package kolt.config

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

// kolt.toml's `main` field holds a Kotlin top-level function FQN. The bare
// name "main" refers to `fun main()` in the root package; "com.example.main"
// refers to `fun main()` in `com.example`.
//
// For target = "native", konanc consumes the FQN directly as its `-e` flag.
// For target = "jvm", we derive the JVM facade class name assuming the file
// is named `Main.kt`, which is the init template's convention and the only
// shape kolt supports without `@JvmName`. A future `jvm_main_class` override
// is noted as YAGNI in the PR that introduced this scheme.
fun jvmMainClass(main: String): String {
    val prefix = main.substringBeforeLast("main")
    return "${prefix}MainKt"
}

// Validates that [main] is a Kotlin top-level function FQN. Either the bare
// literal "main", or a dotted package followed by ".main".
//
// Rejects JVM-style class names (anything ending in "Kt") with a migration
// hint showing the equivalent function FQN the user likely meant. Other
// malformed values get a generic error.
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

// Best-effort suggestion when the user supplied a JVM facade class name:
// drop the trailing "Kt" and replace the last segment with "main".
// "MainKt" → "main"; "com.example.MainKt" → "com.example.main";
// "com.example.AppKt" → "com.example.main".
private fun jvmClassToFqnHint(main: String): String {
    val withoutKt = main.removeSuffix("Kt")
    val dot = withoutKt.lastIndexOf('.')
    return if (dot < 0) "main" else "${withoutKt.substring(0, dot)}.main"
}
