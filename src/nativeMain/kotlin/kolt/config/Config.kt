package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"

val VALID_TARGETS = setOf("jvm", "native")

sealed class ConfigError {
    data class ParseFailed(val message: String) : ConfigError()
}

@Serializable
data class CinteropConfig(
    val name: String,
    val def: String,
    @SerialName("package") val packageName: String? = null
)

@Serializable
data class KotlinSection(
    val version: String,
    val plugins: Map<String, Boolean> = emptyMap()
)

@Serializable
data class BuildSection(
    val target: String,
    @SerialName("jvm_target") val jvmTarget: String = "17",
    val jdk: String? = null,
    val main: String,
    val sources: List<String>,
    @SerialName("test_sources") val testSources: List<String> = listOf("test"),
    val resources: List<String> = emptyList(),
    @SerialName("test_resources") val testResources: List<String> = emptyList()
)

@Serializable
data class FmtSection(
    val style: String = "google"
)

@Serializable
data class KoltConfig(
    val name: String,
    val version: String,
    val kotlin: KotlinSection,
    val build: BuildSection,
    val fmt: FmtSection = FmtSection(),
    val dependencies: Map<String, String> = emptyMap(),
    @SerialName("test-dependencies") val testDependencies: Map<String, String> = emptyMap(),
    val repositories: Map<String, String> = mapOf("central" to MAVEN_CENTRAL_BASE),
    val cinterop: List<CinteropConfig> = emptyList()
)

private val toml = Toml(
    inputConfig = TomlInputConfig(ignoreUnknownNames = true)
)

fun parseConfig(tomlString: String): Result<KoltConfig, ConfigError> {
    return try {
        val config = toml.decodeFromString(KoltConfig.serializer(), tomlString)
        if (config.build.target !in VALID_TARGETS) {
            return Err(ConfigError.ParseFailed(
                "invalid target '${config.build.target}' (valid targets: ${VALID_TARGETS.joinToString(", ")})"
            ))
        }
        validateMainFqn(config.build.main).getError()?.let { return Err(it) }
        // ktoml preserves quotes in map keys; strip them.
        val cleanedDeps = config.dependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
        val cleanedTestDeps = config.testDependencies.mapKeys { (key, _) -> key.removeSurrounding("\"") }
        val cleanedRepos = config.repositories
            .mapKeys { (key, _) -> key.removeSurrounding("\"") }
            .mapValues { (_, url) -> url.trimEnd('/') }
        Ok(config.copy(dependencies = cleanedDeps, testDependencies = cleanedTestDeps, repositories = cleanedRepos))
    } catch (e: SerializationException) {
        Err(ConfigError.ParseFailed("failed to parse kolt.toml: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Err(ConfigError.ParseFailed("failed to parse kolt.toml: ${e.message}"))
    }
}
