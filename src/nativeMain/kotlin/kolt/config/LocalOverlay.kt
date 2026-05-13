package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
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
  val mergedRepositories =
    if (overlay.repositories == null) {
      base.repositories
    } else {
      mergeRepositories(base.repositories, overlay.repositories, overlayPath).getOrElse {
        return Err(it)
      }
    }
  return Ok(base.copy(test = mergedTest, run = mergedRun, repositories = mergedRepositories))
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

// ktoml preserves surrounding quotes on bare-quoted map keys (`[repositories."x"]`
// decodes the key as the literal `"x"`). `liftRepositoriesMap` strips them on
// the post-merge lift; we strip them on BOTH base and overlay before lookup so
// `[repositories."central"]` in kolt.toml merges with `[repositories.central]`
// in kolt.local.toml regardless of which side quoted the name.
private fun mergeRepositories(
  base: Map<String, RawRepository>,
  overlay: Map<String, RawRepository>,
  overlayPath: String,
): Result<Map<String, RawRepository>, ConfigError> {
  val merged = LinkedHashMap<String, RawRepository>(base.size)
  for ((rawName, baseRepo) in base) {
    merged[rawName.removeSurrounding("\"")] = baseRepo
  }
  for ((rawName, overlayRepo) in overlay) {
    val name = rawName.removeSurrounding("\"")
    val baseRepo = merged[name]
    if (baseRepo == null) {
      return Err(
        ConfigError.ParseFailed(
          message = "repository '$name' declared in $overlayPath but not in kolt.toml",
          path = overlayPath,
        )
      )
    }
    merged[name] = baseRepo.copy(url = overlayRepo.url ?: baseRepo.url)
  }
  return Ok(merged)
}
