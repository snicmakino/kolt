package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class UnknownPlugin(val name: String)

private val PLUGIN_JAR_NAMES = mapOf(
    "serialization" to "kotlinx-serialization-compiler-plugin.jar",
    "allopen" to "allopen-compiler-plugin.jar",
    "noarg" to "noarg-compiler-plugin.jar"
)

fun pluginArgs(
    plugins: Map<String, Boolean>,
    kotlinHome: String
): Result<List<String>, UnknownPlugin> {
    val args = mutableListOf<String>()
    for ((name, enabled) in plugins) {
        if (!enabled) continue
        val jarName = PLUGIN_JAR_NAMES[name] ?: return Err(UnknownPlugin(name))
        args.add("-Xplugin=$kotlinHome/lib/$jarName")
    }
    return Ok(args)
}

fun parseKotlinHome(kotlincPath: String): String {
    return kotlincPath.removeSuffix("/bin/kotlinc")
}
