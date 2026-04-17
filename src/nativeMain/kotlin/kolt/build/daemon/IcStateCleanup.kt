package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.RemoveFailed
import kolt.infra.fileExists
import kolt.infra.listSubdirectories
import kolt.infra.removeDirectoryRecursive
import kolt.infra.sha256Hex

// Mirrors the daemon-side `IcStateLayout.projectIdFor` (ADR 0019 §5).
// `projectHashOf` is intentionally not reused: it produces an 8-byte
// digest used only for the daemon socket directory, and the two ID
// shapes must stay independent.
internal fun daemonIcProjectIdOf(absProjectPath: String): String =
    sha256Hex(absProjectPath.encodeToByteArray()).take(32)

// Removes daemon-owned IC state directories for a project across all
// kotlinVersion segments under `<icRoot>/`. Called from `kolt clean` so
// the daemon does not skip recompilation against state pointing at a
// just-deleted `build/classes/` (issue #135).
internal fun cleanDaemonIcStateForProject(
    paths: KoltPaths,
    absProjectPath: String,
): Result<Unit, RemoveFailed> {
    val icRoot = paths.daemonIcDir
    if (!fileExists(icRoot)) return Ok(Unit)
    val projectId = daemonIcProjectIdOf(absProjectPath)
    // Listing failure after the existence check is exotic (TOCTOU / perm flip);
    // treat it as "nothing to clean" rather than failing the user-visible clean.
    val versions = listSubdirectories(icRoot).getOrElse { return Ok(Unit) }
    for (version in versions) {
        val projectIcDir = "$icRoot/$version/$projectId"
        if (!fileExists(projectIcDir)) continue
        removeDirectoryRecursive(projectIcDir).getOrElse { err -> return Err(err) }
    }
    return Ok(Unit)
}
