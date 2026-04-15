package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.infra.WriteFailed

// #65: Kotlin compiler plugin jars are fetched directly from Maven Central
// at a fixed coordinate (one GET per first use, no POM / module metadata /
// transitive traversal). The existing dependency resolver deliberately is
// not reused: `ResolverDeps` carries `readFileContent` for POM parsing and
// the whole `downloadFromRepositories` stack keys off a caller-supplied
// repository list. Neither is meaningful here — the plugin jars are
// shipped by JetBrains at exactly one coordinate on exactly one repo.
// A tiny dedicated seam keeps the fetcher testable without dragging the
// resolver's POM machinery along, and leaves the `[repositories]` config
// untouched so a future contributor cannot accidentally make compiler
// plugins resolvable from a user-provided mirror. See the issue #65
// discussion for the repository-selection rationale.

sealed interface PluginFetchError {
    data class UnknownPlugin(val alias: String) : PluginFetchError
    data class DownloadFailed(
        val alias: String,
        val url: String,
        val cause: DownloadError,
    ) : PluginFetchError
    data class CacheDirCreationFailed(val path: String) : PluginFetchError
    data class HashComputationFailed(val path: String) : PluginFetchError
    data class StampWriteFailed(val path: String) : PluginFetchError
}

// Thin I/O seam, intentionally narrower than `ResolverDeps`. Production
// wires each method to the corresponding `kolt.infra` top-level helper;
// tests substitute an object fake that records every call. [warn] is
// deliberately part of the seam rather than a top-level `eprintln`
// import so cache-corruption observability (review finding #4) stays
// assertable in unit tests without spilling to real stderr.
interface PluginFetcherDeps {
    fun fileExists(path: String): Boolean
    fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed>
    fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError>
    fun computeSha256(filePath: String): Result<String, Sha256Error>
    fun readFileAsString(path: String): Result<String, OpenFailed>
    fun writeFileAsString(path: String, content: String): Result<Unit, WriteFailed>
    fun warn(message: String)
}

// Maven Central is pinned: compiler plugin jars are a JetBrains-published
// artefact and the user-facing `[repositories]` config cannot meaningfully
// override them. Changing this base URL would be a breaking config move.
const val MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2"

// Alias → Maven artefact id under `org.jetbrains.kotlin:`. The alias set
// mirrors the native subprocess path's `PLUGIN_JAR_NAMES` so a project's
// `kolt.toml [plugins]` key means the same thing on every code path
// (daemon, subprocess fallback, native build/test). NB: the Maven
// artefact name differs from the kotlinc-sidecar jar name for the
// serialization plugin — kotlinc ships `kotlinx-serialization-compiler-
// plugin.jar` (with an `x`) but Maven publishes `kotlin-serialization-
// compiler-plugin-<ver>.jar` (no `x`). Verified against
// https://repo1.maven.org/maven2/org/jetbrains/kotlin/ on 2026-04-15.
private val ALIAS_TO_ARTIFACT: Map<String, String> = mapOf(
    "serialization" to "kotlin-serialization-compiler-plugin",
    "allopen" to "kotlin-allopen-compiler-plugin",
    "noarg" to "kotlin-noarg-compiler-plugin",
)

/**
 * Ensures that the compiler plugin jar for [alias] at [kotlinVersion] is
 * present under [cacheBase] and returns its absolute path.
 *
 * The fetcher uses a TOFU sha256 stamp next to the cached jar: on first
 * download the local sha256 is computed and written to `<jar>.sha256`,
 * and on subsequent calls the jar is re-hashed and compared against the
 * stamp. A missing or mismatched stamp triggers a re-download (self-
 * healing against legacy cache contents or bit rot). Maven Central is
 * pinned; see the module doc for the rationale.
 */
fun fetchPluginJar(
    alias: String,
    kotlinVersion: String,
    cacheBase: String,
    deps: PluginFetcherDeps,
): Result<String, PluginFetchError> {
    val artifact = ALIAS_TO_ARTIFACT[alias]
        ?: return Err(PluginFetchError.UnknownPlugin(alias))

    val artifactDir = "$cacheBase/org/jetbrains/kotlin/$artifact/$kotlinVersion"
    val jarPath = "$artifactDir/$artifact-$kotlinVersion.jar"
    val stampPath = "$jarPath.sha256"
    val url = "$MAVEN_CENTRAL_BASE_URL/org/jetbrains/kotlin/$artifact/$kotlinVersion/$artifact-$kotlinVersion.jar"

    // Cache hit path: both jar and stamp must exist, stamp must match the
    // freshly-computed hash. Any deviation drops through to the download
    // path below — the cost of re-downloading a ~1 MB plugin jar is
    // negligible compared to the correctness risk of trusting a stale or
    // partial cache entry.
    //
    // Review finding #4: silent self-heal can mask a systematic
    // filesystem failure (stamps repeatedly unreadable, computeSha256
    // repeatedly failing, mismatches on every run) where every build
    // pays a re-download for no user-visible reason. Each of the three
    // failure modes surfaces a single `warn()` line so a user staring
    // at "why is every build slow" has something to grep for in stderr.
    if (deps.fileExists(jarPath) && deps.fileExists(stampPath)) {
        val storedStamp = deps.readFileAsString(stampPath).getOrElse { err ->
            deps.warn("plugin cache: could not read stamp $stampPath ($err); re-downloading")
            null
        }?.trim()
        val actualHash = deps.computeSha256(jarPath).getOrElse { err ->
            deps.warn("plugin cache: could not hash $jarPath ($err); re-downloading")
            null
        }
        if (storedStamp != null && actualHash != null) {
            if (storedStamp == actualHash) {
                return Ok(jarPath)
            }
            deps.warn(
                "plugin cache: sha256 mismatch for $jarPath " +
                    "(stamp=$storedStamp, actual=$actualHash); re-downloading",
            )
        }
    }

    deps.ensureDirectoryRecursive(artifactDir).getOrElse {
        return Err(PluginFetchError.CacheDirCreationFailed(artifactDir))
    }

    deps.downloadFile(url, jarPath).getOrElse { cause ->
        return Err(PluginFetchError.DownloadFailed(alias, url, cause))
    }

    val freshHash = deps.computeSha256(jarPath).getOrElse {
        return Err(PluginFetchError.HashComputationFailed(jarPath))
    }

    deps.writeFileAsString(stampPath, freshHash).getOrElse {
        return Err(PluginFetchError.StampWriteFailed(stampPath))
    }

    return Ok(jarPath)
}

/**
 * Batch variant used by the compile path callers. Iterates enabled
 * aliases in declaration order and fetches each plugin jar; aborts on
 * the first error so a missing jar does not silently produce a
 * half-plugged plugin set. Returns a LinkedHashMap to preserve iteration
 * order for the `pluginsFingerprint` helper in BuildCommands, which
 * sorts internally but benefits from a stable input for diffing.
 */
fun fetchEnabledPluginJars(
    plugins: Map<String, Boolean>,
    kotlinVersion: String,
    cacheBase: String,
    deps: PluginFetcherDeps,
): Result<Map<String, String>, PluginFetchError> {
    val out = linkedMapOf<String, String>()
    for ((alias, enabled) in plugins) {
        if (!enabled) continue
        val path = fetchPluginJar(alias, kotlinVersion, cacheBase, deps).getOrElse {
            return Err(it)
        }
        out[alias] = path
    }
    return Ok(out)
}
