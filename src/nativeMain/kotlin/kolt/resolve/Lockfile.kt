package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val LOCKFILE_VERSION = 4

sealed class LockfileError {
  data class ParseFailed(val message: String) : LockfileError()

  data class UnsupportedVersion(val version: Int) : LockfileError()
}

data class LockEntry(
  val version: String,
  val sha256: String,
  val transitive: Boolean = false,
  val test: Boolean = false,
)

data class Lockfile(
  val version: Int,
  val kotlin: String,
  val jvmTarget: String,
  val dependencies: Map<String, LockEntry>,
  val classpathBundles: Map<String, Map<String, LockEntry>> = emptyMap(),
)

// Result of loading kolt.lock at the start of a CLI command. Splits the
// "v3 detected" case into two policy-distinct branches so callers can
// decide whether to migrate (kolt deps install) or refuse to proceed
// (kolt build / test / run). See spec jvm-sys-props design.md, Migration
// Strategy section.
sealed class LockfileLoadResult {
  data class Loaded(val lockfile: Lockfile) : LockfileLoadResult()

  // No kolt.lock on disk — caller proceeds with a fresh resolve.
  data object Absent : LockfileLoadResult()

  // kolt.lock exists but is unparseable — caller proceeds with fresh resolve
  // after surfacing the message. Distinct from UnsupportedAndMigrationDenied
  // because corrupt content is not a versioned breaking change.
  data class Corrupt(val message: String) : LockfileLoadResult()

  // v3 detected and migration is allowed for this command (kolt deps install).
  // Caller emits a warning and proceeds with a fresh resolve.
  data class UnsupportedAndMigrationAllowed(val version: Int) : LockfileLoadResult()

  // v3 detected and migration is NOT allowed for this command (kolt build /
  // test / run). Caller emits an error and exits non-zero.
  data class UnsupportedAndMigrationDenied(val version: Int) : LockfileLoadResult()
}

fun classifyLockfileLoad(jsonString: String?, allowMigration: Boolean): LockfileLoadResult {
  if (jsonString == null) return LockfileLoadResult.Absent
  val parsed = parseLockfile(jsonString)
  val error = parsed.getError()
  if (error == null) {
    val ok = parsed.get() ?: error("invariant: getError() == null implies Ok")
    return LockfileLoadResult.Loaded(ok)
  }
  return when (error) {
    is LockfileError.ParseFailed -> LockfileLoadResult.Corrupt(error.message)
    is LockfileError.UnsupportedVersion ->
      if (allowMigration) {
        LockfileLoadResult.UnsupportedAndMigrationAllowed(error.version)
      } else {
        LockfileLoadResult.UnsupportedAndMigrationDenied(error.version)
      }
  }
}

@Serializable
private data class LockEntryJson(
  val version: String,
  val sha256: String,
  val transitive: Boolean = false,
  @SerialName("test") val test: Boolean = false,
)

@Serializable
private data class LockfileJson(
  val version: Int,
  val kotlin: String,
  @SerialName("jvm_target") val jvmTarget: String,
  val dependencies: Map<String, LockEntryJson>,
  @SerialName("classpath_bundles")
  val classpathBundles: Map<String, Map<String, LockEntryJson>> = emptyMap(),
)

private val lockfileJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
}

fun parseLockfile(jsonString: String): Result<Lockfile, LockfileError> {
  val parsed =
    try {
      lockfileJson.decodeFromString<LockfileJson>(jsonString)
    } catch (e: SerializationException) {
      return Err(LockfileError.ParseFailed("failed to parse kolt.lock: ${e.message}"))
    } catch (e: IllegalArgumentException) {
      return Err(LockfileError.ParseFailed("failed to parse kolt.lock: ${e.message}"))
    }
  if (parsed.version != LOCKFILE_VERSION) {
    return Err(LockfileError.UnsupportedVersion(parsed.version))
  }
  return Ok(
    Lockfile(
      version = parsed.version,
      kotlin = parsed.kotlin,
      jvmTarget = parsed.jvmTarget,
      dependencies =
        parsed.dependencies.mapValues { (_, v) ->
          LockEntry(v.version, v.sha256, v.transitive, v.test)
        },
      classpathBundles =
        parsed.classpathBundles.mapValues { (_, bundleEntries) ->
          bundleEntries.mapValues { (_, v) -> LockEntry(v.version, v.sha256, v.transitive, v.test) }
        },
    )
  )
}

fun serializeLockfile(lockfile: Lockfile): String {
  val sortedDeps =
    lockfile.dependencies.entries
      .sortedBy { it.key }
      .associate { (k, v) -> k to LockEntryJson(v.version, v.sha256, v.transitive, v.test) }
  val sortedBundles =
    lockfile.classpathBundles.entries
      .sortedBy { it.key }
      .associate { (bundleName, entries) ->
        bundleName to
          entries.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to LockEntryJson(v.version, v.sha256, v.transitive, v.test) }
      }
  val json =
    LockfileJson(
      version = lockfile.version,
      kotlin = lockfile.kotlin,
      jvmTarget = lockfile.jvmTarget,
      dependencies = sortedDeps,
      classpathBundles = sortedBundles,
    )
  return lockfileJson.encodeToString(LockfileJson.serializer(), json) + "\n"
}
