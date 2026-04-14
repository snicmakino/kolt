package kolt.daemon.ic

import java.nio.file.Path
import java.security.MessageDigest

// Pure functions that compute the on-disk layout of daemon-owned IC state,
// per ADR 0019 §5. Centralising the hash algorithm and the directory
// composition here is deliberate: B-2a review carryover #6 flagged that
// `projectId` hashing and `workingDir` derivation were split across two
// call sites (DaemonServer vs the placeholder IcRequest.workingDir), which
// would break as soon as sub-project / nested layouts require `workingDir`
// to differ from `projectRoot`. After this file lands there is exactly
// one producer of both values.
//
// Path composition:
//
//   <icRoot> / <kotlinVersion> / <sha256(projectRoot)>
//
// where `sha256(projectRoot)` is the first 16 bytes of the digest rendered
// as lowercase hex (32 chars). The short-prefix form is the same one
// `DaemonServer.projectIdFor` used before this refactor, so existing IC
// state directories from earlier daemon runs stay addressable.
//
// The version segment is the Kotlin compiler version the daemon is
// currently pinned to. ADR §5 requires it so that bumping the compiler
// invalidates the cache in a single move: the daemon simply starts
// writing under the new version segment, leaving the old one for a
// future reaper (ADR §Negative) to clean up.
object IcStateLayout {

    fun projectIdFor(projectRoot: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(projectRoot.toString().toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun workingDirFor(icRoot: Path, kotlinVersion: String, projectRoot: Path): Path =
        icRoot.resolve(kotlinVersion).resolve(projectIdFor(projectRoot))
}
