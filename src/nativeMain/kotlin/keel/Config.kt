package keel

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

sealed class ConfigError {
    data class ParseFailed(val message: String) : ConfigError()
}

@Serializable
data class KeelConfig(
    val name: String,
    val version: String,
    val kotlin: String,
    val target: String,
    @SerialName("jvm_target") val jvmTarget: String = "17",
    val main: String,
    val sources: List<String>,
    @SerialName("test_sources") val testSources: List<String> = listOf("test"),
    val dependencies: Map<String, String> = emptyMap(),
    @SerialName("test-dependencies") val testDependencies: Map<String, String> = emptyMap(),
    @SerialName("fmt_style") val fmtStyle: String = "google"
)

private val toml = Toml(
    inputConfig = TomlInputConfig(ignoreUnknownNames = true)
)

fun parseConfig(tomlString: String): Result<KeelConfig, ConfigError> {
    return try {
        val config = toml.decodeFromString(KeelConfig.serializer(), tomlString)
        // ktoml preserves quotes in map keys; strip them
        val cleanedDeps = config.dependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
        val cleanedTestDeps = config.testDependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
        Ok(config.copy(dependencies = cleanedDeps, testDependencies = cleanedTestDeps))
    } catch (e: SerializationException) {
        Err(ConfigError.ParseFailed("failed to parse keel.toml: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Err(ConfigError.ParseFailed("failed to parse keel.toml: ${e.message}"))
    }
}
