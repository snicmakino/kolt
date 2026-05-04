package kolt.daemon.ic

import java.nio.file.Path
import java.security.MessageDigest

// Pure functions that compute the on-disk layout of daemon-owned IC state,
// per ADR 0019 §5.
//
// Path composition:
//
//   <icRoot> / <kotlinVersion> / <sha256(projectRoot)> / <scope>
//
// where `sha256(projectRoot)` is the first 16 bytes of the digest rendered
// as lowercase hex (32 chars), and `<scope>` is the compile scope
// (`main` / `test`). The scope segment is load-bearing: BTA persists its
// `inputsCache` under `workingDir/inputs/`, which is keyed by source-file
// path. Sharing a workingDir between main and test compiles in the same
// project means each compile sees the other compile's sources as
// "removed" (because the test compile's source list does not include the
// main sources, and vice versa) and triggers
// `removeOutputForSourceFiles` against the previously-tracked
// `<source>.kt` -> `<class>.class` mapping — wiping `build/classes/` on
// the next test compile (#376 root cause). Per-scope segmentation
// matches Gradle KGP's one-task-one-workingDir model.
//
// The version segment is the Kotlin compiler version the daemon is
// currently pinned to. ADR §5 requires it so that bumping the compiler
// invalidates the cache in a single move: the daemon simply starts
// writing under the new version segment, leaving the old one for a
// future reaper (ADR §Negative) to clean up.
object IcStateLayout {

  // On-disk names under `<icRoot>/<kotlinVersion>/<projectId>/`.
  // `BtaIncrementalCompiler` writes these and `IcReaper` reads them;
  // collecting the constants here keeps the layout contract in one
  // file. BTA's own state lives one level deeper under `BTA_SUBDIR`
  // so the reaper-facing metadata is not swept away by BTA's
  // cold-path housekeeping of its `workingDirectory`.
  const val BREADCRUMB_FILE: String = "project.path"
  const val LOCK_FILE: String = "LOCK"
  const val BTA_SUBDIR: String = "bta"
  const val CLASSPATH_SNAPSHOTS_SUBDIR: String = "classpath-snapshots"

  // The native client mirrors this in
  // `src/nativeMain/kotlin/kolt/build/daemon/IcStateCleanup.kt`
  // (`daemonIcProjectIdOf`). Update both when changing the algorithm.
  fun projectIdFor(projectRoot: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(projectRoot.toString().toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it) }
  }

  fun workingDirFor(
    icRoot: Path,
    kotlinVersion: String,
    projectRoot: Path,
    scope: CompileScope,
  ): Path = icRoot.resolve(kotlinVersion).resolve(projectIdFor(projectRoot)).resolve(scope.segment)

  fun classpathSnapshotsDirFor(icRoot: Path, kotlinVersion: String): Path =
    icRoot.resolve(kotlinVersion).resolve(CLASSPATH_SNAPSHOTS_SUBDIR)
}

// Compile-scope axis added by #376: ADR 0019 §5 originally assumed
// "1 project => 1 module => 1 outputDir". JVM `kolt test` breaks that
// assumption — main and test compiles share the project but produce
// distinct outputDirs, and BTA's per-source inputsCache cannot be
// shared across them. Each scope gets its own workingDir under the
// shared projectId directory.
enum class CompileScope(val segment: String) {
  Main("main"),
  Test("test"),
}
