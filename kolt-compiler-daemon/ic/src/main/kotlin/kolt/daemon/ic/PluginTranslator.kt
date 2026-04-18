package kolt.daemon.ic

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

// Translates a project's `kolt.toml` [kotlin.plugins] section into the `-Xplugin=<path>`
// freeArgs list that `BtaIncrementalCompiler` pushes through
// `CommonToolArguments.applyArgumentStrings`. Lives inside the adapter per
// ADR 0019 §9 so daemon core never carries a BTA-shaped `pluginClasspaths`
// field.
//
// Why freeArgs and not the structured `COMPILER_PLUGINS` key: the structured
// key was introduced in BTA 2.3.20. The daemon supports the full 2.3.x line
// per ADR 0022 §3; `-Xplugin=` + `applyArgumentStrings` is the one passthrough
// mechanism present across the family (the same mechanism 2.3.20's own
// `Kotlin230AndBelowWrapper` uses internally to shuttle arguments into a
// pre-2.3.20 impl). Verdict source: `spike/bta-compat-138/REPORT.md` §"Plugin-passthrough spike: GREEN".
//
// Plugin-id aliasing dropped: with `-Xplugin=`, kotlinc reads the plugin
// identity from the jar's `META-INF/services/org.jetbrains.kotlin.compiler.
// plugin.CompilerPluginRegistrar` descriptor. The translator only needs to
// forward resolved jar paths; the alias→id map the 2.3.20 adapter needed is
// no longer load-bearing.
//
// The translator is a pure function over (projectRoot, jarResolver). Making
// the jar resolver injectable keeps the unit tests independent of any real
// plugin jars on disk, and lets daemon startup wire in a real resolver that
// reads from `pluginJars: Map<alias, List<path>>` delivered alongside
// `--compiler-jars`.
object PluginTranslator {

    /**
     * Parse `projectRoot/kolt.toml`, pick the enabled `[kotlin.plugins]`
     * entries, ask [jarResolver] for the classpath of each, and emit one
     * `-Xplugin=<path>` argument per resolved jar. Resolver order is
     * preserved.
     *
     * Non-existent `kolt.toml`, absent `[kotlin.plugins]` section, entries
     * set to `false`, and resolver returns that are empty for an enabled
     * alias all collapse to an empty output. The resolver is not called
     * when the parsed map is empty.
     */
    fun translate(
        projectRoot: Path,
        jarResolver: (alias: String) -> List<Path>,
    ): List<String> {
        val tomlFile = projectRoot.resolve("kolt.toml")
        if (!Files.isRegularFile(tomlFile)) return emptyList()
        val enabledAliases = parseEnabledPluginAliases(tomlFile)
        return enabledAliases.flatMap { alias ->
            jarResolver(alias).map { jar -> "-Xplugin=$jar" }
        }
    }

    private fun parseEnabledPluginAliases(tomlFile: Path): List<String> {
        val raw = Files.readString(tomlFile)
        val decoded = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
            .decodeFromString(KoltConfigPluginsView.serializer(), raw)
        return decoded.kotlin.plugins
            .filterValues { it }
            .keys
            .toList()
    }

    @Serializable
    private data class KoltConfigPluginsView(
        // Only the [kotlin.plugins] section matters to this translator;
        // `ignoreUnknownNames` lets every other kolt.toml field pass through
        // without a schema definition.
        @SerialName("kotlin") val kotlin: KotlinView = KotlinView(),
    ) {
        @Serializable
        data class KotlinView(
            @SerialName("plugins") val plugins: Map<String, Boolean> = emptyMap(),
        )
    }
}
