package kolt.daemon.ic

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

// #162: when `[kotlin] compiler` pins a kotlinc newer than `[kotlin] version`
// (e.g. compiler=2.3.20 / version=2.1.0 to let 2.1-language projects run on the
// daemon), emit `-language-version` / `-api-version` freeArgs so the compile
// targets `version`, not `compiler`. Collapses to an empty list whenever the
// two match (or `compiler` is unset) — keeps the common case warning-free.
//
// Runs in the same `applyArgumentStrings` batch as PluginTranslator output:
// the BTA ordering invariant (see BtaIncrementalCompiler header comment) resets
// every argument the call does not mention, so splitting these into a second
// applyArgumentStrings would wipe the plugin freeArgs. Daemon core stays free
// of a BTA-shaped `compilerArguments` field per ADR 0019 §3; the adapter reads
// `kolt.toml` directly here for the same reason PluginTranslator does.
object LanguageVersionTranslator {

    fun translate(projectRoot: Path): List<String> {
        val tomlFile = projectRoot.resolve("kolt.toml")
        if (!Files.isRegularFile(tomlFile)) return emptyList()
        val view = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
            .decodeFromString(KotlinView.serializer(), Files.readString(tomlFile))
        val version = view.kotlin.version ?: return emptyList()
        val compiler = view.kotlin.compiler ?: return emptyList()
        if (compiler == version) return emptyList()
        // kotlinc rejects patch-level values: `-api-version 2.1.0` →
        // `Unknown -api-version value: 2.1.0`. Language/API surface is
        // addressed by `major.minor` only.
        val surface = version.split('.').take(2).joinToString(".")
        return listOf("-language-version", surface, "-api-version", surface)
    }

    @Serializable
    private data class KotlinView(
        @SerialName("kotlin") val kotlin: Section = Section(),
    ) {
        @Serializable
        data class Section(
            val version: String? = null,
            val compiler: String? = null,
        )
    }
}
