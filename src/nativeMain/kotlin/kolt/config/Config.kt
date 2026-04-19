package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kolt.resolve.compareVersions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"

// Kotlin/Native target identifiers, matching `KonanTarget.<n>.name`. Gradle
// Module Metadata uses snake_case variants of these (`linux_x64` etc.); the
// schema-to-metadata mapping lives inside NativeResolver.
val NATIVE_TARGETS = setOf("linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64")

val VALID_TARGETS = setOf("jvm") + NATIVE_TARGETS

val VALID_KINDS = setOf("app", "lib")

// Maps a schema-level KonanTarget identifier (camelCase, e.g. `linuxX64`) to
// the snake_case form used by both konanc CLI (`-target linux_x64`) and the
// `org.jetbrains.kotlin.native.target` attribute in Gradle Module Metadata.
// Precondition: `target in NATIVE_TARGETS`. The regex form is total so it
// never throws; callers are responsible for validating the input first.
fun konanTargetGradleName(target: String): String =
    target.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

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
    val compiler: String? = null,
    val plugins: Map<String, Boolean> = emptyMap()
) {
    // Compiler/daemon/toolchain selection uses this. `version` alone stays the
    // language/API version (lockfile key, -language-version flag). When
    // `compiler` is unset, the two collapse — identical to pre-#162 behavior.
    val effectiveCompiler: String get() = compiler ?: version
}

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
    val kind: String = "app",
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

private fun validateKind(kind: String): Result<Unit, ConfigError> {
    if (kind == "lib") {
        return Err(ConfigError.ParseFailed(
            "kind = \"lib\" is reserved but not yet implemented (ADR 0023)"
        ))
    }
    if (kind !in VALID_KINDS) {
        return Err(ConfigError.ParseFailed(
            "invalid kind '$kind' (valid kinds: ${VALID_KINDS.joinToString(", ")})"
        ))
    }
    return Ok(Unit)
}

private fun validateTarget(target: String): Result<Unit, ConfigError> {
    if (target == "native") {
        return Err(ConfigError.ParseFailed(
            "target = \"native\" is no longer accepted. " +
                "Use a specific Konan target, e.g. target = \"linuxX64\""
        ))
    }
    if (target !in VALID_TARGETS) {
        return Err(ConfigError.ParseFailed(
            "invalid target '$target' (valid targets: ${VALID_TARGETS.joinToString(", ")})"
        ))
    }
    return Ok(Unit)
}

fun parseConfig(tomlString: String): Result<KoltConfig, ConfigError> {
    return try {
        val config = toml.decodeFromString(KoltConfig.serializer(), tomlString)
        validateKind(config.kind).getError()?.let { return Err(it) }
        validateTarget(config.build.target).getError()?.let { return Err(it) }
        validateMainFqn(config.build.main).getError()?.let { return Err(it) }
        config.kotlin.compiler?.let { compiler ->
            if (compareVersions(compiler, config.kotlin.version) < 0) {
                return Err(ConfigError.ParseFailed(
                    "[kotlin] compiler '$compiler' is lower than version '${config.kotlin.version}' " +
                        "(a compiler cannot target a newer language than itself)"
                ))
            }
        }
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
