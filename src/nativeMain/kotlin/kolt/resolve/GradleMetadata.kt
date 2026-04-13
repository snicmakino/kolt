package kolt.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Redirect target when a Kotlin Multiplatform module's .module file
 * redirects the JVM variant to a platform-specific artifact.
 */
data class JvmRedirect(
    val group: String,
    val module: String,
    val version: String
)

/**
 * Parses a Gradle Module Metadata JSON string and extracts the JVM
 * platform redirect if present. Returns null when:
 * - the JSON is invalid
 * - no variant has `org.jetbrains.kotlin.platform.type` = "jvm"
 * - the JVM variant has no `available-at` redirect
 */
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
            // A JVM variant without available-at means the library itself
            // provides a JVM jar — no redirect needed.
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

/**
 * Redirect target for a Kotlin/Native platform variant. A multiplatform root
 * module redirects each native target to a separate module, e.g.
 * `kotlinx-coroutines-core` → `kotlinx-coroutines-core-linuxx64` for linux_x64.
 */
data class NativeRedirect(
    val group: String,
    val module: String,
    val version: String
)

/**
 * A resolved native variant from a platform-specific module file. Carries the
 * `.klib` file reference (relative URL and sha256) and the transitive dependencies
 * declared in the Gradle module metadata.
 */
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

/**
 * Parses a Gradle Module Metadata JSON and extracts the available-at redirect
 * for the given Kotlin/Native target (e.g. "linux_x64"). Returns null when the
 * JSON is invalid, no matching variant exists, or matching variants exist but
 * none have an available-at redirect.
 *
 * A variant matches when ALL of these attributes are present:
 * - `org.jetbrains.kotlin.platform.type` == "native"
 * - `org.jetbrains.kotlin.native.target` == <nativeTarget>
 * - `org.gradle.usage` == "kotlin-api"
 * - `org.gradle.category` == "library"
 *
 * Unlike [parseJvmRedirect], a matching variant without `available-at` is
 * skipped rather than aborting: native modules list many targets in parallel,
 * and an earlier target with no redirect does not imply the requested target
 * is missing.
 */
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

/**
 * Parses a Gradle Module Metadata JSON (a platform-specific redirect target)
 * and extracts the `.klib` file and dependencies for the given Kotlin/Native
 * target. Returns null when the JSON is invalid, no matching variant exists,
 * or the matching variant has no `.klib` file entry.
 */
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

/**
 * Returns true when the JSON decodes as a well-formed Gradle Module Metadata
 * document (v1.1 schema, the subset kolt cares about). Use this to distinguish
 * "JSON is broken" from "JSON is valid but no variant matches our target",
 * since [parseNativeRedirect] and [parseNativeArtifact] both return null in
 * both cases.
 */
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
