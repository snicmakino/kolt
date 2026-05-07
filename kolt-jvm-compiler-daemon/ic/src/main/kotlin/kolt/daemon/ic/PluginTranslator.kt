package kolt.daemon.ic

import java.nio.file.Path

// Emits one `-Xplugin=<jar>` per supplied jar, in map iteration order, for
// `CommonToolArguments.applyArgumentStrings` consumption (ADR 0019 §9).
//
// `-Xplugin=` is the passthrough surface present across the full BTA 2.3.x
// family; the structured `CommonCompilerArguments.COMPILER_PLUGINS` key was
// added only in 2.3.20 and rejects assignment on earlier impls. Plugin
// identity comes from the jar's
// `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`
// descriptor — the alias map's keys are not load-bearing here.
object PluginTranslator {

  fun translate(pluginJars: Map<String, List<Path>>): List<String> =
    pluginJars.values.flatMap { jars -> jars.map { "-Xplugin=$it" } }
}
