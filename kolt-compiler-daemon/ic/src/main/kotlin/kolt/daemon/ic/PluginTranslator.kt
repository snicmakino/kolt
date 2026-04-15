@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

// Translates a project's `kolt.toml` [plugins] section into the BTA-shaped
// `List<CompilerPlugin>` that BtaIncrementalCompiler attaches to
// `COMPILER_PLUGINS` on the JvmCompilerArguments builder. Lives inside the
// adapter per ADR 0019 §9 so daemon core never has to carry a `pluginClasspaths:
// List<Path>` field whose shape is dictated by BTA.
//
// The translator is a pure function over (projectRoot, jarResolver). Making the
// jar resolver injectable keeps the unit tests independent of any real plugin
// jars on disk, and lets daemon startup wire in a real resolver (e.g. one that
// walks a plugin-jars directory delivered alongside --compiler-jars). B-2a
// ships with a trivial `NoopJarResolver` that returns empty classpaths so the
// BtaIncrementalCompiler call site can still exercise the translation path; a
// real resolver is a B-2c / daemon-core concern.
object PluginTranslator {

    // Kotlin compiler plugin IDs are stable strings the compiler itself uses to
    // route -P options. `kotlinx-serialization` is the canonical ID for the
    // serialization plugin across Kotlin 1.x/2.x; the `allopen` / `noarg` IDs
    // are read from `AllOpenPluginNames.PLUGIN_ID` / `NoArgPluginNames.PLUGIN_ID`
    // in the Kotlin source tree (verified against v2.3.20). Changing any of
    // these would break every build that depends on the plugin, so they are
    // safe to hard-code.
    const val SERIALIZATION_PLUGIN_ID = "org.jetbrains.kotlinx.serialization"
    const val ALLOPEN_PLUGIN_ID = "org.jetbrains.kotlin.allopen"
    const val NOARG_PLUGIN_ID = "org.jetbrains.kotlin.noarg"

    // Alias that kolt.toml users type. Aliases mirror the native client's
    // `PLUGIN_JAR_NAMES` map in `kolt.build.CompilerPlugin` so a project's
    // `[plugins]` section means the same thing on both the daemon and the
    // subprocess fallback path.
    private val aliasToPluginId: Map<String, String> = mapOf(
        "serialization" to SERIALIZATION_PLUGIN_ID,
        "allopen" to ALLOPEN_PLUGIN_ID,
        "noarg" to NOARG_PLUGIN_ID,
    )

    /**
     * Parse `projectRoot/kolt.toml`, project its [plugins] map to the enabled
     * entries, and ask [jarResolver] for the classpath of each. Returns the
     * list of `CompilerPlugin` instances to attach to the compile operation.
     *
     * Non-existent `kolt.toml`, absent `[plugins]` section, and plugin entries
     * set to `false` all collapse to an empty output — the resolver is never
     * called in those cases, which makes the common "no plugins" path free.
     */
    fun translate(
        projectRoot: Path,
        jarResolver: (alias: String) -> List<Path>,
    ): List<CompilerPlugin> {
        val tomlFile = projectRoot.resolve("kolt.toml")
        if (!Files.isRegularFile(tomlFile)) return emptyList()
        val enabledAliases = parseEnabledPluginAliases(tomlFile)
        if (enabledAliases.isEmpty()) return emptyList()
        return enabledAliases.map { alias ->
            val pluginId = aliasToPluginId[alias]
                // Unknown alias: still emit a CompilerPlugin keyed by the raw
                // alias string so the BTA layer surfaces a plugin-not-found
                // error rather than silently dropping the user's request.
                ?: alias
            CompilerPlugin(
                pluginId,
                jarResolver(alias),
                /* rawArguments = */ emptyList(),
                /* orderingRequirements = */ Collections.emptySet(),
            )
        }
    }

    private fun parseEnabledPluginAliases(tomlFile: Path): List<String> {
        val raw = Files.readString(tomlFile)
        val decoded = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
            .decodeFromString(KoltConfigPluginsView.serializer(), raw)
        return decoded.plugins
            .filterValues { it }
            .keys
            .toList()
    }

    @Serializable
    private data class KoltConfigPluginsView(
        // Only the [plugins] section matters to this translator; `ignoreUnknownNames`
        // lets every other kolt.toml field pass through without a schema definition.
        @SerialName("plugins") val plugins: Map<String, Boolean> = emptyMap(),
    )
}
