package kolt.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

data class JvmRedirect(val group: String, val module: String, val version: String)

fun parseJvmRedirect(moduleJson: String): JvmRedirect? {
  val metadata =
    try {
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
      redirect =
        JvmRedirect(
          group = availableAt.group,
          module = availableAt.module,
          version = availableAt.version,
        )
    }
  }
  return redirect
}

data class NativeRedirect(val group: String, val module: String, val version: String)

// Kotlin/Native variants can ship multiple `.klib` files per variant: the
// platform klib plus zero or more cinterop sub-klibs (e.g. ktor-utils ships
// `ktor-utils-linuxx64-3.4.3.klib` together with
// `ktor-utils-linuxx64-3.4.3-cinterop-threadUtils.klib`). All sub-klibs must
// be fetched and passed to konanc as `-l` so manifest-declared cinterop
// dependencies link correctly. See ADR 0010.
data class NativeArtifact(val klibFiles: List<KlibFile>, val dependencies: List<NativeDependency>)

data class KlibFile(val url: String, val sha256: String)

data class NativeDependency(
  val group: String,
  val module: String,
  val version: String,
  val strict: Boolean = false,
  val rejects: List<String> = emptyList(),
)

// Unlike parseJvmRedirect, a variant without available-at is skipped (not aborted)
// because native modules list many targets in parallel.
fun parseNativeRedirect(moduleJson: String, nativeTarget: String): NativeRedirect? {
  val metadata =
    try {
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
      version = availableAt.version,
    )
  }
  return null
}

fun parseNativeArtifact(moduleJson: String, nativeTarget: String): NativeArtifact? {
  val metadata =
    try {
      lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
    } catch (_: Exception) {
      return null
    }

  for (variant in metadata.variants) {
    if (!matchesNativeVariant(variant.attributes, nativeTarget)) continue
    val klibFiles = variant.files.filter { it.url.endsWith(".klib") }
    if (klibFiles.isEmpty()) continue
    // `GradleFile.url` is concatenated verbatim as the trailing path segment
    // of the cache write target. A url containing `..`, `/`, or `\` would
    // let malformed or hostile metadata escape the coordinate's cache
    // directory and write elsewhere on disk. Reject the whole metadata
    // rather than silently dropping the offending entry, so a wrong-shape
    // .module fails loudly. Legitimate klib filenames are bare basenames
    // (`<artifact>-<version>[-cinterop-<name>].klib`) — none of these
    // checks reject a well-formed file.
    if (klibFiles.any { !isSafeKlibFilename(it.url) }) return null
    val deps =
      variant.dependencies.map { d ->
        val version = d.version.selectedVersion()
        if (version.isEmpty()) return null
        NativeDependency(
          group = d.group,
          module = d.module,
          version = version,
          strict = d.version.strictly.isNotEmpty(),
          rejects = d.version.rejects,
        )
      }
    return NativeArtifact(
      klibFiles = klibFiles.map { KlibFile(url = it.url, sha256 = it.sha256) },
      dependencies = deps,
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

private fun isSafeKlibFilename(url: String): Boolean =
  !url.contains('/') && !url.contains('\\') && !url.contains("..")

private fun matchesNativeVariant(attrs: Map<String, JsonPrimitive>, nativeTarget: String): Boolean =
  attrs["org.jetbrains.kotlin.platform.type"]?.content == "native" &&
    attrs["org.jetbrains.kotlin.native.target"]?.content == nativeTarget &&
    attrs["org.gradle.usage"]?.content == "kotlin-api" &&
    attrs["org.gradle.category"]?.content == "library"

private val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GradleModuleMetadata(val variants: List<GradleVariant> = emptyList())

// Attribute values use JsonPrimitive, not String, because Gradle Module Metadata
// does not require attribute values to be strings. For example, kotlinx-datetime
// emits `"org.gradle.jvm.version": 8` (integer) on its JVM variants. Callers read
// the value via `.content`, which returns the stringified form for both strings
// and numbers and is sufficient for the literal equality checks kolt performs.
@Serializable
private data class GradleVariant(
  val attributes: Map<String, JsonPrimitive> = emptyMap(),
  @SerialName("available-at") val availableAt: AvailableAt? = null,
  val files: List<GradleFile> = emptyList(),
  val dependencies: List<GradleDependency> = emptyList(),
)

@Serializable
private data class AvailableAt(val group: String, val module: String, val version: String)

@Serializable
private data class GradleFile(val name: String = "", val url: String, val sha256: String = "")

@Serializable
private data class GradleDependency(
  val group: String,
  val module: String,
  val version: GradleVersionSpec,
)

// Precedence: strictly > requires > prefers. This mirrors Gradle's own
// "winning version" selection for a single constraint declaration. `rejects`
// filters candidates during graph resolution; see NativeResolver.
@Serializable
private data class GradleVersionSpec(
  val requires: String = "",
  val strictly: String = "",
  val prefers: String = "",
  val rejects: List<String> = emptyList(),
)

private fun GradleVersionSpec.selectedVersion(): String =
  strictly.ifEmpty { requires.ifEmpty { prefers } }
