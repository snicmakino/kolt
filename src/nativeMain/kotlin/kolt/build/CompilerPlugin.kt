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

// #65 daemon path: returns enabled aliases mapped to their resolved
// plugin jar classpaths. The daemon's `--plugin-jars` flag expects this
// shape (see Main.parsePluginJars), and `PluginTranslator` reads it as
// alias → List<Path> when building BTA `CompilerPlugin` instances.
// Today the lookup is the same kotlinc-sidecar `<lib>/<jar>.jar` mapping
// pluginArgs uses; replacing this with a Maven fixed-coordinate fetcher
// is the rest of #65 and only requires updating this function (the
// daemon wiring is alias-shaped on both sides).
fun pluginJarPaths(
    plugins: Map<String, Boolean>,
    kotlinHome: String,
): Result<Map<String, List<String>>, UnknownPlugin> {
    val out = linkedMapOf<String, List<String>>()
    for ((name, enabled) in plugins) {
        if (!enabled) continue
        val jarName = PLUGIN_JAR_NAMES[name] ?: return Err(UnknownPlugin(name))
        out[name] = listOf("$kotlinHome/lib/$jarName")
    }
    return Ok(out)
}

fun parseKotlinHome(kotlincPath: String): String {
    return kotlincPath.removeSuffix("/bin/kotlinc")
}
