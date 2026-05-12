package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class RawLocalOverlayConfig(
  val test: RawTestSection? = null,
  val run: RawRunSection? = null,
  val repositories: Map<String, RawRepository>? = null,
)

private val overlayToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

internal fun parseLocalOverlay(
  tomlString: String,
  path: String,
): Result<RawLocalOverlayConfig, ConfigError> {
  return try {
    Ok(overlayToml.decodeFromString(RawLocalOverlayConfig.serializer(), tomlString))
  } catch (e: SerializationException) {
    Err(buildKtomlParseError(e.message, path, tomlString, sourceFile = KOLT_LOCAL_TOML))
  } catch (e: IllegalArgumentException) {
    Err(buildKtomlParseError(e.message, path, tomlString, sourceFile = KOLT_LOCAL_TOML))
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun mergeOverlay(
  base: RawKoltConfig,
  overlay: RawLocalOverlayConfig,
  overlayPath: String,
): Result<RawKoltConfig, ConfigError> {
  val mergedTest =
    if (overlay.test == null) {
      base.test
    } else {
      RawTestSection(
        sysProps = mergeSysProps(base.test?.sysProps ?: emptyMap(), overlay.test.sysProps)
      )
    }
  val mergedRun =
    if (overlay.run == null) {
      base.run
    } else {
      RawRunSection(
        sysProps = mergeSysProps(base.run?.sysProps ?: emptyMap(), overlay.run.sysProps)
      )
    }
  return Ok(base.copy(test = mergedTest, run = mergedRun))
}

private fun mergeSysProps(
  base: Map<String, RawSysPropValue>,
  overlay: Map<String, RawSysPropValue>,
): Map<String, RawSysPropValue> {
  val merged = LinkedHashMap<String, RawSysPropValue>(base)
  for ((key, value) in overlay) {
    merged[key] = value
  }
  return merged
}
