package kolt.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

data class JvmRedirect(
    val group: String,
    val module: String,
    val version: String
)

fun parseJvmRedirect(moduleJson: String): JvmRedirect? {
    val metadata = try {
        lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
    } catch (_: Exception) {
        return null
    }

    var redirect: JvmRedirect? = null
    for (variant in metadata.variants) {
        val platformType = variant.attributes["org.jetbrains.kotlin.platform.type"]?.content
        if (platformType != "jvm") continue

        val availableAt = variant.availableAt
        if (availableAt == null) {
            return null
        }
        if (redirect == null) {
            redirect = JvmRedirect(
                group = availableAt.group,
                module = availableAt.module,
                version = availableAt.version
            )
        }
    }
    return redirect
}

data class NativeRedirect(
    val group: String,
    val module: String,
    val version: String
)

data class NativeArtifact(
    val klibFileUrl: String,
    val klibSha256: String,
    val dependencies: List<NativeDependency>
)

data class NativeDependency(
    val group: String,
    val module: String,
    val version: String
)

// Unlike parseJvmRedirect, a variant without available-at is skipped (not aborted)
// because native modules list many targets in parallel.
fun parseNativeRedirect(moduleJson: String, nativeTarget: String): NativeRedirect? {
    val metadata = try {
        lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
    } catch (_: Exception) {
        return null
    }

    for (variant in metadata.variants) {
        if (!matchesNativeVariant(variant.attributes, nativeTarget)) continue
        val availableAt = variant.availableAt ?: continue
        return NativeRedirect(
            group = availableAt.group,
            module = availableAt.module,
            version = availableAt.version
        )
    }
    return null
}

fun parseNativeArtifact(moduleJson: String, nativeTarget: String): NativeArtifact? {
    val metadata = try {
        lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
    } catch (_: Exception) {
        return null
    }

    for (variant in metadata.variants) {
        if (!matchesNativeVariant(variant.attributes, nativeTarget)) continue
        val klibFile = variant.files.firstOrNull { it.url.endsWith(".klib") } ?: continue
        val deps = variant.dependencies.map { d ->
            NativeDependency(group = d.group, module = d.module, version = d.version.requires)
        }
        return NativeArtifact(
            klibFileUrl = klibFile.url,
            klibSha256 = klibFile.sha256,
            dependencies = deps
        )
    }
    return null
}

internal fun isValidGradleModuleJson(moduleJson: String): Boolean =
    try {
        lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
        true
    } catch (_: Exception) {
        false
    }

private fun matchesNativeVariant(attrs: Map<String, JsonPrimitive>, nativeTarget: String): Boolean =
    attrs["org.jetbrains.kotlin.platform.type"]?.content == "native" &&
        attrs["org.jetbrains.kotlin.native.target"]?.content == nativeTarget &&
        attrs["org.gradle.usage"]?.content == "kotlin-api" &&
        attrs["org.gradle.category"]?.content == "library"

private val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GradleModuleMetadata(
    val variants: List<GradleVariant> = emptyList()
)

// Attribute values use JsonPrimitive, not String, because Gradle Module Metadata
// does not require attribute values to be strings. For example, kotlinx-datetime
// emits `"org.gradle.jvm.version": 8` (integer) on its JVM variants. Callers read
// the value via `.content`, which returns the stringified form for both strings
// and numbers and is sufficient for the literal equality checks kolt performs.
@Serializable
private data class GradleVariant(
    val attributes: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("available-at")
    val availableAt: AvailableAt? = null,
    val files: List<GradleFile> = emptyList(),
    val dependencies: List<GradleDependency> = emptyList()
)

@Serializable
private data class AvailableAt(
    val group: String,
    val module: String,
    val version: String
)

@Serializable
private data class GradleFile(
    val name: String = "",
    val url: String,
    val sha256: String = ""
)

@Serializable
private data class GradleDependency(
    val group: String,
    val module: String,
    val version: GradleVersionSpec
)

@Serializable
private data class GradleVersionSpec(
    val requires: String = ""
)
