package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed class LockfileError {
    data class ParseFailed(val message: String) : LockfileError()
    data class UnsupportedVersion(val version: Int) : LockfileError()
}

data class LockEntry(val version: String, val sha256: String)

/**
 * Domain model for keel.lock. Serialized as JSON:
 * ```json
 * {
 *   "version": 1,
 *   "kotlin": "2.1.0",
 *   "jvm_target": "17",
 *   "dependencies": {
 *     "group:artifact": { "version": "1.0.0", "sha256": "..." }
 *   }
 * }
 * ```
 */
data class Lockfile(
    val version: Int,
    val kotlin: String,
    val jvmTarget: String,
    val dependencies: Map<String, LockEntry>
)

@Serializable
private data class LockEntryJson(
    val version: String,
    val sha256: String
)

@Serializable
private data class LockfileJson(
    val version: Int,
    val kotlin: String,
    @SerialName("jvm_target") val jvmTarget: String,
    val dependencies: Map<String, LockEntryJson>
)

private val lockfileJson = Json { prettyPrint = true }

fun parseLockfile(jsonString: String): Result<Lockfile, LockfileError> {
    val parsed = try {
        lockfileJson.decodeFromString<LockfileJson>(jsonString)
    } catch (e: SerializationException) {
        return Err(LockfileError.ParseFailed("failed to parse keel.lock: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        return Err(LockfileError.ParseFailed("failed to parse keel.lock: ${e.message}"))
    }
    if (parsed.version != 1) {
        return Err(LockfileError.UnsupportedVersion(parsed.version))
    }
    return Ok(
        Lockfile(
            version = parsed.version,
            kotlin = parsed.kotlin,
            jvmTarget = parsed.jvmTarget,
            dependencies = parsed.dependencies.mapValues { (_, v) ->
                LockEntry(v.version, v.sha256)
            }
        )
    )
}

fun serializeLockfile(lockfile: Lockfile): String {
    val sorted = lockfile.dependencies.entries.sortedBy { it.key }.associate { (k, v) ->
        k to LockEntryJson(v.version, v.sha256)
    }
    val json = LockfileJson(
        version = lockfile.version,
        kotlin = lockfile.kotlin,
        jvmTarget = lockfile.jvmTarget,
        dependencies = sorted
    )
    return lockfileJson.encodeToString(LockfileJson.serializer(), json) + "\n"
}
